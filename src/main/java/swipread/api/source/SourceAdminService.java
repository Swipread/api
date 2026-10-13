package swipread.api.source;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import swipread.api.article.ArticleRepository;
import swipread.api.article.Source;
import swipread.api.common.ApiException;
import swipread.api.source.dto.CreateSourceRequest;
import swipread.api.source.dto.SourceResponse;

import java.util.List;

@Service
public class SourceAdminService {

    private static final Logger log = LoggerFactory.getLogger(SourceAdminService.class);

    private final SourceRepository sources;
    private final ArticleRepository articles;
    private final FeedProbe probe;

    SourceAdminService(SourceRepository sources, ArticleRepository articles, FeedProbe probe) {
        this.sources = sources;
        this.articles = articles;
        this.probe = probe;
    }

    @Transactional(readOnly = true)
    public List<SourceResponse> list() {
        return sources.findAllByOrderByNameAsc().stream().map(SourceResponse::from).toList();
    }

    /**
     * Le flux est lu avant l'enregistrement, hors transaction : pas de connexion en base tenue pendant l'appel réseau.
     */
    public SourceResponse create(CreateSourceRequest request) {
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
            return SourceResponse.from(sources.saveAndFlush(source));
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("A source already uses this feed URL");
        }
    }

    @Transactional
    public SourceResponse setActive(int id, boolean active) {
        Source source = get(id);
        source.setActive(active);
        return SourceResponse.from(source);
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

    private Source get(int id) {
        return sources.findById(id).orElseThrow(() -> ApiException.notFound("Source not found"));
    }
}
