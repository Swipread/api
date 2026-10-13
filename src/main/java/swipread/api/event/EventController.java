package swipread.api.event;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import swipread.api.event.dto.EventBatchRequest;
import swipread.api.event.dto.EventBatchResponse;
import swipread.api.security.CurrentUserId;

@RestController
@Tag(name = "Events")
class EventController {

    private final EventService eventService;

    EventController(EventService eventService) {
        this.eventService = eventService;
    }

    @PostMapping("/v1/events")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Record a batch of interaction events (deduplicated on eventId)")
    EventBatchResponse ingest(@CurrentUserId String userId, @Valid @RequestBody EventBatchRequest batch) {
        return eventService.ingest(userId, batch);
    }
}
