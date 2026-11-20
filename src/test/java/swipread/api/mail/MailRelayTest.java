package swipread.api.mail;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.transaction.support.TransactionTemplate;
import swipread.api.IntegrationTest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tentatives et abandon, avec un serveur mail qui refuse tout et une horloge fixe.
 */
class MailRelayTest extends IntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transaction;

    @Test
    void aFailingMailIsRetriedLaterThenGivenUpAfterTheLastAttempt() {
        String to = "relay-" + UUID.randomUUID() + "@example.org";
        long id = jdbc.queryForObject("""
                INSERT INTO app.mail_outbox (recipient, subject, text_body, html_body, next_attempt_at)
                VALUES (?, 'Test', 'x', '<p>x</p>', '2100-01-01T00:00:00Z') RETURNING id
                """, Long.class, to);
        JavaMailSender failing = mock(JavaMailSender.class);
        when(failing.createMimeMessage()).thenAnswer(inv -> new JavaMailSenderImpl().createMimeMessage());
        doThrow(new MailSendException("Connection refused")).when(failing).send(any(jakarta.mail.internet.MimeMessage.class));
        Instant now = Instant.parse("2100-01-01T00:00:00Z");
        // Le relais planifié (heure réelle) ne verra jamais ce mail, dû en 2100 ; le relais du test, à l'horloge
        // fixée en 2100, prend le plus ancien mail dû : attendre que ceux des autres tests soient partis.
        await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.queryForObject(
                "SELECT count(*) FROM app.mail_outbox WHERE failed_at IS NULL AND id <> ?", Integer.class, id) == 0);

        for (int attempt = 1; attempt <= MailRelay.MAX_ATTEMPTS; attempt++) {
            MailRelay relay = relayAt(failing, now);
            assertThat(relay.relayOne()).isTrue();
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT attempts, next_attempt_at, last_error, failed_at FROM app.mail_outbox WHERE id = ?", id);
            assertThat(row.get("attempts")).isEqualTo(attempt);
            assertThat(row.get("last_error").toString()).contains("Connection refused");
            if (attempt < MailRelay.MAX_ATTEMPTS) {
                Duration delay = MailRelay.RETRY_DELAYS.get(attempt - 1);
                assertThat(((java.sql.Timestamp) row.get("next_attempt_at")).toInstant()).isEqualTo(now.plus(delay));
                assertThat(row.get("failed_at")).isNull();
                // Pas de nouvelle tentative avant le délai
                assertThat(relayAt(failing, now.plus(delay).minusSeconds(1)).relayOne()).isFalse();
                now = now.plus(delay);
            } else {
                assertThat(row.get("failed_at")).isNotNull();
            }
        }
        // Abandonné : plus jamais tenté
        assertThat(relayAt(failing, now.plus(Duration.ofDays(1))).relayOne()).isFalse();

        // Effacé une semaine après l'abandon
        relayAt(failing, now.plus(MailRelay.FAILED_RETENTION).plusSeconds(1)).purgeFailed();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app.mail_outbox WHERE id = ?", Integer.class, id)).isZero();
    }

    private MailRelay relayAt(JavaMailSender sender, Instant now) {
        return new MailRelay(jdbc, transaction, sender, new MailProperties("noreply@swipread.test", "Swipread", null),
                Clock.fixed(now, ZoneOffset.UTC));
    }
}
