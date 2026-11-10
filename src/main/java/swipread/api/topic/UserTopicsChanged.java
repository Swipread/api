package swipread.api.topic;

/**
 * Publié quand un utilisateur remplace ses thèmes. Les écouteurs le reçoivent après le commit.
 */
public record UserTopicsChanged(String userId) {
}
