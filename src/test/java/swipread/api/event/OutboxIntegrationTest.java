package swipread.api.event;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import swipread.api.IntegrationTest;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class OutboxIntegrationTest extends IntegrationTest {

    @Autowired
    private EventOutbox outbox;

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StringRedisTemplate redis;

    @Test
    void committedMessagesArePublishedInOrderThenRemoved() {
        String first = "e_" + UUID.randomUUID();
        String second = "e_" + UUID.randomUUID();
        transaction.executeWithoutResult(status -> {
            outbox.enqueue(message(first));
            outbox.enqueue(message(second));
        });

        await().atMost(Duration.ofSeconds(5)).until(() -> streamEventIds().containsAll(List.of(first, second)));
        List<String> published = streamEventIds();
        assertThat(published.indexOf(first)).isLessThan(published.indexOf(second));
        assertThat(outboxRows(first) + outboxRows(second)).isZero();
    }

    @Test
    void rolledBackMessagesAreNeverPublished() {
        String eventId = "e_" + UUID.randomUUID();
        transaction.executeWithoutResult(status -> {
            outbox.enqueue(message(eventId));
            status.setRollbackOnly();
        });

        assertThat(outboxRows(eventId)).isZero();
        relay.relayBatch();
        assertThat(streamEventIds()).doesNotContain(eventId);
    }

    @Test
    void enqueueRequiresATransaction() {
        assertThatThrownBy(() -> outbox.enqueue(message("e_" + UUID.randomUUID())))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    private static EventMessage message(String eventId) {
        Instant now = Instant.now();
        return new EventMessage(EventMessage.VERSION, eventId, "u_outbox", "s_1", "impression", "a_it0001", null, 0,
                now, now, Map.of());
    }

    private long outboxRows(String eventId) {
        return jdbc.queryForObject("SELECT count(*) FROM app.event_outbox WHERE message LIKE ?", Long.class,
                "%" + eventId + "%");
    }

    private List<String> streamEventIds() {
        return redis.opsForStream().range("events", Range.unbounded()).stream()
                .map(record -> record.getValue().get("data").toString())
                .filter(data -> data.contains("u_outbox"))
                .map(data -> json.readTree(data).get("eventId").asString())
                .toList();
    }
}
