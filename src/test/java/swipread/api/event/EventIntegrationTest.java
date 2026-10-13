package swipread.api.event;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;
import swipread.api.IntegrationTest;
import swipread.api.feed.TestArticles;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class EventIntegrationTest extends IntegrationTest {

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seedArticles() {
        TestArticles.seed(jdbc, 200);
    }

    @Test
    void batchIsStoredAggregatedPublishedAndDeduplicated() throws Exception {
        RegisteredUser user = registerUser();
        String prefix = "e_" + UUID.randomUUID();
        Instant at = Instant.now().truncatedTo(ChronoUnit.SECONDS).minusSeconds(10);
        String batch = """
                {"sessionId": "s_8f3a", "events": [
                  {"eventId": "%1$s-1", "type": "view", "articleId": "a_it0003", "requestId": "r_7c21", "position": 3,
                   "occurredAt": "%2$s", "payload": {"dwellMs": 4200}},
                  {"eventId": "%1$s-2", "type": "save", "articleId": "a_it0003", "requestId": "r_7c21", "position": 3,
                   "occurredAt": "%3$s"}
                ]}
                """.formatted(prefix, at, at.plusSeconds(1));

        postEvents(user, batch).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accepted").value(2))
                .andExpect(jsonPath("$.duplicates").value(0));
        postEvents(user, batch).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accepted").value(0))
                .andExpect(jsonPath("$.duplicates").value(2));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM app.events WHERE user_id = ?", Long.class, user.userId()))
                .isEqualTo(2);
        Map<String, Object> aggregate = jdbc.queryForMap(
                "SELECT total_dwell_ms, saved_at FROM app.user_articles WHERE user_id = ? AND article_id = 'a_it0003'",
                user.userId());
        assertThat(aggregate.get("total_dwell_ms")).isEqualTo(4200);
        assertThat(aggregate.get("saved_at")).isNotNull();

        // Publication par l'outbox, en différé
        await().atMost(Duration.ofSeconds(5)).until(() -> streamMessagesFor(user.userId()).size() == 2);
        List<JsonNode> messages = streamMessagesFor(user.userId());
        assertThat(messages).hasSize(2);
        JsonNode view = messages.getFirst();
        assertThat(view.get("version").asInt()).isEqualTo(1);
        assertThat(view.get("eventId").asString()).isEqualTo(prefix + "-1");
        assertThat(view.get("sessionId").asString()).isEqualTo("s_8f3a");
        assertThat(view.get("occurredAt").asString()).isEqualTo(at.toString());
        assertThat(view.get("receivedAt").asString()).isNotBlank();
        assertThat(view.get("payload").get("dwellMs").asInt()).isEqualTo(4200);
        assertThat(view.get("payload").get("articleWordCount").asInt()).isEqualTo(300);
    }

    @Test
    void strongEventTrimsStockToTenAndRefills() throws Exception {
        RegisteredUser user = registerUser();
        String feedKey = "feed:" + user.userId();
        mvc.perform(get("/v1/feed").header("Authorization", user.bearer())).andExpect(status().isOk());
        await().atMost(Duration.ofSeconds(5)).until(() -> redis.opsForList().size(feedKey) == 50);
        List<String> firstTen = redis.opsForList().range(feedKey, 0, 9);

        // Bloque le remplissage pour observer l'état juste après l'invalidation
        redis.opsForValue().set(feedKey + ":lock", "test", Duration.ofSeconds(5));
        postEvents(user, """
                {"sessionId": "s_1", "events": [{"eventId": "e_%s", "type": "like", "articleId": "a_it0001",
                  "occurredAt": "%s"}]}
                """.formatted(UUID.randomUUID(), Instant.now().minusSeconds(1))).andExpect(status().isAccepted());
        assertThat(redis.opsForList().range(feedKey, 0, -1)).isEqualTo(firstTen);
        assertThat(redis.opsForHash().size(feedKey + ":meta")).isEqualTo(10);

        redis.delete(feedKey + ":lock");
        postEvents(user, """
                {"sessionId": "s_1", "events": [{"eventId": "e_%s", "type": "share", "articleId": "a_it0001",
                  "occurredAt": "%s", "payload": {"channel": "copy"}}]}
                """.formatted(UUID.randomUUID(), Instant.now().minusSeconds(1))).andExpect(status().isAccepted());
        await().atMost(Duration.ofSeconds(5)).until(() -> redis.opsForList().size(feedKey) == 50);
        assertThat(redis.opsForList().range(feedKey, 0, 9)).isEqualTo(firstTen);
    }

    @Test
    void readWithoutScrollDepthIsAccepted() throws Exception {
        RegisteredUser user = registerUser();
        postEvents(user, """
                {"sessionId": "s_1", "events": [{"eventId": "e_%s", "type": "read", "articleId": "a_it0001",
                  "occurredAt": "%s", "payload": {"readMs": 45000}}]}
                """.formatted(UUID.randomUUID(), Instant.now().minusSeconds(1)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accepted").value(1));
    }

    @Test
    void invalidPayloadIsRejected() throws Exception {
        RegisteredUser user = registerUser();
        postEvents(user, """
                {"sessionId": "s_1", "events": [{"eventId": "e_bad", "type": "read", "articleId": "a_it0001",
                  "occurredAt": "2026-10-12T10:41:12Z", "payload": {"readMs": 10, "scrollDepth": 3}}]}
                """).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Event e_bad (read): scrollDepth must be between 0 and 1"));
        postEvents(user, """
                {"sessionId": "s_1", "events": [{"eventId": "e_bad", "type": "teleport", "articleId": "a_it0001",
                  "occurredAt": "2026-10-12T10:41:12Z"}]}
                """).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("events[0].type"));
    }

    @Test
    void partitionsForCurrentAndNextMonthExist() {
        YearMonth next = YearMonth.now(ZoneOffset.UTC).plusMonths(1);
        String partition = "events_%d_%02d".formatted(next.getYear(), next.getMonthValue());
        assertThat(jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class, "app." + partition)).isTrue();
    }

    private ResultActions postEvents(RegisteredUser user, String body) throws Exception {
        return mvc.perform(post("/v1/events").header("Authorization", user.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private List<JsonNode> streamMessagesFor(String userId) {
        List<MapRecord<String, Object, Object>> records = redis.opsForStream().range("events",
                Range.unbounded());
        return records.stream()
                .map(record -> json.readTree(record.getValue().get("data").toString()))
                .filter(node -> node.get("userId").asString().equals(userId))
                .toList();
    }
}
