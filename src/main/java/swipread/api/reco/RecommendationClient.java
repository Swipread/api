package swipread.api.reco;

import swipread.api.reco.dto.RecommendationRequest;

public interface RecommendationClient {

    RecommendationResult recommend(RecommendationRequest request);
}
