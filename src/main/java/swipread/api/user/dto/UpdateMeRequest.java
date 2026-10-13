package swipread.api.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateMeRequest(@NotBlank @Size(max = 50) String displayName) {
}
