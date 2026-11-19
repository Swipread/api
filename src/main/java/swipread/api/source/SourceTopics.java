package swipread.api.source;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import swipread.api.topic.dto.TopicResponse;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Thèmes de chaque source (app.source_topics, V8), dans l'ordre choisi par l'admin : ses articles en héritent, le
 * premier étant leur thème principal. En SQL plutôt qu'en collection JPA : réordonner les thèmes réécrirait les
 * lignes une à une et violerait au passage l'unicité de (source_id, position).
 */
@Repository
public class SourceTopics {

    public static final int MAX = 3;

    private final JdbcTemplate jdbc;

    SourceTopics(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Thèmes de toutes les sources, dans l'ordre de chacune ; une source sans thème est absente. */
    public Map<Integer, List<TopicResponse>> all() {
        Map<Integer, List<TopicResponse>> bySource = new HashMap<>();
        jdbc.query("""
                        SELECT st.source_id, t.id, t.slug, t.label
                        FROM app.source_topics st JOIN app.topics t ON t.id = st.topic_id
                        ORDER BY st.source_id, st.position
                        """,
                rs -> {
                    bySource.computeIfAbsent(rs.getInt("source_id"), id -> new ArrayList<>())
                            .add(new TopicResponse(rs.getInt("id"), rs.getString("slug"), rs.getString("label")));
                });
        return bySource;
    }

    /** Thèmes d'une source, dans son ordre ; vide si elle n'en a pas. */
    public List<TopicResponse> of(int sourceId) {
        return jdbc.query("""
                        SELECT t.id, t.slug, t.label
                        FROM app.source_topics st JOIN app.topics t ON t.id = st.topic_id
                        WHERE st.source_id = ?
                        ORDER BY st.position
                        """,
                (rs, row) -> new TopicResponse(rs.getInt("id"), rs.getString("slug"), rs.getString("label")),
                sourceId);
    }

    /** Remplace les thèmes de la source par ceux-ci, dans cet ordre. À appeler dans une transaction. */
    void replace(int sourceId, List<Integer> topicIds) {
        jdbc.update("DELETE FROM app.source_topics WHERE source_id = ?", sourceId);
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < topicIds.size(); i++) {
            rows.add(new Object[]{sourceId, topicIds.get(i), i + 1});
        }
        jdbc.batchUpdate("INSERT INTO app.source_topics (source_id, topic_id, position) VALUES (?, ?, ?)", rows);
    }
}
