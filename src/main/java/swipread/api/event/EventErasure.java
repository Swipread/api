package swipread.api.event;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import swipread.api.common.IdGenerator;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/**
 * Effacement des événements d'un compte supprimé, et avis à la reco par un message user_deleted sur le stream.
 */
@Component
public class EventErasure {

    private final EventRepository repository;
    private final EventOutbox outbox;
    private final IdGenerator idGenerator;
    private final Clock clock;

    EventErasure(EventRepository repository, EventOutbox outbox, IdGenerator idGenerator, Clock clock) {
        this.repository = repository;
        this.outbox = outbox;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    /**
     * À appeler dans la transaction qui supprime le compte : le message user_deleted n'est publié que si la
     * suppression est validée.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void eraseUser(String userId) {
        repository.deleteByUser(userId);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        outbox.enqueue(new EventMessage(EventMessage.VERSION, idGenerator.newEventId(), userId, null,
                EventMessage.USER_DELETED, null, null, null, now, now, Map.of()));
    }
}
