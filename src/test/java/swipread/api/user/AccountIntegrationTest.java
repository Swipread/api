package swipread.api.user;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import swipread.api.IntegrationTest;
import swipread.api.feed.TestArticles;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AccountIntegrationTest extends IntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StringRedisTemplate redis;

    @Test
    void displayNameCanBeChanged() throws Exception {
        RegisteredUser user = registerUser();

        mvc.perform(patch("/v1/me").header("Authorization", user.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\": \"  Nouveau nom  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Nouveau nom"))
                .andExpect(jsonPath("$.role").value("READER"));
        mvc.perform(get("/v1/me").header("Authorization", user.bearer()))
                .andExpect(jsonPath("$.displayName").value("Nouveau nom"));

        mvc.perform(patch("/v1/me").header("Authorization", user.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\": \" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("displayName"));
        mvc.perform(patch("/v1/me").header("Authorization", user.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\": \"" + "x".repeat(51) + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deletingTheAccountErasesPersonalDataAndNotifiesReco() throws Exception {
        TestArticles.seed(jdbc, 200);
        String email = "leaving-" + UUID.randomUUID() + "@example.org";
        String token = register(email);
        String bearer = "Bearer " + token;
        String userId = jdbc.queryForObject("SELECT id FROM app.users WHERE email = ?", String.class, email);

        mvc.perform(put("/v1/me/topics").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topicIds\": [1, 2, 3]}"))
                .andExpect(status().isOk());
        mvc.perform(post("/v1/events").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sessionId": "s_1", "events": [
                                  {"eventId": "e_%s", "type": "open", "articleId": "a_it0001", "occurredAt": "%s"}]}
                                """.formatted(UUID.randomUUID(), Instant.now().minusSeconds(5))))
                .andExpect(status().isAccepted());
        mvc.perform(get("/v1/feed").header("Authorization", bearer)).andExpect(status().isOk());
        // Attendre la fin du remplissage pour qu'il ne recrée pas le stock après la suppression
        await().atMost(Duration.ofSeconds(5)).until(() -> redis.opsForList().size("feed:" + userId) == 50);

        mvc.perform(delete("/v1/me").header("Authorization", bearer))
                .andExpect(status().isNoContent());

        assertThat(count("SELECT count(*) FROM app.users WHERE id = ?", userId)).isZero();
        assertThat(count("SELECT count(*) FROM app.user_topics WHERE user_id = ?", userId)).isZero();
        assertThat(count("SELECT count(*) FROM app.user_articles WHERE user_id = ?", userId)).isZero();
        assertThat(count("SELECT count(*) FROM app.events WHERE user_id = ?", userId)).isZero();
        assertThat(redis.keys("*" + userId + "*")).isEmpty();

        // Publication par l'outbox, en différé
        await().atMost(Duration.ofSeconds(5)).until(() -> !streamMessagesFor(userId).isEmpty()
                && streamMessagesFor(userId).getLast().get("type").asString().equals("user_deleted"));
        List<JsonNode> messages = streamMessagesFor(userId);
        JsonNode last = messages.getLast();
        assertThat(last.get("type").asString()).isEqualTo("user_deleted");
        assertThat(last.get("version").asInt()).isEqualTo(1);
        assertThat(last.get("eventId").asString()).startsWith("e_");
        assertThat(last.has("articleId")).isFalse();

        // Le token du compte supprimé est refusé, et l'email peut resservir
        mvc.perform(get("/v1/me").header("Authorization", bearer)).andExpect(status().isUnauthorized());
        mvc.perform(post("/v1/events").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\": \"s_1\", \"events\": []}"))
                .andExpect(status().isUnauthorized());
        register(email);
    }

    private String register(String email) throws Exception {
        String body = mvc.perform(post("/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "password123", "displayName": "Leaving"}
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("accessToken").asString();
    }

    private int count(String sql, String userId) {
        return jdbc.queryForObject(sql, Integer.class, userId);
    }

    private List<JsonNode> streamMessagesFor(String userId) {
        return redis.opsForStream().range("events", Range.unbounded()).stream()
                .map(record -> json.readTree(record.getValue().get("data").toString()))
                .filter(node -> node.get("userId").asString().equals(userId))
                .toList();
    }
}
