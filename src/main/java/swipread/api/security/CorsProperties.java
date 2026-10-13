package swipread.api.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Origines du frontend autorisées à appeler l'API depuis un navigateur. Liste vide : aucun appel cross-origin.
 */
@ConfigurationProperties("swipread.cors")
public record CorsProperties(List<String> allowedOrigins) {

    public CorsProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
    }
}
