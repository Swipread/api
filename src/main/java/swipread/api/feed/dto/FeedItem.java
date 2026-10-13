package swipread.api.feed.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import swipread.api.article.dto.ArticleResponse;
import swipread.api.feed.FeedOrigin;
import swipread.api.topic.dto.TopicResponse;
import swipread.api.user.UserArticleStates;

import java.time.Instant;
import java.util.List;

/**
 * requestId et reason sont à recopier dans les événements envoyés pour cet article.
 */
public record FeedItem(String articleId, String title, String summary, String url, String imageUrl, String author,
                       String sourceName, String language, int wordCount, Instant publishedAt,
                       @Schema(description = "Detected topics, most confident first") List<TopicResponse> topics,
                       @Schema(description = "Liked by the current user") boolean liked,
                       @Schema(description = "Saved by the current user") boolean saved,
                       String requestId, String reason, int position) {

    public static FeedItem of(ArticleResponse article, FeedOrigin origin, UserArticleStates.State state, int position) {
        return new FeedItem(article.id(), article.title(), article.summary(), article.url(), article.imageUrl(),
                article.author(), article.sourceName(), article.language(), article.wordCount(), article.publishedAt(),
                article.topics(), state.liked(), state.saved(), origin.requestId(), origin.reason(), position);
    }
}
