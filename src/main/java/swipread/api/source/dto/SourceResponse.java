package swipread.api.source.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import swipread.api.article.Source;
import swipread.api.topic.dto.TopicResponse;

import java.time.Instant;
import java.util.List;

public record SourceResponse(int id, String name, String siteUrl, String feedUrl, String language,
                             @Schema(description = "Reuse conditions of the content") String license,
                             @Schema(description = "Inactive sources are skipped by the ingestion") boolean active,
                             @Schema(description = "The source's topics, in order; its articles inherit them. A source"
                                     + " without topics is skipped by the ingestion")
                             List<TopicResponse> topics,
                             @Schema(description = "Last successful read of the feed by the ingestion; null if never")
                             Instant lastFetchedAt,
                             @Schema(description = "Error of the last read; null when it succeeded")
                             String lastError) {

    public static SourceResponse from(Source source, List<TopicResponse> topics) {
        return new SourceResponse(source.getId(), source.getName(), source.getSiteUrl(), source.getFeedUrl(),
                source.getLanguage(), source.getLicense(), source.isActive(), topics, source.getLastFetchedAt(),
                source.getLastError());
    }
}
