package swipread.api.feed;

/**
 * Lot d'origine d'un article du stock, conservé dans feed:{userId}:meta sous la forme "requestId|reason".
 */
public record FeedOrigin(String requestId, String reason) {

    public static final String FALLBACK_REASON = "fallback";
    public static final FeedOrigin FALLBACK = new FeedOrigin(FALLBACK_REASON, FALLBACK_REASON);
    public static final FeedOrigin UNKNOWN = new FeedOrigin(null, null);

    String encode() {
        return requestId + "|" + reason;
    }

    static FeedOrigin decode(Object value) {
        if (value == null) {
            return UNKNOWN;
        }
        String[] parts = value.toString().split("\\|", 2);
        return parts.length == 2 ? new FeedOrigin(parts[0], parts[1]) : UNKNOWN;
    }
}
