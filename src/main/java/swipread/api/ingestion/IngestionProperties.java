package swipread.api.ingestion;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param enabled         lance la lecture planifiée des flux (coupée en dev et en test)
 * @param interval        délai entre la fin d'une passe et le début de la suivante
 * @param initialDelay    délai avant la première passe après le démarrage
 * @param maxAge          les articles publiés avant (maintenant - maxAge) sont ignorés
 * @param maxNewPerSource nouveaux articles au plus par source et par passage
 * @param userAgent       User-Agent des requêtes, et nom cherché dans robots.txt (son premier mot)
 */
@ConfigurationProperties("swipread.ingestion")
public record IngestionProperties(boolean enabled, Duration interval, Duration initialDelay, Duration maxAge,
                                  int maxNewPerSource, String userAgent) {

    /** Jeton produit du User-Agent (« SwipreadBot »), comparé aux lignes User-agent de robots.txt. */
    String robotName() {
        String first = userAgent.strip().split("[\\s/]", 2)[0];
        return first.isEmpty() ? "*" : first;
    }
}
