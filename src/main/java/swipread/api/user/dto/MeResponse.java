package swipread.api.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import swipread.api.topic.dto.TopicResponse;
import swipread.api.user.Role;

import java.time.Instant;
import java.util.List;

public record MeResponse(String id, String email, String displayName, String locale, Instant createdAt,
                         @Schema(description = "ADMIN gives access to /v1/admin endpoints") Role role,
                         List<TopicResponse> topics) {
}
