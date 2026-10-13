package swipread.api.source;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import swipread.api.source.dto.CreateSourceRequest;
import swipread.api.source.dto.SourceResponse;
import swipread.api.source.dto.UpdateSourceRequest;

import java.util.List;

@RestController
@RequestMapping("/v1/admin/sources")
@Tag(name = "Admin")
class AdminSourceController {

    private final SourceAdminService service;

    AdminSourceController(SourceAdminService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "List ingested sources")
    List<SourceResponse> list() {
        return service.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add a source after checking that its feed returns articles")
    @ApiResponse(responseCode = "201", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "409", description = "A source already uses this feed URL")
    @ApiResponse(responseCode = "422", description = "Feed unreachable, invalid, or without articles")
    SourceResponse create(@Valid @RequestBody CreateSourceRequest request) {
        return service.create(request);
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Activate or deactivate a source")
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", description = "Unknown source")
    SourceResponse update(@PathVariable int id, @Valid @RequestBody UpdateSourceRequest request) {
        return service.setActive(id, request.active());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a source that has no articles")
    @ApiResponse(responseCode = "204", description = "Source deleted")
    @ApiResponse(responseCode = "404", description = "Unknown source")
    @ApiResponse(responseCode = "409", description = "The source has articles: deactivate it instead")
    void delete(@PathVariable int id) {
        service.delete(id);
    }
}
