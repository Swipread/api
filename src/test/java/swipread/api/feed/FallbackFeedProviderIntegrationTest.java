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

    private static final String NEWEST_ACTIVE = """
            SELECT a.id FROM app.articles a JOIN app.sources s ON s.id = a.source_id AND s.active
            ORDER BY a.published_at DESC, a.id DESC LIMIT ?
            """;

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
        List<String> newest = jdbc.queryForList(NEWEST_ACTIVE, String.class, 161);
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

    @Test
    void articlesSeenOrHiddenLongAgoAreNeverServedAgain() throws Exception {
        String userId = registerUser().userId();
        String slug = jdbc.queryForObject("SELECT slug FROM app.topics WHERE id = 7", String.class);
        jdbc.update("INSERT INTO app.user_topics (user_id, topic_id) VALUES (?, 7)", userId);
        List<String> newest = jdbc.queryForList(NEWEST_ACTIVE, String.class, 8);
        // Vus il y a 40 jours : seen:{userId} a expiré, il ne reste que app.user_articles
        markSeenLongAgo(userId, newest.get(0), false);
        markSeenLongAgo(userId, newest.get(1), true);
        markSeenLongAgo(userId, "a_it0190", false);
        markSeenLongAgo(userId, "a_it0191", true);
        redis.delete("fallback:" + slug);
        redis.opsForList().rightPushAll("fallback:" + slug, List.of("a_it0190", "a_it0191", "a_it0192"));
        try {
            List<String> ids = fallback.articleIds(userId, 4, List.of());

            assertThat(ids).hasSize(4).containsOnly("a_it0192", newest.get(2), newest.get(3), newest.get(4));
        } finally {
            redis.delete("fallback:" + slug);
        }
    }

    @Test
    void articlesOfInactiveSourcesAreNeverServed() throws Exception {
        String userId = registerUser().userId();
        String slug = jdbc.queryForObject("SELECT slug FROM app.topics WHERE id = 7", String.class);
        jdbc.update("INSERT INTO app.user_topics (user_id, topic_id) VALUES (?, 7)", userId);
        jdbc.update("""
                INSERT INTO app.sources (name, site_url, feed_url, language, active)
                VALUES ('Inactive source', 'https://inactive.example.org', 'https://inactive.example.org/rss', 'fr', false)
                """);
        // Plus récents que tous les autres : sans filtre, ils seraient servis en premier
        jdbc.update("""
                INSERT INTO app.articles (id, source_id, url, title, summary, language, word_count, published_at)
                SELECT 'a_ix' || n, (SELECT id FROM app.sources WHERE feed_url = 'https://inactive.example.org/rss'),
                       'https://inactive.example.org/articles/' || n, 'Inactive ' || n, 'Summary', 'fr', 300,
                       now() + (n || ' minutes')::interval
                FROM generate_series(1, 3) AS n
                """);
        redis.delete("fallback:" + slug);
        redis.opsForList().rightPushAll("fallback:" + slug, List.of("a_ix1", "a_ix2", "a_it0190", "a_it0191"));
        try {
            List<String> newest = jdbc.queryForList(NEWEST_ACTIVE, String.class, 2);

            List<String> ids = fallback.articleIds(userId, 4, List.of());

            assertThat(ids).hasSize(4).containsOnly("a_it0190", "a_it0191", newest.get(0), newest.get(1));
        } finally {
            redis.delete("fallback:" + slug);
            jdbc.update("DELETE FROM app.articles WHERE id LIKE 'a_ix%'");
            jdbc.update("DELETE FROM app.sources WHERE feed_url = 'https://inactive.example.org/rss'");
        }
    }

    private void markSeenLongAgo(String userId, String articleId, boolean hidden) {
        jdbc.update("""
                INSERT INTO app.user_articles (user_id, article_id, first_seen_at, hidden_at)
                VALUES (?, ?, now() - interval '40 days', CASE WHEN ? THEN now() - interval '40 days' END)
                """, userId, articleId, hidden);
    }
}
