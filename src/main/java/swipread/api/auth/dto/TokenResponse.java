package swipread.api.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record TokenResponse(String accessToken,
                            @Schema(example = "Bearer") String tokenType,
                            @Schema(description = "Lifetime of the access token, in seconds", example = "900") long expiresIn,
                            String userId) {
}
