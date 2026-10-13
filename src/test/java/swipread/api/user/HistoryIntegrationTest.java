package swipread.api.user;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import swipread.api.IntegrationTest;
import swipread.api.feed.TestArticles;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class HistoryIntegrationTest extends IntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seedArticles() {
        TestArticles.seed(jdbc, 200);
    }

    @Test
    void historyListsOpenedArticlesByLastOpening() throws Exception {
        RegisteredUser user = registerUser();
        Instant base = Instant.now().minusSeconds(60);
        send(user,
                event("open", "a_it0101", base.plusSeconds(1), null),
                event("read", "a_it0102", base.plusSeconds(2), "{\"readMs\": 30000}"),
                event("view", "a_it0103", base.plusSeconds(3), "{\"dwellMs\": 4000}"),
                event("like", "a_it0102", base.plusSeconds(4), null),
                event("open", "a_it0101", base.plusSeconds(5), null));
        // Un événement en retard ne fait pas reculer la date de dernière ouverture
        send(user, event("open", "a_it0101", base, null));

        JsonNode first = history(user, "?limit=1");
        assertThat(ids(first)).containsExactly("a_it0101");
        assertThat(first.get("items").get(0).get("readAt").asString()).isEqualTo(base.plusSeconds(5).toString());
        assertThat(first.get("items").get(0).get("liked").asBoolean()).isFalse();

        JsonNode second = history(user, "?limit=1&cursor=" + first.get("nextCursor").asString());
        assertThat(ids(second)).containsExactly("a_it0102");
        assertThat(second.get("items").get(0).get("liked").asBoolean()).isTrue();
        assertThat(second.get("items").get(0).get("saved").asBoolean()).isFalse();
        assertThat(second.has("nextCursor")).isFalse();
    }

    @Test
    void hiddenArticlesLeaveHistory() throws Exception {
        RegisteredUser user = registerUser();
        Instant base = Instant.now().minusSeconds(60);
        send(user, event("open", "a_it0110", base.plusSeconds(1), null), event("open", "a_it0111", base.plusSeconds(2), null));
        send(user, event("hide", "a_it0111", base.plusSeconds(3), null));

        assertThat(ids(history(user, ""))).containsExactly("a_it0110");
    }

    @Test
    void invalidParametersAreRejected() throws Exception {
        RegisteredUser user = registerUser();
        mvc.perform(get("/v1/me/history?cursor=garbage").header("Authorization", user.bearer()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/v1/me/history?limit=0").header("Authorization", user.bearer()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/v1/me/history"))
                .andExpect(status().isUnauthorized());
    }

    private void send(RegisteredUser user, String... events) throws Exception {
        mvc.perform(post("/v1/events").header("Authorization", user.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\": \"s_1\", \"events\": [" + String.join(",", events) + "]}"))
                .andExpect(status().isAccepted());
    }

    private JsonNode history(RegisteredUser user, String query) throws Exception {
        return json.readTree(mvc.perform(get("/v1/me/history" + query).header("Authorization", user.bearer()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private static String event(String type, String articleId, Instant at, String payload) {
        return """
                {"eventId": "e_%s", "type": "%s", "articleId": "%s", "occurredAt": "%s", "payload": %s}
                """.formatted(UUID.randomUUID(), type, articleId, at, payload == null ? "{}" : payload);
    }

    private static List<String> ids(JsonNode page) {
        return page.get("items").valueStream().map(item -> item.get("article").get("id").asString()).toList();
    }
}
