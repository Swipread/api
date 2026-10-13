package swipread.api.source.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import swipread.api.article.Source;

public record SourceResponse(int id, String name, String siteUrl, String feedUrl, String language,
                             @Schema(description = "Reuse conditions of the content") String license,
                             @Schema(description = "Inactive sources are skipped by the ingestion") boolean active) {

    public static SourceResponse from(Source source) {
        return new SourceResponse(source.getId(), source.getName(), source.getSiteUrl(), source.getFeedUrl(),
                source.getLanguage(), source.getLicense(), source.isActive());
    }
}
