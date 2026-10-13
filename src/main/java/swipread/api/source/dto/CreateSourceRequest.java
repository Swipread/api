package swipread.api.source.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateSourceRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Size(max = 2048) @Pattern(regexp = HTTP_URL, message = "must be an http(s) URL") String siteUrl,
        @NotBlank @Size(max = 2048) @Pattern(regexp = HTTP_URL, message = "must be an http(s) URL") String feedUrl,
        @NotBlank @Pattern(regexp = "^[a-z]{2}$", message = "must be an ISO 639-1 code such as fr") String language,
        @Size(max = 200) String license) {

    static final String HTTP_URL = "^https?://\\S+$";
}
