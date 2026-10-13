package swipread.api.topic;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import swipread.api.topic.dto.AdminTopicResponse;
import swipread.api.topic.dto.TopicLabelRequest;

import java.util.List;

@RestController
@RequestMapping("/v1/admin/topics")
@Tag(name = "Admin")
class AdminTopicController {

    private final TopicAdminService service;

    AdminTopicController(TopicAdminService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "List topics with their article and reader counts")
    List<AdminTopicResponse> list() {
        return service.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a topic; the slug is derived from the label")
    @ApiResponse(responseCode = "201", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "409", description = "A topic with the same slug already exists")
    AdminTopicResponse create(@Valid @RequestBody TopicLabelRequest request) {
        return service.create(request.label());
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Rename a topic (the slug does not change)")
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", description = "Unknown topic")
    AdminTopicResponse relabel(@PathVariable int id, @Valid @RequestBody TopicLabelRequest request) {
        return service.relabel(id, request.label());
    }
}
