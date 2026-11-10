package swipread.api.auth;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * @param refreshTtl          durée de vie d'un refresh token, repartie à chaque renouvellement (glissante)
 * @param refreshGrace        délai pendant lequel un token tout juste remplacé reste accepté sans rotation : deux
 *                            onglets qui renouvellent en même temps ne sont pas pris pour un vol
 * @param refreshCookieSecure attribut Secure du cookie ; false seulement en dev et en test, servis en HTTP
 */
@Validated
@ConfigurationProperties("swipread.auth")
public record AuthProperties(@NotNull Duration refreshTtl, @NotNull Duration refreshGrace, boolean refreshCookieSecure) {
}
