package swipread.api.feed;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import swipread.api.IntegrationTest;

import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class FallbackFeedProviderIntegrationTest extends IntegrationTest {

    @Autowired
    private FallbackFeedProvider fallback;

    @Autowired
    private FeedStore store;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seedArticles() {
        TestArticles.seed(jdbc, 200);
    }

    @Test
    void seenAmongReturnsOnlyServedCandidates() throws Exception {
        String userId = registerUser().userId();
        store.markSeen(userId, List.of("a_it0001", "a_it0002"));

        assertThat(store.seenAmong(userId, List.of("a_it0001", "a_it0003", "a_it0002"))).containsOnly("a_it0001", "a_it0002");
        assertThat(store.seenAmong(userId, List.of())).isEmpty();
    }

    @Test
    void recentArticlesSkipSeenOnesAcrossSeveralBatches() throws Exception {
        String userId = registerUser().userId();
        // Plus d'articles vus qu'un lot : il faut lire le lot suivant
        List<String> newest = jdbc.queryForList(
                "SELECT id FROM app.articles ORDER BY published_at DESC, id DESC LIMIT 161", String.class);
        store.markSeen(userId, newest.subList(0, 150));

        List<String> ids = fallback.articleIds(userId, 10, List.of(newest.get(150)));

        assertThat(ids).containsExactlyElementsOf(newest.subList(151, 161));
    }

    @Test
    void topicListsSkipSeenAndExcludedArticles() throws Exception {
        String userId = registerUser().userId();
        String slug = jdbc.queryForObject("SELECT slug FROM app.topics WHERE id = 7", String.class);
        jdbc.update("INSERT INTO app.user_topics (user_id, topic_id) VALUES (?, 7)", userId);
        List<String> list = IntStream.rangeClosed(190, 195).mapToObj(n -> "a_it0" + n).toList();
        redis.delete("fallback:" + slug);
        redis.opsForList().rightPushAll("fallback:" + slug, list);
        try {
            store.markSeen(userId, List.of("a_it0190", "a_it0191"));

            List<String> ids = fallback.articleIds(userId, 3, Set.of("a_it0192"));

            assertThat(ids).hasSize(3).containsOnly("a_it0193", "a_it0194", "a_it0195");
        } finally {
            redis.delete("fallback:" + slug);
        }
    }
}
