package swipread.api.security;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties("swipread.jwt")
public record JwtProperties(
        // HS256 exige une clé d'au moins 256 bits
        @NotNull @Size(min = 32) String secret,
        @NotNull Duration ttl) {
}
