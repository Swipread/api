package swipread.api.user;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import swipread.api.IntegrationTest;
import swipread.api.feed.TestArticles;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UserArticleStatesIntegrationTest extends IntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UserArticleStates states;

    @Test
    void readsLikedAndSavedFlags() throws Exception {
        TestArticles.seed(jdbc, 200);
        String userId = registerUser().userId();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                INSERT INTO app.user_articles (user_id, article_id, first_seen_at, liked_at, saved_at)
                VALUES (?, 'a_it0001', ?, ?, NULL), (?, 'a_it0002', ?, NULL, ?), (?, 'a_it0003', ?, NULL, NULL)
                """, userId, now, now, userId, now, now, userId, now);

        var found = states.find(userId, List.of("a_it0001", "a_it0002", "a_it0003", "a_it0004"));

        assertThat(found).containsEntry("a_it0001", new UserArticleStates.State(true, false))
                .containsEntry("a_it0002", new UserArticleStates.State(false, true))
                .containsEntry("a_it0003", UserArticleStates.State.NONE)
                .doesNotContainKey("a_it0004");
        assertThat(states.find(userId, List.of())).isEmpty();
    }
}
