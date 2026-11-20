package swipread.api.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChangePasswordRequest(
        @NotBlank String currentPassword,
        // Mêmes règles qu'à l'inscription : BCrypt ignore tout ce qui dépasse 72 octets
        @NotBlank @Size(min = 8, max = 72) String newPassword) {
}
