package swipread.api.event;

import java.time.Instant;
import java.util.Map;

/**
 * Message publié sur le stream Redis events, au format du contrat (version 1).
 */
record EventMessage(int version, String eventId, String userId, String sessionId, String type, String articleId,
                    String requestId, Integer position, Instant occurredAt, Instant receivedAt,
                    Map<String, Object> payload) {

    static final int VERSION = 1;
    static final String USER_DELETED = "user_deleted";
}
