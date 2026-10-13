package swipread.api.user;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import swipread.api.common.ApiException;
import swipread.api.event.EventErasure;
import swipread.api.feed.FeedStore;
import swipread.api.topic.TopicService;
import swipread.api.user.dto.MeResponse;
import swipread.api.user.dto.UpdateMeRequest;

@Service
public class UserService {

    private final UserRepository users;
    private final TopicService topicService;
    private final EventErasure eventErasure;
    private final FeedStore feedStore;
    private final TransactionTemplate transaction;

    public UserService(UserRepository users, TopicService topicService, EventErasure eventErasure, FeedStore feedStore,
                       TransactionTemplate transaction) {
        this.users = users;
        this.topicService = topicService;
        this.eventErasure = eventErasure;
        this.feedStore = feedStore;
        this.transaction = transaction;
    }

    @Transactional(readOnly = true)
    public User get(String userId) {
        return users.findById(userId).orElseThrow(() -> ApiException.notFound("User not found"));
    }

    @Transactional(readOnly = true)
    public MeResponse me(String userId) {
        return toResponse(get(userId));
    }

    @Transactional
    public MeResponse update(String userId, UpdateMeRequest request) {
        User user = get(userId);
        user.rename(request.displayName().strip());
        return toResponse(user);
    }

    /**
     * Supprime le compte et les données personnelles : thèmes et user_articles (cascade), événements, clés Redis.
     * La reco est prévenue par un message user_deleted pour effacer reco.user_profiles.
     */
    public void delete(String userId) {
        transaction.executeWithoutResult(status -> {
            User user = get(userId);
            eventErasure.eraseUser(userId);
            users.delete(user);
        });
        // Après le commit, pour ne rien effacer si la suppression échoue. Un remplissage déjà lancé peut recréer le
        // stock : il expire en 6 h et le token du compte supprimé est refusé.
        feedStore.clear(userId);
    }

    private MeResponse toResponse(User user) {
        return new MeResponse(user.getId(), user.getEmail(), user.getDisplayName(), user.getLocale(),
                user.getCreatedAt(), user.getRole(), topicService.listForUser(user.getId()));
    }
}
