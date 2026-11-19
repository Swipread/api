package swipread.api.source.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateSourceRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Size(max = 2048) @Pattern(regexp = HTTP_URL, message = "must be an http(s) URL") String siteUrl,
        @NotBlank @Size(max = 2048) @Pattern(regexp = HTTP_URL, message = "must be an http(s) URL") String feedUrl,
        @NotBlank @Pattern(regexp = "^[a-z]{2}$", message = "must be an ISO 639-1 code such as fr") String language,
        @Size(max = 200) String license,
        @Schema(description = "One to three distinct topic ids, in order: the source's articles inherit them, the first"
                + " being their main topic")
        @NotNull @Size(min = 1, max = 3) List<@NotNull Integer> topicIds) {

    static final String HTTP_URL = "^https?://\\S+$";
}
