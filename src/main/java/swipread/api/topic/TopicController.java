package swipread.api.topic;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import swipread.api.security.CurrentUserId;
import swipread.api.topic.dto.TopicResponse;
import swipread.api.topic.dto.UpdateTopicsRequest;

import java.util.List;

@RestController
@Tag(name = "Onboarding")
class TopicController {

    private final TopicService topicService;

    TopicController(TopicService topicService) {
        this.topicService = topicService;
    }

    @GetMapping("/v1/topics")
    @Operation(summary = "List the topics offered during onboarding")
    List<TopicResponse> listTopics() {
        return topicService.listAll();
    }

    @PutMapping("/v1/me/topics")
    @Operation(summary = "Replace the topics chosen by the current user")
    List<TopicResponse> updateMyTopics(@CurrentUserId String userId, @Valid @RequestBody UpdateTopicsRequest request) {
        return topicService.replaceForUser(userId, request.topicIds());
    }
}
