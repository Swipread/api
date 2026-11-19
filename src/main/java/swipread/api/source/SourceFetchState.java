package swipread.api.source;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * État de la dernière lecture d'une source par l'ingestion (V8), montré aux admins.
 */
@Component
public class SourceFetchState {

    static final int MAX_ERROR_LENGTH = 500;

    private final SourceRepository sources;

    SourceFetchState(SourceRepository sources) {
        this.sources = sources;
    }

    /** Lecture réussie : les validateurs HTTP servent à la lecture conditionnelle suivante, l'erreur est effacée. */
    @Transactional
    public void recordFetch(int sourceId, Instant at, String etag, String lastModified) {
        sources.findById(sourceId).ifPresent(source -> source.recordFetch(at, etag, lastModified));
    }

    /** Lecture en échec : la date de la dernière lecture réussie est gardée. */
    @Transactional
    public void recordError(int sourceId, String error) {
        String message = error.length() > MAX_ERROR_LENGTH ? error.substring(0, MAX_ERROR_LENGTH) : error;
        sources.findById(sourceId).ifPresent(source -> source.recordError(message));
    }
}
