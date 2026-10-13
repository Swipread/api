package swipread.api.topic;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "user_topics", schema = "app")
public class UserTopic {

    @EmbeddedId
    private Id id;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected UserTopic() {
    }

    public UserTopic(String userId, Integer topicId, Instant createdAt) {
        this.id = new Id(userId, topicId);
        this.createdAt = createdAt;
    }

    public Id getId() {
        return id;
    }

    @Embeddable
    public record Id(@Column(name = "user_id") String userId, @Column(name = "topic_id") Integer topicId) {
    }
}
