package swipread.api.mail;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import swipread.api.IntegrationTest;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Envoi réel en SMTP vers Mailpit (conteneur de test), lu par son API HTTP.
 */
class MailIntegrationTest extends IntegrationTest {

    @Autowired
    private MailOutbox outbox;

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private JdbcTemplate jdbc;

    @Value("${mailpit.api-url}")
    private String mailpitUrl;

    private RestClient mailpit;

    @BeforeEach
    void setUp() {
        mailpit = RestClient.create(mailpitUrl);
    }

    @Test
    void aCommittedMailIsSentWithBothPartsThenRemoved() {
        String to = recipient();
        MailTemplate template = new MailTemplate("Bienvenue sur Swipread", List.of("Bonjour <Léa>,", "Un test."),
                new MailTemplate.Action("Confirmer", "https://swipread.example/confirmer?token=a&b"), "Note de bas.");

        transaction.executeWithoutResult(status -> outbox.enqueue(template.to(to)));

        JsonNode message = await().atMost(Duration.ofSeconds(10)).until(() -> find(to), m -> m != null);
        assertThat(message.get("Subject").asString()).isEqualTo("Bienvenue sur Swipread");
        assertThat(message.get("From").get("Address").asString()).isEqualTo("noreply@swipread.test");
        assertThat(message.get("From").get("Name").asString()).isEqualTo("Swipread");
        JsonNode full = mailpit.get().uri("/api/v1/message/{id}", message.get("ID").asString())
                .retrieve().body(JsonNode.class);
        assertThat(full.get("Text").asString()).contains("Bonjour <Léa>,", "Confirmer : https://swipread.example/confirmer?token=a&b");
        assertThat(full.get("HTML").asString()).contains("Bonjour &lt;Léa&gt;,", "href=\"https://swipread.example/confirmer?token=a&amp;b\"");
        assertThat(pending(to)).isZero();
    }

    @Test
    void aRolledBackMailIsNeverSent() throws Exception {
        String to = recipient();

        transaction.executeWithoutResult(status -> {
            outbox.enqueue(new MailMessage(to, "Annulé", "x", "<p>x</p>"));
            status.setRollbackOnly();
        });

        Thread.sleep(1000);
        assertThat(pending(to)).isZero();
        assertThat(find(to)).isNull();
    }

    @Test
    void forgetRemovesTheMailsNotSentYet() {
        String to = recipient();
        jdbc.update("""
                INSERT INTO app.mail_outbox (recipient, subject, text_body, html_body, next_attempt_at)
                VALUES (?, 'Plus tard', 'x', '<p>x</p>', now() + interval '1 hour')
                """, to);

        outbox.forget(to);

        assertThat(pending(to)).isZero();
    }

    @Test
    void deletingTheAccountRemovesItsPendingMails() throws Exception {
        RegisteredUser user = registerUser();
        String email = jdbc.queryForObject("SELECT email FROM app.users WHERE id = ?", String.class, user.userId());
        jdbc.update("""
                INSERT INTO app.mail_outbox (recipient, subject, text_body, html_body, next_attempt_at)
                VALUES (?, 'Plus tard', 'x', '<p>x</p>', now() + interval '1 hour')
                """, email);

        mvc.perform(delete("/v1/me").header("Authorization", user.bearer())).andExpect(status().isNoContent());

        assertThat(pending(email)).isZero();
    }

    private JsonNode find(String to) {
        JsonNode result = mailpit.get().uri("/api/v1/search?query={q}", "to:" + to).retrieve().body(JsonNode.class);
        JsonNode messages = result.get("messages");
        return messages.isEmpty() ? null : messages.get(0);
    }

    private int pending(String to) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM app.mail_outbox WHERE recipient = ?", Integer.class, to);
        return count == null ? 0 : count;
    }

    private static String recipient() {
        return "mail-" + UUID.randomUUID() + "@example.org";
    }
}
