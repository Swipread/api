package swipread.api.source;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param allowLocalFeeds lève les contrôles d'adresse et de port de la vérification des flux. Réservé aux tests
 *                        d'intégration, qui servent leurs flux depuis localhost sur un port aléatoire.
 */
@ConfigurationProperties("swipread.sources")
public record SourceProperties(boolean allowLocalFeeds) {
}
