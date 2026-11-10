package swipread.api.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * Publie les messages de app.event_outbox sur le stream events, dans l'ordre d'écriture, puis les supprime.
 * <p>
 * Chaque lot est traité dans une transaction : si Redis échoue, les lignes restent et seront republiées au tour
 * suivant (un message peut alors partir deux fois, la reco est idempotente sur eventId). {@code SKIP LOCKED} permet
 * à plusieurs instances de l'API de relayer en parallèle sans publier deux fois la même ligne.
 */
@Component
class OutboxRelay {

    static final int BATCH_SIZE = 100;
    static final long INTERVAL_MS = 200;

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final EventStreamPublisher publisher;

    OutboxRelay(JdbcTemplate jdbc, TransactionTemplate transaction, EventStreamPublisher publisher) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.publisher = publisher;
    }

    @Scheduled(fixedDelay = INTERVAL_MS, initialDelay = INTERVAL_MS)
    void relayPending() {
        try {
            int published;
            do {
                published = relayBatch();
            } while (published == BATCH_SIZE);
        } catch (RuntimeException e) {
            log.warn("Outbox relay failed, retrying in {} ms", INTERVAL_MS, e);
        }
    }

    /**
     * @return le nombre de messages publiés
     */
    int relayBatch() {
        Integer published = transaction.execute(status -> {
            List<Row> rows = jdbc.query("""
                            SELECT id, message FROM app.event_outbox
                            ORDER BY id
                            LIMIT ?
                            FOR UPDATE SKIP LOCKED
                            """, (rs, n) -> new Row(rs.getLong(1), rs.getString(2)), BATCH_SIZE);
            rows.forEach(row -> publisher.publish(row.message()));
            if (!rows.isEmpty()) {
                jdbc.update("DELETE FROM app.event_outbox WHERE id = ANY (?)",
                        (Object) rows.stream().map(Row::id).toArray(Long[]::new));
            }
            return rows.size();
        });
        return published == null ? 0 : published;
    }

    private record Row(long id, String message) {
    }
}
