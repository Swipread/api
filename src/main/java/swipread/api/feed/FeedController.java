package swipread.api.feed;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import swipread.api.feed.dto.FeedResponse;
import swipread.api.security.CurrentUserId;

@RestController
@Tag(name = "Feed")
class FeedController {

    private final FeedService feedService;

    FeedController(FeedService feedService) {
        this.feedService = feedService;
    }

    @GetMapping("/v1/feed")
    @Operation(summary = "Next page of recommended articles")
    FeedResponse feed(@CurrentUserId String userId,
                      @Parameter(description = "Current app session, forwarded to the recommender")
                      @RequestHeader(name = "X-Session-Id", required = false) String sessionId,
                      @Parameter(description = "mobile or desktop")
                      @RequestHeader(name = "X-Device", required = false) String device) {
        return feedService.nextPage(userId, new FeedContext(sessionId, device));
    }
}
