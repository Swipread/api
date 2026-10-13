package swipread.api.feed;

/**
 * Contexte de session transmis à la reco ; tous les champs sont optionnels.
 */
public record FeedContext(String sessionId, String device) {

    public static final FeedContext EMPTY = new FeedContext(null, null);
}
