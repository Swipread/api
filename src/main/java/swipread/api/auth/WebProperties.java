package swipread.api.auth;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;

/**
 * @param baseUrl adresse du web, pour les liens envoyés par mail (https://swipread.example, sans / final)
 */
@Validated
@ConfigurationProperties("swipread.web")
public record WebProperties(@NotNull URI baseUrl) {
}
