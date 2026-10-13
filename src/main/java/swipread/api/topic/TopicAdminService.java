package swipread.api.topic;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import swipread.api.common.ApiException;
import swipread.api.topic.dto.AdminTopicResponse;

import java.util.List;

@Service
public class TopicAdminService {

    private final TopicRepository topics;

    public TopicAdminService(TopicRepository topics) {
        this.topics = topics;
    }

    @Transactional(readOnly = true)
    public List<AdminTopicResponse> list() {
        return topics.findStats(null).stream().map(TopicAdminService::toResponse).toList();
    }

    /**
     * Le slug est tiré du libellé à la création, puis ne change plus : la reco l'utilise dans les clés fallback:{slug}.
     */
    @Transactional
    public AdminTopicResponse create(String label) {
        String clean = label.strip();
        String slug = Slugs.of(clean);
        if (slug.isEmpty()) {
            throw ApiException.badRequest("Label must contain at least one letter or digit");
        }
        if (topics.existsBySlug(slug)) {
            throw ApiException.conflict("Topic already exists: " + slug);
        }
        Topic topic;
        try {
            topic = topics.saveAndFlush(new Topic(slug, clean));
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("Topic already exists: " + slug);
        }
        return new AdminTopicResponse(topic.getId(), topic.getSlug(), topic.getLabel(), 0, 0);
    }

    @Transactional
    public AdminTopicResponse relabel(int id, String label) {
        Topic topic = topics.findById(id).orElseThrow(() -> ApiException.notFound("Topic not found"));
        topic.relabel(label.strip());
        topics.flush();
        return toResponse(topics.findStats(id).getFirst());
    }

    private static AdminTopicResponse toResponse(TopicRepository.TopicStats stats) {
        return new AdminTopicResponse(stats.getId(), stats.getSlug(), stats.getLabel(), stats.getArticleCount(),
                stats.getReaderCount());
    }
}
