package swipread.api.event;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Écrit un message du stream events dans app.event_outbox, dans la transaction de l'appelant : il n'est publié
 * (par {@link OutboxRelay}) que si cette transaction est validée.
 */
@Component
class EventOutbox {

    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    EventOutbox(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void enqueue(EventMessage message) {
        jdbc.update("INSERT INTO app.event_outbox (message) VALUES (?)", json.writeValueAsString(message));
    }
}
