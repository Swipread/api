package swipread.api.source;

import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Crée des clients {@link SafeHttp} soumis à la protection SSRF des sources, pour les autres modules (ingestion).
 * L'appelant ferme le client qu'il a créé.
 */
@Component
public class SafeHttpFactory {

    private final FeedAddressPolicy addressPolicy;

    SafeHttpFactory(FeedAddressPolicy addressPolicy) {
        this.addressPolicy = addressPolicy;
    }

    public SafeHttp create(Duration connectTimeout, Duration readTimeout, String userAgent) {
        return new SafeHttp(addressPolicy, connectTimeout, readTimeout, userAgent);
    }
}
