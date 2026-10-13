package swipread.api.event;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import swipread.api.article.ArticleQueryService;
import swipread.api.common.ApiException;
import swipread.api.event.dto.EventBatchRequest;
import swipread.api.event.dto.EventBatchResponse;
import swipread.api.event.dto.EventRequest;
import swipread.api.feed.FeedContext;
import swipread.api.feed.FeedRefiller;
import swipread.api.feed.FeedStore;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static swipread.api.event.EventFixtures.NOW;
import static swipread.api.event.EventFixtures.event;

class EventServiceTest {

    private static final String USER = "u_42";

    private final EventRepository repository = mock(EventRepository.class);
    private final EventOutbox outbox = mock(EventOutbox.class);
    private final ArticleQueryService articles = mock(ArticleQueryService.class);
    private final FeedStore feedStore = mock(FeedStore.class);
    private final FeedRefiller refiller = mock(FeedRefiller.class);
    private final EventService service = new EventService(repository, outbox, articles, feedStore, refiller,
            new TransactionTemplate(mock(PlatformTransactionManager.class)), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void publishesOnlyNewEventsEnrichedWithWordCount() {
        EventRequest view = event("e_1", "view", Map.of("dwellMs", 4200));
        EventRequest duplicate = event("e_2", "impression", null);
        when(repository.insertNew(eq(USER), eq("s_1"), anyList(), eq(NOW))).thenReturn(List.of(view));
        when(articles.wordCounts(List.of("a_1"))).thenReturn(Map.of("a_1", 850));

        EventBatchResponse response = service.ingest(USER, new EventBatchRequest("s_1", List.of(view, duplicate)));

        assertThat(response).isEqualTo(new EventBatchResponse(1, 1));
        ArgumentCaptor<EventMessage> message = ArgumentCaptor.forClass(EventMessage.class);
        verify(outbox).enqueue(message.capture());
        assertThat(message.getValue().version()).isEqualTo(1);
        assertThat(message.getValue().userId()).isEqualTo(USER);
        assertThat(message.getValue().receivedAt()).isEqualTo(NOW);
        assertThat(message.getValue().payload()).containsEntry("dwellMs", 4200).containsEntry("articleWordCount", 850);
        verify(repository).applyToUserArticle(eq(USER), eq("a_1"), any());
        verifyNoInteractions(refiller);
    }

    @Test
    void sameEventIdTwiceInBatchIsInsertedOnceAndEventsAreSortedChronologically() {
        EventRequest unlike = event("e_2", "unlike", "a_1", NOW.minusSeconds(1), null);
        EventRequest like = event("e_1", "like", "a_1", NOW.minusSeconds(5), null);
        when(repository.insertNew(anyString(), anyString(), anyList(), any())).thenAnswer(inv -> inv.getArgument(2));

        service.ingest(USER, new EventBatchRequest("s_1", List.of(unlike, like, like)));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EventRequest>> inserted = ArgumentCaptor.forClass(List.class);
        verify(repository).insertNew(eq(USER), eq("s_1"), inserted.capture(), eq(NOW));
        assertThat(inserted.getValue()).extracting(EventRequest::eventId).containsExactly("e_1", "e_2");
    }

    @Test
    void unknownArticleIsStoredAndPublishedButNotAggregated() {
        EventRequest open = event("e_1", "open", "a_unknown", NOW, null);
        when(repository.insertNew(anyString(), anyString(), anyList(), any())).thenReturn(List.of(open));
        when(articles.wordCounts(anyList())).thenReturn(Map.of());

        service.ingest(USER, new EventBatchRequest("s_1", List.of(open)));

        verify(repository, never()).applyToUserArticle(any(), any(), any());
        verify(outbox).enqueue(any());
    }

    @Test
    void strongEventInvalidatesFeedOnceAndHideRemovesArticle() {
        EventRequest like = event("e_1", "like", "a_1", NOW, null);
        EventRequest hide = event("e_2", "hide", "a_2", NOW, null);
        when(repository.insertNew(anyString(), anyString(), anyList(), any())).thenReturn(List.of(like, hide));

        service.ingest(USER, new EventBatchRequest("s_1", List.of(like, hide)));

        verify(feedStore).remove(USER, "a_2");
        verify(refiller, times(1)).invalidate(USER, new FeedContext("s_1", null));
    }

    @Test
    void longReadWithoutScrollDepthInvalidatesFeed() {
        EventRequest read = event("e_1", "read", "a_1", NOW, Map.of("readMs", 120_000));
        when(repository.insertNew(anyString(), anyString(), anyList(), any())).thenReturn(List.of(read));
        // 460 mots = 2 minutes estimées : 120 s dépassent largement les 60 %
        when(articles.wordCounts(List.of("a_1"))).thenReturn(Map.of("a_1", 460));

        service.ingest(USER, new EventBatchRequest("s_1", List.of(read)));

        verify(refiller).invalidate(USER, new FeedContext("s_1", null));
    }

    @Test
    void shortReadDoesNotInvalidateFeed() {
        EventRequest read = event("e_1", "read", "a_1", NOW, Map.of("readMs", 5_000));
        when(repository.insertNew(anyString(), anyString(), anyList(), any())).thenReturn(List.of(read));
        when(articles.wordCounts(List.of("a_1"))).thenReturn(Map.of("a_1", 460));

        service.ingest(USER, new EventBatchRequest("s_1", List.of(read)));

        verifyNoInteractions(refiller);
    }

    @Test
    void duplicateStrongEventDoesNotInvalidateAgain() {
        EventRequest like = event("e_1", "like", null);
        when(repository.insertNew(anyString(), anyString(), anyList(), any())).thenReturn(List.of());

        EventBatchResponse response = service.ingest(USER, new EventBatchRequest("s_1", List.of(like)));

        assertThat(response).isEqualTo(new EventBatchResponse(0, 1));
        verifyNoInteractions(refiller, outbox);
    }

    @Test
    void rejectsEventsFromTheFuture() {
        EventRequest future = event("e_1", "open", "a_1", NOW.plusSeconds(2 * 86_400), null);

        assertThatThrownBy(() -> service.ingest(USER, new EventBatchRequest("s_1", List.of(future))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("future");
        verifyNoInteractions(repository);
    }
}
