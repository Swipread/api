package swipread.api.user.dto;

import java.util.List;

/**
 * nextCursor est absent quand il n'y a plus de page.
 */
public record HistoryPageResponse(List<HistoryItemResponse> items, String nextCursor) {
}
