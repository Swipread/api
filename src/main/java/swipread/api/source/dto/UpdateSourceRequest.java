package swipread.api.source.dto;

import jakarta.validation.constraints.NotNull;

public record UpdateSourceRequest(@NotNull Boolean active) {
}
