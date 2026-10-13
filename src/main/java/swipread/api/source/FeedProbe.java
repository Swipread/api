package swipread.api.source;

/**
 * Lit un flux RSS ou Atom pour vérifier qu'il renvoie des articles avant d'enregistrer une source.
 */
interface FeedProbe {

    /**
     * @return le nombre d'articles du flux
     * @throws UnreadableFeedException si le flux est injoignable ou n'est pas un flux RSS/Atom valide
     */
    int countEntries(String feedUrl);

    class UnreadableFeedException extends RuntimeException {
        UnreadableFeedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
