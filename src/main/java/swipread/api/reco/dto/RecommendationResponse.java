package swipread.api.reco.dto;

import java.time.Instant;
import java.util.List;

public record RecommendationResponse(String requestId, String modelVersion, Instant generatedAt,
                                     List<RecommendedItem> items) {
}
