package swipread.api.reco;

import swipread.api.reco.dto.RecommendationResponse;

/**
 * Issue d'un appel à la reco. Les échecs ne lèvent pas d'exception : l'appelant bascule sur le feed de repli.
 */
public sealed interface RecommendationResult {

    record Success(RecommendationResponse response) implements RecommendationResult {
    }

    record Failure(Reason reason, String message) implements RecommendationResult {
    }

    enum Reason {
        /** 400 : requête invalide, à corriger côté backend, pas de retry. */
        BAD_REQUEST,
        /** 404 : utilisateur inconnu de la reco (nouvel inscrit). */
        UNKNOWN_USER,
        /** 503, timeout, erreur réseau ou réponse inattendue. */
        UNAVAILABLE
    }
}
