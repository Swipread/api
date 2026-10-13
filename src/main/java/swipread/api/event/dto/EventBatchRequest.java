package swipread.api.event.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record EventBatchRequest(
        @NotBlank @Size(max = 64) String sessionId,
        @NotNull @Size(min = 1, max = 100) List<@NotNull @Valid EventRequest> events) {
}
