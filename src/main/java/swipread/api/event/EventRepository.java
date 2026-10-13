package swipread.api.event;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import swipread.api.event.dto.EventRequest;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Repository
class EventRepository {

    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    EventRepository(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * Insère les événements et renvoie ceux qui n'existaient pas déjà (dédoublonnage sur la clé primaire).
     */
    List<EventRequest> insertNew(String userId, String sessionId, List<EventRequest> events, Instant receivedAt) {
        int[][] counts = jdbc.batchUpdate("""
                INSERT INTO app.events (event_id, user_id, session_id, type, article_id, request_id, position,
                                        payload, occurred_at, received_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                ON CONFLICT DO NOTHING
                """, events, events.size(), (ps, event) -> {
            ps.setString(1, event.eventId());
            ps.setString(2, userId);
            ps.setString(3, sessionId);
            ps.setString(4, event.type());
            ps.setString(5, event.articleId());
            ps.setString(6, event.requestId());
            ps.setObject(7, event.position());
            ps.setString(8, json.writeValueAsString(event.payloadOrEmpty()));
            ps.setTimestamp(9, Timestamp.from(event.occurredAt()));
            ps.setTimestamp(10, Timestamp.from(receivedAt));
        });
        List<EventRequest> inserted = new ArrayList<>();
        int index = 0;
        for (int[] batch : counts) {
            for (int count : batch) {
                if (count > 0) {
                    inserted.add(events.get(index));
                }
                index++;
            }
        }
        return inserted;
    }

    int deleteByUser(String userId) {
        return jdbc.update("DELETE FROM app.events WHERE user_id = ?", userId);
    }

    void applyToUserArticle(String userId, String articleId, UserArticleChange change) {
        jdbc.update("""
                INSERT INTO app.user_articles AS ua (user_id, article_id, first_seen_at, total_dwell_ms, max_scroll,
                                                     liked_at, saved_at, hidden_at, opened_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (user_id, article_id) DO UPDATE SET
                  first_seen_at  = LEAST(ua.first_seen_at, EXCLUDED.first_seen_at),
                  total_dwell_ms = LEAST(ua.total_dwell_ms::bigint + EXCLUDED.total_dwell_ms, 2147483647)::int,
                  max_scroll     = GREATEST(ua.max_scroll, EXCLUDED.max_scroll),
                  liked_at       = CASE WHEN ? THEN EXCLUDED.liked_at ELSE ua.liked_at END,
                  saved_at       = CASE WHEN ? THEN EXCLUDED.saved_at ELSE ua.saved_at END,
                  hidden_at      = COALESCE(EXCLUDED.hidden_at, ua.hidden_at),
                  opened_at      = GREATEST(ua.opened_at, EXCLUDED.opened_at)
                """,
                userId, articleId, timestamp(change.seenAt()), change.dwellMs(),
                change.maxScroll() == null ? null : change.maxScroll().floatValue(),
                timestamp(change.likedAt()), timestamp(change.savedAt()), timestamp(change.hiddenAt()),
                timestamp(change.openedAt()),
                change.updateLiked(), change.updateSaved());
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
