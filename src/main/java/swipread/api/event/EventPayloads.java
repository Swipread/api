package swipread.api.event;

import swipread.api.common.ApiException;
import swipread.api.event.dto.EventRequest;

import java.util.Map;
import java.util.Optional;

/**
 * Lecture et validation des champs de payload propres à chaque type d'événement.
 */
final class EventPayloads {

    static final long MAX_DURATION_MS = 86_400_000L;

    private EventPayloads() {
    }

    static void validate(EventRequest event) {
        Map<String, Object> payload = event.payloadOrEmpty();
        switch (EventType.of(event.type())) {
            case VIEW -> requireDuration(event, payload, "dwellMs");
            case READ -> {
                requireDuration(event, payload, "readMs");
                // Optionnel : le front web ne peut pas mesurer le défilement d'une page tierce
                Optional<Double> depth = number(event, payload, "scrollDepth");
                if (depth.isPresent() && (depth.get() < 0 || depth.get() > 1)) {
                    throw invalid(event, "scrollDepth must be between 0 and 1");
                }
            }
            case SHARE -> {
                if (!(payload.get("channel") instanceof String channel) || channel.isBlank()) {
                    throw invalid(event, "channel is required");
                }
            }
            case HIDE -> {
                Object reason = payload.get("reason");
                if (reason != null && !(reason instanceof String)) {
                    throw invalid(event, "reason must be a string");
                }
            }
            default -> {
            }
        }
    }

    static long dwellMs(EventRequest event) {
        return number(event, event.payloadOrEmpty(), "dwellMs").map(Number::longValue).orElse(0L);
    }

    static long readMs(EventRequest event) {
        return number(event, event.payloadOrEmpty(), "readMs").map(Number::longValue).orElse(0L);
    }

    static Optional<Double> scrollDepth(EventRequest event) {
        return number(event, event.payloadOrEmpty(), "scrollDepth").map(Number::doubleValue);
    }

    private static void requireDuration(EventRequest event, Map<String, Object> payload, String field) {
        double value = number(event, payload, field).orElseThrow(() -> invalid(event, field + " is required"));
        if (value < 0 || value > MAX_DURATION_MS) {
            throw invalid(event, field + " must be between 0 and " + MAX_DURATION_MS);
        }
    }

    private static Optional<Double> number(EventRequest event, Map<String, Object> payload, String field) {
        Object value = payload.get(field);
        if (value == null) {
            return Optional.empty();
        }
        if (value instanceof Number number) {
            return Optional.of(number.doubleValue());
        }
        throw invalid(event, field + " must be a number");
    }

    private static ApiException invalid(EventRequest event, String detail) {
        return ApiException.badRequest("Event " + event.eventId() + " (" + event.type() + "): " + detail);
    }
}
