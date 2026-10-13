package swipread.api.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneOffset;

/**
 * Crée à l'avance les partitions mensuelles de app.events (mois courant et suivant).
 */
@Component
class EventPartitionJob {

    private static final Logger log = LoggerFactory.getLogger(EventPartitionJob.class);

    private final JdbcTemplate jdbc;
    private final Clock clock;

    EventPartitionJob(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(cron = "0 0 3 * * *", zone = "UTC")
    void ensurePartitions() {
        YearMonth current = YearMonth.now(clock.withZone(ZoneOffset.UTC));
        createPartition(current);
        createPartition(current.plusMonths(1));
    }

    void createPartition(YearMonth month) {
        String name = "events_%d_%02d".formatted(month.getYear(), month.getMonthValue());
        String sql = "CREATE TABLE IF NOT EXISTS app.%s PARTITION OF app.events FOR VALUES FROM ('%s 00:00:00+00') TO ('%s 00:00:00+00')"
                .formatted(name, month.atDay(1), month.plusMonths(1).atDay(1));
        try {
            jdbc.execute(sql);
        } catch (DataAccessException e) {
            // Typiquement : des lignes de ce mois sont déjà dans la partition default et doivent être déplacées à la main
            log.error("Could not create partition app.{}", name, e);
        }
    }
}
