package swipread.api.feed;

import org.springframework.jdbc.core.JdbcTemplate;

public final class TestArticles {

    private TestArticles() {
    }

    /**
     * Insère count articles a_it0001… (idempotent).
     */
    public static void seed(JdbcTemplate jdbc, int count) {
        jdbc.update("""
                INSERT INTO app.sources (name, site_url, feed_url, language)
                VALUES ('Test source', 'https://test.example.org', 'https://test.example.org/rss', 'fr')
                ON CONFLICT (feed_url) DO NOTHING
                """);
        jdbc.update("""
                INSERT INTO app.articles (id, source_id, url, title, summary, language, word_count, published_at)
                SELECT 'a_it' || lpad(n::text, 4, '0'),
                       (SELECT id FROM app.sources WHERE feed_url = 'https://test.example.org/rss'),
                       'https://test.example.org/articles/' || n, 'Article ' || n, 'Summary ' || n, 'fr',
                       100 * n, now() - (n || ' minutes')::interval
                FROM generate_series(1, ?) AS n
                ON CONFLICT (id) DO NOTHING
                """, count);
    }
}
