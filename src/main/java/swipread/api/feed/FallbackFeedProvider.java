package swipread.api.feed;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import swipread.api.topic.TopicService;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Feed de repli : listes fallback:{slug} des thèmes de l'utilisateur, mélangées et filtrées,
 * complétées par les articles les plus récents en base. Les articles de seen:{userId} sont toujours exclus.
 */
@Component
public class FallbackFeedProvider {

    static final int RECENT_LIMIT = 50;
    static final int RECENT_BATCH = 100;

    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;
    private final TopicService topicService;
    private final FeedStore store;

    public FallbackFeedProvider(StringRedisTemplate redis, JdbcTemplate jdbc, TopicService topicService, FeedStore store) {
        this.redis = redis;
        this.jdbc = jdbc;
        this.topicService = topicService;
        this.store = store;
    }

    /**
     * @param excluded articles à écarter en plus de seen:{userId} (stock actuel, articles déjà retenus)
     */
    public List<String> articleIds(String userId, int count, Collection<String> excluded) {
        Set<String> exclude = new HashSet<>(excluded);
        List<String> lists = new ArrayList<>();
        for (String slug : topicService.slugsForUser(userId)) {
            lists.addAll(Optional.ofNullable(redis.opsForList().range("fallback:" + slug, 0, -1)).orElse(List.of()));
        }
        Collections.shuffle(lists);
        Set<String> candidates = new LinkedHashSet<>(lists);
        candidates.removeAll(exclude);
        candidates.removeAll(store.seenAmong(userId, candidates));

        Set<String> picked = new LinkedHashSet<>(candidates.stream().limit(count).toList());
        if (picked.size() < count) {
            exclude.addAll(picked);
            picked.addAll(mostRecent(userId, Math.min(count - picked.size(), RECENT_LIMIT), exclude));
        }
        return List.copyOf(picked);
    }

    /**
     * Articles les plus récents hors exclus et hors seen, lus par lots (pagination par clé sur published_at, id) :
     * seen n'est interrogé que pour les articles de chaque lot.
     */
    private List<String> mostRecent(String userId, int limit, Set<String> exclude) {
        List<String> found = new ArrayList<>(limit);
        String cursorDate = "infinity";
        String cursorId = "";
        while (found.size() < limit) {
            List<String[]> rows = jdbc.query("""
                            SELECT id, published_at::text FROM app.articles
                            WHERE NOT (id = ANY (?))
                              AND (published_at, id) < (?::timestamptz, ?)
                            ORDER BY published_at DESC, id DESC
                            LIMIT ?
                            """, (rs, n) -> new String[]{rs.getString(1), rs.getString(2)},
                    exclude.toArray(String[]::new), cursorDate, cursorId, RECENT_BATCH);
            List<String> ids = rows.stream().map(row -> row[0]).toList();
            Set<String> seen = store.seenAmong(userId, ids);
            ids.stream().filter(id -> !seen.contains(id)).limit(limit - found.size()).forEach(found::add);
            if (rows.size() < RECENT_BATCH) {
                break;
            }
            cursorDate = rows.getLast()[1];
            cursorId = rows.getLast()[0];
        }
        return found;
    }
}
