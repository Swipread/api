package swipread.api.feed;

import org.junit.jupiter.api.Test;
import swipread.api.article.ArticleQueryService;
import swipread.api.article.dto.ArticleResponse;
import swipread.api.feed.dto.FeedItem;
import swipread.api.feed.dto.FeedResponse;
import swipread.api.topic.dto.TopicResponse;
import swipread.api.user.UserArticleStates;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FeedServiceTest {

    private static final String USER = "u_1";
    private static final FeedContext CONTEXT = new FeedContext("s_1", "mobile");

    private final FeedStore store = mock(FeedStore.class);
    private final FeedRefiller refiller = mock(FeedRefiller.class);
    private final FallbackFeedProvider fallback = mock(FallbackFeedProvider.class);
    private final ArticleQueryService articles = mock(ArticleQueryService.class);
    private final UserArticleStates states = mock(UserArticleStates.class);
    private final FeedService service = new FeedService(store, refiller, fallback, articles, states,
            new FeedProperties(10, 50, 20));

    @Test
    void servesPageFromStockAndMarksItSeen() {
        FeedOrigin origin = new FeedOrigin("r_1", "similar");
        when(store.pop(USER, 10)).thenReturn(List.of(new StockItem("a_1", origin), new StockItem("a_2", origin)));
        when(store.size(USER)).thenReturn(30L);
        when(articles.hydrate(List.of("a_1", "a_2"))).thenReturn(List.of(article("a_1"), article("a_2")));

        FeedResponse response = service.nextPage(USER, CONTEXT);

        assertThat(response.items()).extracting(FeedItem::articleId).containsExactly("a_1", "a_2");
        assertThat(response.items()).extracting(FeedItem::position).containsExactly(0, 1);
        assertThat(response.items().getFirst().requestId()).isEqualTo("r_1");
        assertThat(response.items().getFirst().reason()).isEqualTo("similar");
        verify(store).markSeen(USER, List.of("a_1", "a_2"));
        verify(refiller, never()).triggerRefill(any(), any());
    }

    @Test
    void itemsCarryTheUserStateOfEachArticle() {
        when(store.pop(USER, 10)).thenReturn(List.of(new StockItem("a_1", FeedOrigin.UNKNOWN), new StockItem("a_2", FeedOrigin.UNKNOWN)));
        when(store.size(USER)).thenReturn(30L);
        when(articles.hydrate(List.of("a_1", "a_2"))).thenReturn(List.of(article("a_1"), article("a_2")));
        when(states.find(USER, List.of("a_1", "a_2"))).thenReturn(Map.of("a_1", new UserArticleStates.State(true, true)));

        FeedResponse response = service.nextPage(USER, CONTEXT);

        assertThat(response.items()).extracting(FeedItem::liked).containsExactly(true, false);
        assertThat(response.items()).extracting(FeedItem::saved).containsExactly(true, false);
        assertThat(response.items().getFirst().topics()).extracting(TopicResponse::slug).containsExactly("tech");
    }

    @Test
    void triggersRefillBelowThreshold() {
        when(store.pop(USER, 10)).thenReturn(List.of(new StockItem("a_1", FeedOrigin.UNKNOWN)));
        when(store.size(USER)).thenReturn(19L);
        when(articles.hydrate(anyList())).thenReturn(List.of(article("a_1")));

        service.nextPage(USER, CONTEXT);

        verify(refiller).triggerRefill(USER, CONTEXT);
    }

    @Test
    void emptyStockServesFallbackSynchronouslyThenRefills() {
        when(store.pop(USER, 10)).thenReturn(List.of());
        when(fallback.articleIds(USER, 10, List.of())).thenReturn(List.of("a_9"));
        when(store.size(USER)).thenReturn(0L);
        when(articles.hydrate(List.of("a_9"))).thenReturn(List.of(article("a_9")));

        FeedResponse response = service.nextPage(USER, CONTEXT);

        assertThat(response.items()).singleElement().satisfies(item -> {
            assertThat(item.reason()).isEqualTo("fallback");
            assertThat(item.requestId()).isEqualTo("fallback");
        });
        verify(store).markSeen(USER, List.of("a_9"));
        verify(refiller).triggerRefill(USER, CONTEXT);
    }

    @Test
    void skipsArticlesMissingFromDatabase() {
        when(store.pop(USER, 10)).thenReturn(List.of(new StockItem("a_gone", FeedOrigin.UNKNOWN), new StockItem("a_1", FeedOrigin.UNKNOWN)));
        when(store.size(USER)).thenReturn(30L);
        when(articles.hydrate(List.of("a_gone", "a_1"))).thenReturn(List.of(article("a_1")));

        FeedResponse response = service.nextPage(USER, CONTEXT);

        assertThat(response.items()).singleElement().satisfies(item -> assertThat(item.position()).isZero());
    }

    private static ArticleResponse article(String id) {
        return new ArticleResponse(id, "title", "summary", "https://example.org/" + id, null, null, "source", "fr", 500,
                Instant.parse("2026-10-12T10:00:00Z"), List.of(new TopicResponse(1, "tech", "Tech")));
    }
}
