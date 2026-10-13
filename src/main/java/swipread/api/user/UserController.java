package swipread.api.user;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import swipread.api.security.CurrentUserId;
import swipread.api.user.dto.HistoryPageResponse;
import swipread.api.user.dto.MeResponse;
import swipread.api.user.dto.SavedPageResponse;
import swipread.api.user.dto.UpdateMeRequest;

@RestController
@RequestMapping("/v1/me")
@Tag(name = "Me")
class UserController {

    private final UserService userService;
    private final SavedArticleService savedArticleService;
    private final HistoryService historyService;

    UserController(UserService userService, SavedArticleService savedArticleService, HistoryService historyService) {
        this.userService = userService;
        this.savedArticleService = savedArticleService;
        this.historyService = historyService;
    }

    @GetMapping
    @Operation(summary = "Current user profile with chosen topics")
    MeResponse me(@CurrentUserId String userId) {
        return userService.me(userId);
    }

    @PatchMapping
    @Operation(summary = "Update the current user profile")
    MeResponse update(@CurrentUserId String userId, @Valid @RequestBody UpdateMeRequest request) {
        return userService.update(userId, request);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete the account and all personal data",
            description = "The access token is rejected afterwards. The recommender is notified on the events stream.")
    @ApiResponse(responseCode = "204", description = "Account deleted")
    void delete(@CurrentUserId String userId) {
        userService.delete(userId);
    }

    @GetMapping("/saved")
    @Operation(summary = "Saved articles, most recently saved first")
    SavedPageResponse saved(@CurrentUserId String userId,
                            @Parameter(description = "nextCursor from the previous page")
                            @RequestParam(required = false) String cursor,
                            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return savedArticleService.page(userId, cursor, limit);
    }

    @GetMapping("/history")
    @Operation(summary = "Reading history: opened articles, most recently opened first")
    HistoryPageResponse history(@CurrentUserId String userId,
                                @Parameter(description = "nextCursor from the previous page")
                                @RequestParam(required = false) String cursor,
                                @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return historyService.page(userId, cursor, limit);
    }
}
