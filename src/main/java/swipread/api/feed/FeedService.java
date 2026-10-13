package swipread.api.feed;

import org.springframework.stereotype.Service;
import swipread.api.article.ArticleQueryService;
import swipread.api.article.dto.ArticleResponse;
import swipread.api.feed.dto.FeedItem;
import swipread.api.feed.dto.FeedResponse;
import swipread.api.user.UserArticleStates;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class FeedService {

    private final FeedStore store;
    private final FeedRefiller refiller;
    private final FallbackFeedProvider fallback;
    private final ArticleQueryService articles;
    private final UserArticleStates states;
    private final FeedProperties properties;

    public FeedService(FeedStore store, FeedRefiller refiller, FallbackFeedProvider fallback,
                       ArticleQueryService articles, UserArticleStates states, FeedProperties properties) {
        this.store = store;
        this.refiller = refiller;
        this.fallback = fallback;
        this.articles = articles;
        this.states = states;
        this.properties = properties;
    }

    public FeedResponse nextPage(String userId, FeedContext context) {
        List<StockItem> page = store.pop(userId, properties.pageSize());
        if (page.isEmpty()) {
            // Stock vide : on ne fait pas attendre la reco, la page vient directement du repli
            page = fallback.articleIds(userId, properties.pageSize(), List.of()).stream()
                    .map(id -> new StockItem(id, FeedOrigin.FALLBACK))
                    .toList();
        }
        List<String> ids = page.stream().map(StockItem::articleId).toList();
        store.markSeen(userId, ids);
        if (store.size(userId) < properties.refillThreshold()) {
            refiller.triggerRefill(userId, context);
        }
        return new FeedResponse(toItems(userId, page));
    }

    private List<FeedItem> toItems(String userId, List<StockItem> page) {
        Map<String, FeedOrigin> origins = new HashMap<>();
        page.forEach(item -> origins.put(item.articleId(), item.origin()));
        List<String> ids = page.stream().map(StockItem::articleId).toList();
        List<ArticleResponse> hydrated = articles.hydrate(ids);
        Map<String, UserArticleStates.State> userStates = states.find(userId, ids);
        List<FeedItem> items = new ArrayList<>(hydrated.size());
        for (ArticleResponse article : hydrated) {
            items.add(FeedItem.of(article, origins.get(article.id()),
                    userStates.getOrDefault(article.id(), UserArticleStates.State.NONE), items.size()));
        }
        return items;
    }
}
