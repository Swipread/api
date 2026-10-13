package swipread.api.feed;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.jdbc.core.JdbcTemplate;
import swipread.api.IntegrationTest;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.HashSet;
import java.util.stream.IntStream;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FeedIntegrationTest extends IntegrationTest {

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seedArticles() {
        TestArticles.seed(jdbc, 200);
    }

    @Test
    void firstPageComesFromFallbackThenStockIsFilledByReco() throws Exception {
        RegisteredUser user = registerUser();

        JsonNode first = feed(user);
        assertThat(first.get("items")).hasSize(10);
        assertThat(first.get("items").get(0).get("reason").asString()).isEqualTo("fallback");
        assertThat(first.get("items").get(0).get("position").asInt()).isZero();
        assertThat(first.get("items").get(0).get("topics").isArray()).isTrue();
        assertThat(first.get("items").get(0).get("liked").asBoolean()).isFalse();
        assertThat(first.get("items").get(0).get("saved").asBoolean()).isFalse();

        String feedKey = "feed:" + user.userId();
        await().atMost(Duration.ofSeconds(5)).until(() -> redis.opsForList().size(feedKey) == 50);
        assertThat(redis.opsForValue().get(feedKey + ":req")).startsWith("r_");
        assertThat(redis.getExpire(feedKey)).isPositive();
        assertThat(redis.opsForHash().size(feedKey + ":meta")).isEqualTo(50);

        JsonNode second = feed(user);
        assertThat(second.get("items")).hasSize(10);
        JsonNode item = second.get("items").get(0);
        assertThat(item.get("reason").asString()).isEqualTo("explore");
        assertThat(item.get("requestId").asString()).startsWith("r_");
        assertThat(item.get("title").asString()).isNotBlank();
        assertThat(redis.opsForList().size(feedKey)).isEqualTo(40);
        assertThat(redis.opsForSet().size("seen:" + user.userId())).isEqualTo(20);

        Set<String> served = new HashSet<>(articleIds(first));
        assertThat(articleIds(second)).noneMatch(served::contains);
    }

    @Test
    void refillsInBackgroundWhenStockDropsBelowThreshold() throws Exception {
        RegisteredUser user = registerUser();
        String feedKey = "feed:" + user.userId();
        feed(user);
        await().atMost(Duration.ofSeconds(5)).until(() -> redis.opsForList().size(feedKey) == 50);

        for (int i = 0; i < 4; i++) {
            feed(user);
        }
        // 50 - 40 = 10 < 20 : remplissage jusqu'à 50
        await().atMost(Duration.ofSeconds(5)).until(() -> redis.opsForList().size(feedKey) == 50);
        List<String> stock = redis.opsForList().range(feedKey, 0, -1);
        assertThat(new HashSet<>(stock)).hasSize(50);
        assertThat(stock).noneMatch(redis.opsForSet().members("seen:" + user.userId())::contains);
    }

    @Test
    void changingTopicsEmptiesTheStockButKeepsSeenArticles() throws Exception {
        RegisteredUser user = registerUser();
        putTopics(user, "[1, 2, 3]").andExpect(status().isOk());
        String feedKey = "feed:" + user.userId();
        JsonNode first = feed(user);
        await().atMost(Duration.ofSeconds(5)).until(() -> redis.opsForList().size(feedKey) == 50);

        // Repli des nouveaux thèmes : seul le premier a une liste fallback:{slug}
        List<String> newTopicSlugs = jdbc.queryForList(
                "SELECT slug FROM app.topics WHERE id IN (4, 5, 6) ORDER BY id", String.class);
        List<String> newTopicArticles = IntStream.rangeClosed(101, 120).mapToObj(n -> "a_it0" + n).toList();
        newTopicSlugs.forEach(slug -> redis.delete("fallback:" + slug));
        redis.opsForList().rightPushAll("fallback:" + newTopicSlugs.getFirst(), newTopicArticles);
        try {
            putTopics(user, "[4, 5, 6]").andExpect(status().isOk());

            assertThat(redis.hasKey(feedKey)).isFalse();
            assertThat(redis.hasKey(feedKey + ":meta")).isFalse();
            assertThat(redis.hasKey(feedKey + ":req")).isFalse();
            assertThat(redis.opsForSet().size("seen:" + user.userId())).isEqualTo(10);

            JsonNode next = feed(user);
            assertThat(next.get("items")).hasSize(10);
            assertThat(next.get("items").valueStream().map(item -> item.get("reason").asString()))
                    .containsOnly("fallback");
            assertThat(articleIds(next)).allMatch(newTopicArticles::contains);
        } finally {
            redis.delete("fallback:" + newTopicSlugs.getFirst());
        }
    }

    @Test
    void rejectedTopicsChangeKeepsTheStock() throws Exception {
        RegisteredUser user = registerUser();
        putTopics(user, "[1, 2, 3]").andExpect(status().isOk());
        String feedKey = "feed:" + user.userId();
        feed(user);
        await().atMost(Duration.ofSeconds(5)).until(() -> redis.opsForList().size(feedKey) == 50);

        putTopics(user, "[1, 2, 9999]").andExpect(status().isBadRequest());

        assertThat(redis.opsForList().size(feedKey)).isEqualTo(50);
    }

    private ResultActions putTopics(RegisteredUser user, String ids) throws Exception {
        return mvc.perform(put("/v1/me/topics").header("Authorization", user.bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"topicIds\": " + ids + "}"));
    }

    private JsonNode feed(RegisteredUser user) throws Exception {
        String body = mvc.perform(get("/v1/feed").header("Authorization", user.bearer()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private static List<String> articleIds(JsonNode page) {
        return page.get("items").valueStream().map(item -> item.get("articleId").asString()).toList();
    }
}
