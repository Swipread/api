package swipread.api.feed;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import swipread.api.topic.UserTopicsChanged;

/**
 * US4 : un changement de thèmes vide le stock, calculé sur les anciens thèmes. Le GET /v1/feed suivant sert sa
 * première page par le repli (nouveaux thèmes) et relance la reco en arrière-plan.
 */
@Component
class TopicsChangedListener {

    private final FeedStore store;

    TopicsChangedListener(FeedStore store) {
        this.store = store;
    }

    // Après le commit : un PUT refusé ou annulé ne vide rien
    @TransactionalEventListener
    void onTopicsChanged(UserTopicsChanged event) {
        store.resetStock(event.userId());
    }
}
