package swipread.api.user.dto;

import java.util.List;

/**
 * nextCursor est absent quand il n'y a plus de page.
 */
public record SavedPageResponse(List<SavedArticleResponse> items, String nextCursor) {
}
