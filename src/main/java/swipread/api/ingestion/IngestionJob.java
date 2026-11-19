package swipread.api.ingestion;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Passe d'ingestion planifiée ; absente si swipread.ingestion.enabled vaut false (dev, test).
 */
@Component
@ConditionalOnProperty(name = "swipread.ingestion.enabled", havingValue = "true")
class IngestionJob {

    private final IngestionService ingestion;

    IngestionJob(IngestionService ingestion) {
        this.ingestion = ingestion;
    }

    // Délai compté depuis la fin de la passe précédente : deux passes d'une même instance ne se chevauchent pas
    @Scheduled(fixedDelayString = "${swipread.ingestion.interval}", initialDelayString = "${swipread.ingestion.initial-delay}")
    void run() {
        ingestion.runOnce();
    }
}
