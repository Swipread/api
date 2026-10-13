package swipread.api.event;

import swipread.api.event.dto.EventRequest;

/**
 * Événements forts : like, save, share, hide, et read « complet ». Ils déclenchent l'invalidation du stock Redis de
 * l'utilisateur.
 * <p>
 * Un read est complet si scrollDepth dépasse 0,8 (règle du contrat), ou si readMs atteint 60 % du temps de lecture
 * estimé à 230 mots par minute (écart validé : le front web ne peut pas mesurer scrollDepth).
 */
final class StrongEventPolicy {

    static final double DEEP_READ_THRESHOLD = 0.8;
    static final int READING_WORDS_PER_MINUTE = 230;
    static final double COMPLETE_READ_RATIO = 0.6;

    private StrongEventPolicy() {
    }

    /**
     * @param articleWordCount longueur de l'article, null s'il est inconnu en base
     */
    static boolean isStrong(EventRequest event, Integer articleWordCount) {
        return switch (EventType.of(event.type())) {
            case LIKE, SAVE, SHARE, HIDE -> true;
            case READ -> isDeepRead(event) || isLongRead(event, articleWordCount);
            default -> false;
        };
    }

    private static boolean isDeepRead(EventRequest event) {
        return EventPayloads.scrollDepth(event).filter(depth -> depth > DEEP_READ_THRESHOLD).isPresent();
    }

    private static boolean isLongRead(EventRequest event, Integer articleWordCount) {
        if (articleWordCount == null || articleWordCount <= 0) {
            return false;
        }
        return EventPayloads.readMs(event) >= COMPLETE_READ_RATIO * estimatedReadingMs(articleWordCount);
    }

    static double estimatedReadingMs(int wordCount) {
        return wordCount * 60_000.0 / READING_WORDS_PER_MINUTE;
    }
}
