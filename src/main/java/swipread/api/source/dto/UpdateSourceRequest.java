package swipread.api.source.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Modification partielle : les champs absents ne changent pas, mais au moins un doit être fourni.
 */
public record UpdateSourceRequest(
        @Schema(description = "Inactive sources are skipped by the ingestion") Boolean active,
        @Schema(description = "One to three distinct topic ids, in order; replaces the source's topics")
        @Size(min = 1, max = 3) List<@NotNull Integer> topicIds) {
}
