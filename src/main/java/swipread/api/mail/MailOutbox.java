package swipread.api.mail;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Met un mail en attente dans app.mail_outbox, dans la transaction de l'appelant s'il y en a une : le mail ne part
 * (par {@link MailRelay}) que si elle est validée, et une panne du serveur mail ne la fait jamais échouer.
 */
@Component
public class MailOutbox {

    private final JdbcTemplate jdbc;

    MailOutbox(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void enqueue(MailMessage message) {
        jdbc.update("""
                INSERT INTO app.mail_outbox (recipient, subject, text_body, html_body) VALUES (?, ?, ?, ?)
                """, message.to(), message.subject(), message.text(), message.html());
    }

    /**
     * Suppression d'un compte : les mails qui ne sont pas encore partis vers cette adresse sont effacés.
     */
    @Transactional
    public void forget(String recipient) {
        jdbc.update("DELETE FROM app.mail_outbox WHERE recipient = ?", recipient);
    }
}
