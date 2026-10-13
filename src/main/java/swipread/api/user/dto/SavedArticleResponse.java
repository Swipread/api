package swipread.api.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import swipread.api.article.dto.ArticleResponse;

import java.time.Instant;

public record SavedArticleResponse(ArticleResponse article, Instant savedAt,
                                   @Schema(description = "Liked by the current user") boolean liked) {
}
