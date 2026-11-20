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
 * complétées par les articles les plus récents en base. Seuls les articles des sources actives sont servis,
 * sans limite d'âge ; ceux de seen:{userId} et de app.user_articles (déjà vus ou masqués) sont toujours exclus.
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
        // Une liste fallback:{slug} peut garder l'article d'une source désactivée jusqu'au passage du worker
        candidates.retainAll(servable(userId, candidates));

        Set<String> picked = new LinkedHashSet<>(candidates.stream().limit(count).toList());
        if (picked.size() < count) {
            exclude.addAll(picked);
            picked.addAll(mostRecent(userId, Math.min(count - picked.size(), RECENT_LIMIT), exclude));
        }
        return List.copyOf(picked);
    }

    /**
     * Ceux des candidats qui viennent d'une source active et que l'utilisateur n'a jamais vus ni masqués.
     */
    private Set<String> servable(String userId, Collection<String> candidates) {
        if (candidates.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.queryForList("""
                SELECT a.id FROM app.articles a
                JOIN app.sources s ON s.id = a.source_id AND s.active
                WHERE a.id = ANY (?)
                  AND NOT EXISTS (SELECT 1 FROM app.user_articles ua WHERE ua.user_id = ? AND ua.article_id = a.id)
                """, String.class, candidates.toArray(String[]::new), userId));
    }

    /**
     * Articles les plus récents des sources actives, hors exclus, hors app.user_articles et hors seen, lus par lots
     * (pagination par clé sur published_at, id) : seen n'est interrogé que pour les articles de chaque lot.
     */
    private List<String> mostRecent(String userId, int limit, Set<String> exclude) {
        List<String> found = new ArrayList<>(limit);
        String cursorDate = "infinity";
        String cursorId = "";
        while (found.size() < limit) {
            List<String[]> rows = jdbc.query("""
                            SELECT a.id, a.published_at::text FROM app.articles a
                            JOIN app.sources s ON s.id = a.source_id AND s.active
                            WHERE NOT (a.id = ANY (?))
                              AND NOT EXISTS (SELECT 1 FROM app.user_articles ua
                                              WHERE ua.user_id = ? AND ua.article_id = a.id)
                              AND (a.published_at, a.id) < (?::timestamptz, ?)
                            ORDER BY a.published_at DESC, a.id DESC
                            LIMIT ?
                            """, (rs, n) -> new String[]{rs.getString(1), rs.getString(2)},
                    exclude.toArray(String[]::new), userId, cursorDate, cursorId, RECENT_BATCH);
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
