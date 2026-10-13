package swipread.api.user;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * État « aimé » / « sauvegardé » d'articles pour un utilisateur, lu dans app.user_articles.
 */
@Repository
public class UserArticleStates {

    public record State(boolean liked, boolean saved) {
        public static final State NONE = new State(false, false);
    }

    private final JdbcTemplate jdbc;

    public UserArticleStates(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Les articles sans ligne dans app.user_articles sont absents de la map : utiliser {@link State#NONE}.
     */
    public Map<String, State> find(String userId, Collection<String> articleIds) {
        Map<String, State> states = new HashMap<>();
        if (articleIds.isEmpty()) {
            return states;
        }
        jdbc.query("""
                        SELECT article_id, liked_at IS NOT NULL AS liked, saved_at IS NOT NULL AS saved
                        FROM app.user_articles
                        WHERE user_id = ? AND article_id = ANY (?)
                        """,
                rs -> {
                    states.put(rs.getString("article_id"), new State(rs.getBoolean("liked"), rs.getBoolean("saved")));
                },
                userId, articleIds.toArray(String[]::new));
        return states;
    }
}
