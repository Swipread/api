package swipread.api.reco;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamInfo;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import swipread.api.IntegrationTest;
import swipread.api.feed.TestArticles;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MockEventsConsumerIntegrationTest extends IntegrationTest {

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MockEventsConsumer consumer;

    @Autowired
    private RedisConnectionFactory connectionFactory;

    @Autowired
    private JsonMapper json;

    @Test
    void publishedEventsAreReadAndAcknowledgedByTheMockGroup() throws Exception {
        TestArticles.seed(jdbc, 5);
        RegisteredUser user = registerUser();
        String eventId = "e_" + UUID.randomUUID();
        Instant at = Instant.now().truncatedTo(ChronoUnit.SECONDS).minusSeconds(5);
        mvc.perform(post("/v1/events").header("Authorization", user.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sessionId": "s_1", "events": [{"eventId": "%s", "type": "open",
                                 "articleId": "a_it0001", "occurredAt": "%s"}]}
                                """.formatted(eventId, at)))
                .andExpect(status().isAccepted());
        // Publication par l'outbox, en différé
        RecordId published = await().atMost(Duration.ofSeconds(5)).until(
                () -> redis.opsForStream().reverseRange("events", Range.unbounded()).stream()
                        .filter(record -> record.getValue().get("data").toString().contains(eventId))
                        .map(MapRecord::getId)
                        .findFirst(),
                Optional::isPresent).orElseThrow();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            StreamInfo.XInfoGroup group = redis.opsForStream().groups("events").stream()
                    .filter(g -> g.groupName().equals(MockEventsConsumer.GROUP))
                    .findFirst().orElseThrow();
            assertThat(compare(RecordId.of(group.lastDeliveredId()), published)).isGreaterThanOrEqualTo(0);
            assertThat(group.pendingCount()).isZero();
        });
    }

    @Test
    void existingGroupDoesNotPreventStartup() {
        // Le groupe existe déjà : créé au démarrage du contexte, comme après un redémarrage de l'API en dev
        assertThatCode(consumer::createGroupIfMissing).doesNotThrowAnyException();
    }

    @Test
    void stoppedConsumerLeavesTheGroup() {
        MockEventsConsumer other = new MockEventsConsumer(connectionFactory, redis, json);
        long before = consumerCount();
        other.start();
        // Le consommateur n'existe dans Redis qu'à sa première lecture (XREADGROUP), faite en arrière-plan
        await().atMost(Duration.ofSeconds(5)).until(() -> consumerCount() == before + 1);
        other.stop();
        assertThat(consumerCount()).isEqualTo(before);
    }

    private long consumerCount() {
        return redis.opsForStream().consumers("events", MockEventsConsumer.GROUP).size();
    }

    @Test
    void unreadableMessageIsOnlyLogged() {
        MapRecord<String, String, String> garbage = StreamRecords.newRecord().in("events")
                .ofMap(Map.of("data", "not json"));
        assertThatCode(() -> consumer.handle(garbage)).doesNotThrowAnyException();
    }

    private static int compare(RecordId a, RecordId b) {
        int byTime = Long.compare(a.getTimestamp(), b.getTimestamp());
        return byTime != 0 ? byTime : Long.compare(a.getSequence(), b.getSequence());
    }
}
