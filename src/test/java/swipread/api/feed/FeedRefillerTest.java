package swipread.api.feed;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import swipread.api.reco.RecommendationClient;
import swipread.api.reco.RecommendationResult;
import swipread.api.reco.dto.RecommendationRequest;
import swipread.api.reco.dto.RecommendationResponse;
import swipread.api.reco.dto.RecommendedItem;
import swipread.api.user.UserRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FeedRefillerTest {

    private static final String USER = "u_1";

    private final FeedStore store = mock(FeedStore.class);
    private final RecommendationClient reco = mock(RecommendationClient.class);
    private final FallbackFeedProvider fallback = mock(FallbackFeedProvider.class);
    private final UserRepository users = mock(UserRepository.class);
    private final FeedRefiller refiller = new FeedRefiller(store, reco, fallback, users,
            new FeedProperties(10, 50, 20), Runnable::run);

    @BeforeEach
    void setUp() {
        when(store.seenAmong(eq(USER), anyCollection())).thenReturn(Set.of());
        when(store.tryLock(USER)).thenReturn(Optional.of("token"));
        when(store.version(USER)).thenReturn(3L);
        when(store.append(eq(USER), anyList(), any(), anyLong())).thenReturn(true);
    }

    @Test
    void requestsMissingCountAndExcludesCurrentStock() {
        List<String> stock = ids("a_s", 15);
        when(store.stock(USER)).thenReturn(stock);
        when(reco.recommend(any())).thenReturn(success("r_1", ids("a_r", 35)));

        refiller.refill(USER, new FeedContext("s_1", "mobile"));

        ArgumentCaptor<RecommendationRequest> request = ArgumentCaptor.forClass(RecommendationRequest.class);
        verify(reco).recommend(request.capture());
        assertThat(request.getValue().count()).isEqualTo(35);
        assertThat(request.getValue().exclude()).isEqualTo(stock);
        assertThat(request.getValue().context().sessionId()).isEqualTo("s_1");

        List<StockItem> pushed = capturedAppend("r_1");
        assertThat(pushed).hasSize(35).allSatisfy(item -> assertThat(item.origin()).isEqualTo(new FeedOrigin("r_1", "explore")));
        verifyNoInteractions(fallback);
    }

    @Test
    void completesShortRecommendationWithFallback() {
        when(store.stock(USER)).thenReturn(List.of());
        when(reco.recommend(any())).thenReturn(success("r_2", ids("a_r", 30)));
        when(fallback.articleIds(eq(USER), eq(20), anyCollection())).thenReturn(ids("a_f", 20));

        refiller.refill(USER, FeedContext.EMPTY);

        List<StockItem> pushed = capturedAppend("r_2");
        assertThat(pushed).hasSize(50);
        assertThat(pushed.subList(30, 50)).allSatisfy(item -> assertThat(item.origin()).isEqualTo(FeedOrigin.FALLBACK));
    }

    @Test
    void dropsRecommendedArticlesAlreadySeenOrInStock() {
        when(store.stock(USER)).thenReturn(List.of("a_stock"));
        when(store.seenAmong(eq(USER), anyCollection())).thenReturn(Set.of("a_seen"));
        when(reco.recommend(any())).thenReturn(success("r_3", List.of("a_stock", "a_seen", "a_new", "a_new")));
        when(fallback.articleIds(eq(USER), eq(48), anyCollection())).thenReturn(List.of());

        refiller.refill(USER, FeedContext.EMPTY);

        assertThat(capturedAppend("r_3")).extracting(StockItem::articleId).containsExactly("a_new");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> excluded = ArgumentCaptor.forClass(Collection.class);
        verify(fallback).articleIds(eq(USER), eq(48), excluded.capture());
        // Le repli filtre seen lui-même ; on lui passe le stock et les articles déjà retenus
        assertThat(excluded.getValue()).contains("a_stock", "a_new");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> checked = ArgumentCaptor.forClass(Collection.class);
        verify(store).seenAmong(eq(USER), checked.capture());
        assertThat(checked.getValue()).containsExactly("a_stock", "a_seen", "a_new", "a_new");
    }

    @Test
    void fallsBackEntirelyWhenRecommendationFails() {
        when(store.stock(USER)).thenReturn(ids("a_s", 5));
        when(reco.recommend(any())).thenReturn(new RecommendationResult.Failure(RecommendationResult.Reason.UNAVAILABLE, "timeout"));
        when(fallback.articleIds(eq(USER), eq(45), anyCollection())).thenReturn(ids("a_f", 45));

        refiller.refill(USER, FeedContext.EMPTY);

        assertThat(capturedAppend(null)).hasSize(45).allSatisfy(item -> assertThat(item.origin()).isEqualTo(FeedOrigin.FALLBACK));
    }

    @Test
    void doesNothingWhenStockIsFull() {
        when(store.stock(USER)).thenReturn(ids("a_s", 50));

        refiller.refill(USER, FeedContext.EMPTY);

        verifyNoInteractions(reco, fallback);
        verify(store, never()).append(any(), anyList(), any(), anyLong());
    }

    @Test
    void triggerSkipsWhenLockIsHeldAndReleasesItOtherwise() {
        when(store.tryLock(USER)).thenReturn(Optional.empty());
        refiller.triggerRefill(USER, FeedContext.EMPTY);
        verify(store, never()).stock(any());

        when(store.tryLock(USER)).thenReturn(Optional.of("token"));
        when(store.stock(USER)).thenReturn(ids("a_s", 50));
        refiller.triggerRefill(USER, FeedContext.EMPTY);
        verify(store).unlock(USER, "token");
    }

    @Test
    void invalidationKeepsFirstPageAndRefills() {
        when(store.stock(USER)).thenReturn(ids("a_s", 10));
        when(reco.recommend(any())).thenReturn(success("r_4", ids("a_r", 40)));

        refiller.invalidate(USER, FeedContext.EMPTY);

        verify(store).trim(USER, 10);
        assertThat(capturedAppend("r_4")).hasSize(40);
    }

    @Test
    void recomputesOnceWhenTheStockChangedDuringTheRefill() {
        when(store.stock(USER)).thenReturn(List.of());
        when(reco.recommend(any())).thenReturn(success("r_5", ids("a_r", 50)));
        // Invalidé pendant le premier calcul, puis toujours pendant le second
        when(store.version(USER)).thenReturn(3L, 4L);
        when(store.append(eq(USER), anyList(), any(), anyLong())).thenReturn(false);

        refiller.triggerRefill(USER, FeedContext.EMPTY);

        verify(store).append(eq(USER), anyList(), eq("r_5"), eq(3L));
        verify(store).append(eq(USER), anyList(), eq("r_5"), eq(4L));
        verify(reco, times(2)).recommend(any());
        verify(store).unlock(USER, "token");
    }

    @Test
    void doesNotRecomputeWhenTheBatchWasAdded() {
        when(store.stock(USER)).thenReturn(List.of());
        when(reco.recommend(any())).thenReturn(success("r_6", ids("a_r", 50)));

        refiller.triggerRefill(USER, FeedContext.EMPTY);

        verify(reco, times(1)).recommend(any());
    }

    @SuppressWarnings("unchecked")
    private List<StockItem> capturedAppend(String requestId) {
        ArgumentCaptor<List<StockItem>> items = ArgumentCaptor.forClass(List.class);
        verify(store).append(eq(USER), items.capture(), eq(requestId), eq(3L));
        return items.getValue();
    }

    private static RecommendationResult success(String requestId, List<String> articleIds) {
        List<RecommendedItem> items = articleIds.stream().map(id -> new RecommendedItem(id, 0.5, "explore")).toList();
        return new RecommendationResult.Success(new RecommendationResponse(requestId, "test", Instant.now(), items));
    }

    private static List<String> ids(String prefix, int count) {
        return IntStream.range(0, count).mapToObj(i -> prefix + i).toList();
    }
}
