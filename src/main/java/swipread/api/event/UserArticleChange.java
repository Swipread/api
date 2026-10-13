package swipread.api.event;

import swipread.api.event.dto.EventRequest;

import java.time.Instant;

/**
 * Effet d'un événement sur la ligne app.user_articles correspondante.
 *
 * @param updateLiked vrai pour like/unlike ; likedAt vaut alors occurredAt ou null
 * @param updateSaved vrai pour save/unsave ; savedAt vaut alors occurredAt ou null
 * @param openedAt    occurredAt pour open/read, qui alimentent l'historique de lecture
 */
record UserArticleChange(Instant seenAt, int dwellMs, Double maxScroll, boolean updateLiked, Instant likedAt,
                         boolean updateSaved, Instant savedAt, Instant hiddenAt, Instant openedAt) {

    static UserArticleChange of(EventRequest event) {
        Instant at = event.occurredAt();
        return switch (EventType.of(event.type())) {
            case VIEW -> new UserArticleChange(at, (int) EventPayloads.dwellMs(event), null, false, null, false, null, null, null);
            case OPEN -> new UserArticleChange(at, 0, null, false, null, false, null, null, at);
            case READ -> new UserArticleChange(at, 0, EventPayloads.scrollDepth(event).orElse(null), false, null, false, null, null, at);
            case LIKE -> new UserArticleChange(at, 0, null, true, at, false, null, null, null);
            case UNLIKE -> new UserArticleChange(at, 0, null, true, null, false, null, null, null);
            case SAVE -> new UserArticleChange(at, 0, null, false, null, true, at, null, null);
            case UNSAVE -> new UserArticleChange(at, 0, null, false, null, true, null, null, null);
            case HIDE -> new UserArticleChange(at, 0, null, false, null, false, null, at, null);
            case IMPRESSION, SHARE -> new UserArticleChange(at, 0, null, false, null, false, null, null, null);
        };
    }
}
