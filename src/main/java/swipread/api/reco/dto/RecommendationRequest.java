package swipread.api.reco.dto;

import java.util.List;

public record RecommendationRequest(String userId, int count, List<String> exclude, RecommendationContext context) {
}
