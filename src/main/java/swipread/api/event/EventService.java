package swipread.api.event;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import swipread.api.article.ArticleQueryService;
import swipread.api.common.ApiException;
import swipread.api.event.dto.EventBatchRequest;
import swipread.api.event.dto.EventBatchResponse;
import swipread.api.event.dto.EventRequest;
import swipread.api.feed.FeedContext;
import swipread.api.feed.FeedRefiller;
import swipread.api.feed.FeedStore;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class EventService {

    static final Duration MAX_CLOCK_SKEW = Duration.ofDays(1);

    private final EventRepository repository;
    private final EventOutbox outbox;
    private final ArticleQueryService articles;
    private final FeedStore feedStore;
    private final FeedRefiller refiller;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public EventService(EventRepository repository, EventOutbox outbox, ArticleQueryService articles,
                        FeedStore feedStore, FeedRefiller refiller, TransactionTemplate transaction, Clock clock) {
        this.repository = repository;
        this.outbox = outbox;
        this.articles = articles;
        this.feedStore = feedStore;
        this.refiller = refiller;
        this.transaction = transaction;
        this.clock = clock;
    }

    public EventBatchResponse ingest(String userId, EventBatchRequest batch) {
        Instant receivedAt = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        validate(batch.events(), receivedAt);

        // Un même eventId renvoyé dans le lot ne compte qu'une fois ; l'ordre chronologique
        // garantit qu'un unlike postérieur à un like l'emporte.
        Map<String, EventRequest> unique = new LinkedHashMap<>();
        batch.events().forEach(event -> unique.putIfAbsent(event.eventId(), event));
        List<EventRequest> events = unique.values().stream()
                .sorted(Comparator.comparing(EventRequest::occurredAt))
                .toList();

        // Les messages passent par l'outbox, écrite dans la même transaction : ils ne partent sur le stream que si
        // les événements sont enregistrés, et Redis indisponible ne fait pas échouer l'enregistrement.
        Inserted inserted = transaction.execute(status -> {
            List<EventRequest> fresh = repository.insertNew(userId, batch.sessionId(), events, receivedAt);
            Map<String, Integer> wordCounts = articles.wordCounts(fresh.stream().map(EventRequest::articleId).toList());
            for (EventRequest event : fresh) {
                if (wordCounts.containsKey(event.articleId())) {
                    repository.applyToUserArticle(userId, event.articleId(), UserArticleChange.of(event));
                }
                outbox.enqueue(toMessage(userId, batch.sessionId(), event, receivedAt, wordCounts.get(event.articleId())));
            }
            return new Inserted(fresh, wordCounts);
        });

        invalidateFeedIfNeeded(userId, batch.sessionId(), inserted);
        return new EventBatchResponse(inserted.events().size(), batch.events().size() - inserted.events().size());
    }

    private void validate(List<EventRequest> events, Instant now) {
        for (EventRequest event : events) {
            if (event.occurredAt().isAfter(now.plus(MAX_CLOCK_SKEW))) {
                throw ApiException.badRequest("Event " + event.eventId() + ": occurredAt is in the future");
            }
            EventPayloads.validate(event);
        }
    }

    private void invalidateFeedIfNeeded(String userId, String sessionId, Inserted inserted) {
        inserted.events().stream()
                .filter(event -> EventType.of(event.type()) == EventType.HIDE)
                .forEach(event -> feedStore.remove(userId, event.articleId()));
        if (inserted.events().stream()
                .anyMatch(event -> StrongEventPolicy.isStrong(event, inserted.wordCounts().get(event.articleId())))) {
            refiller.invalidate(userId, new FeedContext(sessionId, null));
        }
    }

    private record Inserted(List<EventRequest> events, Map<String, Integer> wordCounts) {
    }

    static EventMessage toMessage(String userId, String sessionId, EventRequest event, Instant receivedAt,
                                  Integer articleWordCount) {
        Map<String, Object> payload = new HashMap<>(event.payloadOrEmpty());
        if (articleWordCount != null) {
            payload.put("articleWordCount", articleWordCount);
        }
        return new EventMessage(EventMessage.VERSION, event.eventId(), userId, sessionId, event.type(),
                event.articleId(), event.requestId(), event.position(), event.occurredAt(), receivedAt, payload);
    }
}
