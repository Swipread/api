package swipread.api.event;

import swipread.api.event.dto.EventRequest;

import java.time.Instant;
import java.util.Map;

final class EventFixtures {

    static final Instant NOW = Instant.parse("2026-10-12T10:41:15Z");

    private EventFixtures() {
    }

    static EventRequest event(String id, String type, Map<String, Object> payload) {
        return event(id, type, "a_1", NOW.minusSeconds(3), payload);
    }

    static EventRequest event(String id, String type, String articleId, Instant occurredAt, Map<String, Object> payload) {
        return new EventRequest(id, type, articleId, "r_1", 3, occurredAt, payload);
    }
}
