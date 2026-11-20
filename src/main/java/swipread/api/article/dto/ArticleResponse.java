package swipread.api.article.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import swipread.api.article.Article;
import swipread.api.topic.dto.TopicResponse;

import java.time.Instant;
import java.util.List;

public record ArticleResponse(String id, String title, String summary, String url, String imageUrl, String author,
                              String sourceName, String language, int wordCount,
                              @Schema(description = WORD_COUNT_FROM_SUMMARY) boolean wordCountFromSummary,
                              Instant publishedAt,
                              @Schema(description = "Detected topics, most confident first")
                              List<TopicResponse> topics) {

    public static final String WORD_COUNT_FROM_SUMMARY = "True when only the feed's title and summary could be "
            + "counted (the article's page could not be read): wordCount then says nothing of the reading time";

    public static ArticleResponse from(Article article, List<TopicResponse> topics) {
        return new ArticleResponse(article.getId(), article.getTitle(), article.getSummary(), article.getUrl(),
                article.getImageUrl(), article.getAuthor(), article.getSource().getName(), article.getLanguage(),
                article.getWordCount(), article.isWordCountFromSummary(), article.getPublishedAt(), topics);
    }
}
