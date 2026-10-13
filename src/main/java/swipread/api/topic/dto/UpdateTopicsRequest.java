package swipread.api.topic.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

// US3 : au moins 3 thèmes à l'onboarding
public record UpdateTopicsRequest(@NotNull @Size(min = 3, max = 50) List<@NotNull Integer> topicIds) {
}
