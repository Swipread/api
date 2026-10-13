package swipread.api.topic;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import swipread.api.common.ApiException;
import swipread.api.topic.dto.TopicResponse;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class TopicService {

    private final TopicRepository topics;
    private final UserTopicRepository userTopics;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    public TopicService(TopicRepository topics, UserTopicRepository userTopics, Clock clock,
                        ApplicationEventPublisher events) {
        this.topics = topics;
        this.userTopics = userTopics;
        this.clock = clock;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public List<TopicResponse> listAll() {
        return topics.findAllByOrderByLabelAsc().stream().map(TopicResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<TopicResponse> listForUser(String userId) {
        return topics.findByUserId(userId).stream().map(TopicResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<String> slugsForUser(String userId) {
        return topics.findByUserId(userId).stream().map(Topic::getSlug).toList();
    }

    /**
     * Remplace l'ensemble des thèmes de l'utilisateur. Le stock du feed, calculé sur les anciens thèmes, est vidé
     * après le commit (US4) via {@link UserTopicsChanged}.
     */
    @Transactional
    public List<TopicResponse> replaceForUser(String userId, List<Integer> topicIds) {
        Set<Integer> requested = new HashSet<>(topicIds);
        List<Topic> found = topics.findAllById(requested);
        if (found.size() != requested.size()) {
            Set<Integer> unknown = new HashSet<>(requested);
            found.forEach(topic -> unknown.remove(topic.getId()));
            throw ApiException.badRequest("Unknown topic ids: " + unknown);
        }
        userTopics.deleteByUserId(userId);
        userTopics.flush();
        Instant now = clock.instant();
        userTopics.saveAll(requested.stream().map(topicId -> new UserTopic(userId, topicId, now)).toList());
        events.publishEvent(new UserTopicsChanged(userId));
        return listForUser(userId);
    }
}
