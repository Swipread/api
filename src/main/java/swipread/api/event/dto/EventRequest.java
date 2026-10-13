package swipread.api.event.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Map;

public record EventRequest(
        @NotBlank @Size(max = 64) String eventId,
        @NotNull @Pattern(regexp = "impression|view|open|read|like|unlike|save|unsave|share|hide") String type,
        @NotBlank @Size(max = 64) String articleId,
        @Size(max = 64) String requestId,
        @PositiveOrZero Integer position,
        @NotNull Instant occurredAt,
        Map<String, Object> payload) {

    public Map<String, Object> payloadOrEmpty() {
        return payload == null ? Map.of() : payload;
    }
}
