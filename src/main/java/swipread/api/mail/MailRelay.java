package swipread.api.mail;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.UnsupportedEncodingException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Envoie les mails de app.mail_outbox en SMTP, un par un, et supprime chaque ligne envoyée.
 * <p>
 * Chaque mail est verrouillé ({@code FOR UPDATE SKIP LOCKED}) le temps de son envoi : plusieurs instances de l'API
 * peuvent relayer en parallèle sans envoyer deux fois le même. Un échec est retenté après {@link #RETRY_DELAYS} ;
 * après la dernière tentative, le mail est marqué en échec (failed_at) et n'est plus retenté.
 */
@Component
class MailRelay {

    /** Délai avant chaque nouvelle tentative : 8 envois au plus sur environ 22 heures. */
    static final List<Duration> RETRY_DELAYS = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5),
            Duration.ofMinutes(15), Duration.ofHours(1), Duration.ofHours(3), Duration.ofHours(6), Duration.ofHours(12));
    static final int MAX_ATTEMPTS = RETRY_DELAYS.size() + 1;
    static final int MAX_PER_RUN = 50;
    static final Duration FAILED_RETENTION = Duration.ofDays(7);

    private static final Logger log = LoggerFactory.getLogger(MailRelay.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final JavaMailSender sender;
    private final MailProperties properties;
    private final Clock clock;

    MailRelay(JdbcTemplate jdbc, TransactionTemplate transaction, JavaMailSender sender, MailProperties properties,
              Clock clock) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.sender = sender;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${swipread.mail.relay-interval:5s}", initialDelayString = "${swipread.mail.relay-interval:5s}")
    void relayDue() {
        try {
            int handled = 0;
            while (handled < MAX_PER_RUN && relayOne()) {
                handled++;
            }
        } catch (RuntimeException e) {
            log.warn("Mail relay failed, retrying at the next run", e);
        }
    }

    /**
     * Tente l'envoi du plus ancien mail dû.
     *
     * @return faux s'il n'y avait aucun mail à envoyer
     */
    boolean relayOne() {
        Boolean found = transaction.execute(status -> {
            Instant now = clock.instant();
            List<Pending> due = jdbc.query("""
                            SELECT id, recipient, subject, text_body, html_body, attempts FROM app.mail_outbox
                            WHERE failed_at IS NULL AND next_attempt_at <= ?
                            ORDER BY id
                            LIMIT 1
                            FOR UPDATE SKIP LOCKED
                            """, (rs, n) -> new Pending(rs.getLong(1), new MailMessage(rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getString(5)), rs.getInt(6)),
                    Timestamp.from(now));
            if (due.isEmpty()) {
                return false;
            }
            Pending pending = due.getFirst();
            try {
                sender.send(toMime(pending.message()));
                jdbc.update("DELETE FROM app.mail_outbox WHERE id = ?", pending.id());
            } catch (MailException | MessagingException | UnsupportedEncodingException e) {
                failed(pending, now, e);
            }
            return true;
        });
        return Boolean.TRUE.equals(found);
    }

    private void failed(Pending pending, Instant now, Exception error) {
        int attempts = pending.attempts() + 1;
        String reason = error.getClass().getSimpleName() + ": " + error.getMessage();
        if (attempts >= MAX_ATTEMPTS) {
            log.error("Mail {} given up after {} attempts: {}", pending.id(), attempts, reason);
            jdbc.update("UPDATE app.mail_outbox SET attempts = ?, last_error = ?, failed_at = ? WHERE id = ?",
                    attempts, reason, Timestamp.from(now), pending.id());
            return;
        }
        Duration delay = RETRY_DELAYS.get(attempts - 1);
        log.warn("Mail {} not sent (attempt {}), retrying in {}: {}", pending.id(), attempts, delay, reason);
        jdbc.update("UPDATE app.mail_outbox SET attempts = ?, last_error = ?, next_attempt_at = ? WHERE id = ?",
                attempts, reason, Timestamp.from(now.plus(delay)), pending.id());
    }

    private MimeMessage toMime(MailMessage message) throws MessagingException, UnsupportedEncodingException {
        MimeMessage mime = sender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(mime, true, "UTF-8");
        helper.setFrom(properties.from(), properties.fromName());
        helper.setTo(message.to());
        helper.setSubject(message.subject());
        helper.setText(message.text(), message.html());
        return mime;
    }

    /**
     * Mails abandonnés : gardés une semaine pour comprendre l'échec, puis effacés (ils contiennent une adresse et
     * parfois un lien personnel).
     */
    @Scheduled(cron = "0 45 3 * * *", zone = "UTC")
    void purgeFailed() {
        int deleted = jdbc.update("DELETE FROM app.mail_outbox WHERE failed_at < ?",
                Timestamp.from(clock.instant().minus(FAILED_RETENTION)));
        if (deleted > 0) {
            log.info("Purged {} failed mails", deleted);
        }
    }

    private record Pending(long id, MailMessage message, int attempts) {
    }
}
