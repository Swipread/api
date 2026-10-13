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

class SavedArticlesIntegrationTest extends IntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seedArticles() {
        TestArticles.seed(jdbc, 200);
    }

    @Test
    void savedArticlesArePaginatedNewestFirstAndExcludeHidden() throws Exception {
        RegisteredUser user = registerUser();
        Instant base = Instant.now().minusSeconds(60);
        StringBuilder events = new StringBuilder();
        for (int i = 1; i <= 5; i++) {
            events.append(event("save", "a_it000" + i, base.plusSeconds(i))).append(',');
        }
        events.append(event("hide", "a_it0005", base.plusSeconds(10)));
        mvc.perform(post("/v1/events").header("Authorization", user.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\": \"s_1\", \"events\": [" + events + "]}"))
                .andExpect(status().isAccepted());

        JsonNode first = saved(user, "?limit=2");
        assertThat(ids(first)).containsExactly("a_it0004", "a_it0003");
        assertThat(first.get("items").get(0).get("article").get("title").asString()).isEqualTo("Article 4");
        assertThat(first.get("items").get(0).get("savedAt").asString()).isEqualTo(base.plusSeconds(4).toString());

        JsonNode second = saved(user, "?limit=2&cursor=" + first.get("nextCursor").asString());
        assertThat(ids(second)).containsExactly("a_it0002", "a_it0001");
        assertThat(second.has("nextCursor")).isFalse();

        mvc.perform(get("/v1/me/saved?cursor=garbage").header("Authorization", user.bearer()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/v1/me/saved?limit=500").header("Authorization", user.bearer()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void savedArticlesCarryTopicsAndLikedState() throws Exception {
        // Thèmes du seed : 1 = tech, 2 = science. La confiance la plus haute passe en premier.
        jdbc.update("""
                INSERT INTO app.article_topics (article_id, topic_id, confidence)
                VALUES ('a_it0150', 1, 0.3), ('a_it0150', 2, 0.9)
                ON CONFLICT DO NOTHING
                """);
        RegisteredUser user = registerUser();
        Instant base = Instant.now().minusSeconds(60);
        String events = String.join(",",
                event("save", "a_it0150", base.plusSeconds(1)),
                event("like", "a_it0150", base.plusSeconds(2)),
                event("save", "a_it0151", base.plusSeconds(3)));
        mvc.perform(post("/v1/events").header("Authorization", user.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\": \"s_1\", \"events\": [" + events + "]}"))
                .andExpect(status().isAccepted());

        JsonNode page = saved(user, "");
        assertThat(ids(page)).containsExactly("a_it0151", "a_it0150");
        JsonNode items = page.get("items");
        assertThat(items.get(0).get("liked").asBoolean()).isFalse();
        assertThat(items.get(0).get("article").get("topics")).isEmpty();
        assertThat(items.get(1).get("liked").asBoolean()).isTrue();
        assertThat(items.get(1).get("article").get("topics").valueStream().map(t -> t.get("slug").asString()))
                .containsExactly("science", "tech");
    }

    @Test
    void unsaveRemovesArticleFromSavedAndSaveAgainRestoresIt() throws Exception {
        RegisteredUser user = registerUser();
        Instant base = Instant.now().minusSeconds(60);
        send(user, event("save", "a_it0160", base.plusSeconds(1)), event("unsave", "a_it0160", base.plusSeconds(2)));
        assertThat(ids(saved(user, ""))).isEmpty();

        send(user, event("save", "a_it0160", base.plusSeconds(3)));
        JsonNode page = saved(user, "");
        assertThat(ids(page)).containsExactly("a_it0160");
        assertThat(page.get("items").get(0).get("savedAt").asString()).isEqualTo(base.plusSeconds(3).toString());
    }

    private void send(RegisteredUser user, String... events) throws Exception {
        mvc.perform(post("/v1/events").header("Authorization", user.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\": \"s_1\", \"events\": [" + String.join(",", events) + "]}"))
                .andExpect(status().isAccepted());
    }

    private JsonNode saved(RegisteredUser user, String query) throws Exception {
        return json.readTree(mvc.perform(get("/v1/me/saved" + query).header("Authorization", user.bearer()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private static String event(String type, String articleId, Instant at) {
        return """
                {"eventId": "e_%s", "type": "%s", "articleId": "%s", "occurredAt": "%s"}
                """.formatted(UUID.randomUUID(), type, articleId, at);
    }

    private static List<String> ids(JsonNode page) {
        return page.get("items").valueStream().map(item -> item.get("article").get("id").asString()).toList();
    }
}
