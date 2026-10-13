package swipread.api.topic.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TopicLabelRequest(@NotBlank @Size(max = 50) String label) {
}
