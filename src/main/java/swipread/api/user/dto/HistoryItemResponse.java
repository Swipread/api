package swipread.api.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import swipread.api.article.dto.ArticleResponse;

import java.time.Instant;

public record HistoryItemResponse(ArticleResponse article,
                                  @Schema(description = "Last time the article was opened (open or read event)")
                                  Instant readAt,
                                  @Schema(description = "Liked by the current user") boolean liked,
                                  @Schema(description = "Saved by the current user") boolean saved) {
}
