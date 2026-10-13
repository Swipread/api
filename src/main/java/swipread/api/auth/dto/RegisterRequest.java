package swipread.api.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Email @Size(max = 254) String email,
        // BCrypt ignore tout ce qui dépasse 72 octets
        @NotBlank @Size(min = 8, max = 72) String password,
        @NotBlank @Size(max = 50) String displayName,
        @Pattern(regexp = "^[a-z]{2}-[A-Z]{2}$", message = "must look like fr-FR") String locale) {
}
