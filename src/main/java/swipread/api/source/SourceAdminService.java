package swipread.api.source;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import swipread.api.article.ArticleRepository;
import swipread.api.article.Source;
import swipread.api.common.ApiException;
import swipread.api.source.dto.CreateSourceRequest;
import swipread.api.source.dto.SourceResponse;
import swipread.api.source.dto.UpdateSourceRequest;
import swipread.api.topic.TopicRepository;
import swipread.api.topic.dto.TopicResponse;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class SourceAdminService {

    private static final Logger log = LoggerFactory.getLogger(SourceAdminService.class);

    private final SourceRepository sources;
    private final SourceTopics sourceTopics;
    private final TopicRepository topics;
    private final ArticleRepository articles;
    private final FeedProbe probe;
    private final TransactionTemplate transaction;

    SourceAdminService(SourceRepository sources, SourceTopics sourceTopics, TopicRepository topics,
                       ArticleRepository articles, FeedProbe probe, TransactionTemplate transaction) {
        this.sources = sources;
        this.sourceTopics = sourceTopics;
        this.topics = topics;
        this.articles = articles;
        this.probe = probe;
        this.transaction = transaction;
    }

    @Transactional(readOnly = true)
    public List<SourceResponse> list() {
        Map<Integer, List<TopicResponse>> topicsBySource = sourceTopics.all();
        return sources.findAllByOrderByNameAsc().stream()
                .map(source -> SourceResponse.from(source, topicsBySource.getOrDefault(source.getId(), List.of())))
                .toList();
    }

    /**
     * Le flux est lu avant l'enregistrement, hors transaction : pas de connexion en base tenue pendant l'appel réseau.
     * La source et ses thèmes sont ensuite enregistrés dans la même transaction.
     */
    public SourceResponse create(CreateSourceRequest request) {
        checkTopics(request.topicIds());
        String feedUrl = request.feedUrl().strip();
        if (sources.existsByFeedUrl(feedUrl)) {
            throw ApiException.conflict("A source already uses this feed URL");
        }
        int entries;
        try {
            entries = probe.countEntries(feedUrl);
        } catch (FeedProbe.UnreadableFeedException e) {
            log.info("Feed check failed for {}: {}", feedUrl, e.getMessage());
            throw ApiException.unprocessable(e.getMessage());
        }
        if (entries == 0) {
            throw ApiException.unprocessable("Feed has no articles");
        }
        String license = request.license() == null || request.license().isBlank() ? null : request.license().strip();
        Source source = new Source(request.name().strip(), request.siteUrl().strip(), feedUrl, request.language(), license);
        try {
            return transaction.execute(status -> {
                Source saved = sources.saveAndFlush(source);
                sourceTopics.replace(saved.getId(), request.topicIds());
                return SourceResponse.from(saved, sourceTopics.of(saved.getId()));
            });
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("A source already uses this feed URL");
        }
    }

    @Transactional
    public SourceResponse update(int id, UpdateSourceRequest request) {
        if (request.active() == null && request.topicIds() == null) {
            throw ApiException.badRequest("Nothing to update: give active or topicIds");
        }
        Source source = get(id);
        if (request.topicIds() != null) {
            checkTopics(request.topicIds());
            sourceTopics.replace(id, request.topicIds());
        }
        if (request.active() != null) {
            source.setActive(request.active());
        }
        return SourceResponse.from(source, sourceTopics.of(id));
    }

    /**
     * Une source qui a des articles ne peut pas être supprimée : il faut la désactiver. Supprimer ses articles
     * effacerait aussi les sauvegardés et l'historique des lecteurs.
     */
    @Transactional
    public void delete(int id) {
        Source source = get(id);
        if (articles.existsBySourceId(id)) {
            throw ApiException.conflict("Source has articles: deactivate it instead");
        }
        sources.delete(source);
    }

    /** L'ordre compte (le premier thème est le thème principal des articles) : pas de doublon, que des thèmes connus. */
    private void checkTopics(List<Integer> topicIds) {
        Set<Integer> distinct = new LinkedHashSet<>(topicIds);
        if (distinct.size() != topicIds.size()) {
            throw ApiException.badRequest("Topic ids must not repeat");
        }
        Set<Integer> unknown = new HashSet<>(distinct);
        topics.findAllById(distinct).forEach(topic -> unknown.remove(topic.getId()));
        if (!unknown.isEmpty()) {
            throw ApiException.badRequest("Unknown topic ids: " + unknown);
        }
    }

    private Source get(int id) {
        return sources.findById(id).orElseThrow(() -> ApiException.notFound("Source not found"));
    }
}
