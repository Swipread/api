package swipread.api.feed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;
import swipread.api.reco.RecommendationClient;
import swipread.api.reco.RecommendationResult;
import swipread.api.reco.dto.RecommendationContext;
import swipread.api.reco.dto.RecommendationRequest;
import swipread.api.reco.dto.RecommendedItem;
import swipread.api.user.User;
import swipread.api.user.UserRepository;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Remplit le stock Redis en tâche de fond : reco d'abord, complément ou remplacement par le repli.
 */
@Component
public class FeedRefiller {

    private static final Logger log = LoggerFactory.getLogger(FeedRefiller.class);

    private final FeedStore store;
    private final RecommendationClient recommendationClient;
    private final FallbackFeedProvider fallback;
    private final UserRepository users;
    private final FeedProperties properties;
    private final TaskExecutor executor;

    public FeedRefiller(FeedStore store, RecommendationClient recommendationClient, FallbackFeedProvider fallback,
                        UserRepository users, FeedProperties properties,
                        @Qualifier(FeedConfig.REFILL_EXECUTOR) TaskExecutor executor) {
        this.store = store;
        this.recommendationClient = recommendationClient;
        this.fallback = fallback;
        this.users = users;
        this.properties = properties;
        this.executor = executor;
    }

    /**
     * Lance un remplissage en arrière-plan si le verrou est libre ; sinon ne fait rien.
     */
    public void triggerRefill(String userId, FeedContext context) {
        Optional<String> token = store.tryLock(userId);
        if (token.isEmpty()) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    // Lot écarté parce que le stock a été invalidé entre-temps : on recalcule une fois, verrou toujours tenu
                    if (!refill(userId, context)) {
                        refill(userId, context);
                    }
                } catch (RuntimeException e) {
                    log.error("Feed refill failed for {}", userId, e);
                } finally {
                    store.unlock(userId, token.get());
                }
            });
        } catch (RuntimeException e) {
            store.unlock(userId, token.get());
            throw e;
        }
    }

    /**
     * Invalidation sur événement fort : on garde les premiers éléments pour un scroll fluide et on relance.
     */
    public void invalidate(String userId, FeedContext context) {
        store.trim(userId, properties.pageSize());
        triggerRefill(userId, context);
    }

    /**
     * @return false si le lot a été écarté parce que le stock a changé de version pendant le calcul
     */
    boolean refill(String userId, FeedContext context) {
        long version = store.version(userId);
        List<String> stock = store.stock(userId);
        int missing = properties.stockSize() - stock.size();
        if (missing <= 0) {
            return true;
        }
        Set<String> taken = new HashSet<>(stock);
        List<StockItem> items = new ArrayList<>(missing);
        String requestId = null;

        RecommendationRequest request = new RecommendationRequest(userId, missing, stock, recoContext(userId, context));
        RecommendationResult result = recommendationClient.recommend(request);
        if (result instanceof RecommendationResult.Success(var response)) {
            requestId = response.requestId();
            // seen couvre les articles servis dont les événements ne sont pas encore arrivés en base
            Set<String> seen = store.seenAmong(userId,
                    response.items().stream().map(RecommendedItem::articleId).toList());
            for (RecommendedItem item : response.items()) {
                if (items.size() < missing && !seen.contains(item.articleId()) && taken.add(item.articleId())) {
                    items.add(new StockItem(item.articleId(), new FeedOrigin(requestId, item.reason())));
                }
            }
        } else if (result instanceof RecommendationResult.Failure(var reason, var message)) {
            log.info("Using fallback feed for {} ({}: {})", userId, reason, message);
        }

        if (items.size() < missing) {
            fallback.articleIds(userId, missing - items.size(), taken)
                    .forEach(id -> items.add(new StockItem(id, FeedOrigin.FALLBACK)));
        }
        boolean appended = store.append(userId, items, requestId, version);
        if (!appended) {
            log.debug("Feed refill for {} discarded: the stock changed while it was computed", userId);
        }
        return appended;
    }

    private RecommendationContext recoContext(String userId, FeedContext context) {
        String locale = users.findById(userId).map(User::getLocale).orElse(null);
        return new RecommendationContext(context.sessionId(), locale, context.device());
    }
}
