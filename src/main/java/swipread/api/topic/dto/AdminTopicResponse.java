package swipread.api.topic.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record AdminTopicResponse(int id, String slug, String label,
                                 @Schema(description = "Articles tagged with this topic") long articleCount,
                                 @Schema(description = "Readers who chose this topic") long readerCount) {
}
