package swipread.api.topic.dto;

import swipread.api.topic.Topic;

public record TopicResponse(int id, String slug, String label) {

    public static TopicResponse from(Topic topic) {
        return new TopicResponse(topic.getId(), topic.getSlug(), topic.getLabel());
    }
}
