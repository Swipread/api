package swipread.api.feed;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Accès aux clés Redis du feed décrites dans le contrat (feed, req, lock, seen, meta, ver).
 * <p>
 * feed:{userId}:ver est incrémenté avant toute invalidation ou tout vidage du stock. Un remplissage note la version
 * à son lancement et son lot n'est ajouté que si elle n'a pas changé : un lot calculé avant un événement fort ou un
 * changement de thèmes est ainsi écarté.
 */
@Component
public class FeedStore {

    static final Duration FEED_TTL = Duration.ofHours(6);
    static final Duration LOCK_TTL = Duration.ofSeconds(5);
    static final Duration SEEN_TTL = Duration.ofDays(30);

    // Garde les n premiers éléments du stock et purge les métadonnées des éléments retirés
    private static final RedisScript<Long> TRIM_SCRIPT = RedisScript.of("""
            redis.call('LTRIM', KEYS[1], 0, tonumber(ARGV[1]) - 1)
            local kept = redis.call('LRANGE', KEYS[1], 0, -1)
            local values = {}
            if #kept > 0 then values = redis.call('HMGET', KEYS[2], unpack(kept)) end
            redis.call('DEL', KEYS[2])
            for i, id in ipairs(kept) do
              if values[i] then redis.call('HSET', KEYS[2], id, values[i]) end
            end
            if #kept > 0 then redis.call('EXPIRE', KEYS[2], tonumber(ARGV[2])) end
            return #kept
            """, Long.class);

    // KEYS : feed, meta, req, ver. ARGV : version attendue, TTL, requestId ('' si aucun), puis paires id, origine
    private static final RedisScript<Long> APPEND_SCRIPT = RedisScript.of("""
            if (redis.call('GET', KEYS[4]) or '0') ~= ARGV[1] then return 0 end
            for i = 4, #ARGV, 2 do
              redis.call('RPUSH', KEYS[1], ARGV[i])
              redis.call('HSET', KEYS[2], ARGV[i], ARGV[i + 1])
            end
            if #ARGV >= 4 then
              redis.call('EXPIRE', KEYS[1], tonumber(ARGV[2]))
              redis.call('EXPIRE', KEYS[2], tonumber(ARGV[2]))
            end
            if ARGV[3] ~= '' then redis.call('SET', KEYS[3], ARGV[3], 'EX', tonumber(ARGV[2])) end
            return 1
            """, Long.class);

    private static final RedisScript<Long> UNLOCK_SCRIPT = RedisScript.of("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;

    public FeedStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    static String feedKey(String userId) {
        return "feed:" + userId;
    }

    static String requestKey(String userId) {
        return "feed:" + userId + ":req";
    }

    static String lockKey(String userId) {
        return "feed:" + userId + ":lock";
    }

    static String metaKey(String userId) {
        return "feed:" + userId + ":meta";
    }

    static String versionKey(String userId) {
        return "feed:" + userId + ":ver";
    }

    static String seenKey(String userId) {
        return "seen:" + userId;
    }

    /**
     * LPOP feed:{userId} n, avec l'origine de chaque article retiré.
     */
    public List<StockItem> pop(String userId, int count) {
        List<String> ids = Optional.ofNullable(redis.opsForList().leftPop(feedKey(userId), count)).orElse(List.of());
        if (ids.isEmpty()) {
            return List.of();
        }
        List<Object> fields = new ArrayList<>(ids);
        List<Object> origins = redis.opsForHash().multiGet(metaKey(userId), fields);
        redis.opsForHash().delete(metaKey(userId), fields.toArray());
        List<StockItem> items = new ArrayList<>(ids.size());
        for (int i = 0; i < ids.size(); i++) {
            items.add(new StockItem(ids.get(i), FeedOrigin.decode(origins.get(i))));
        }
        return items;
    }

    public long size(String userId) {
        return Optional.ofNullable(redis.opsForList().size(feedKey(userId))).orElse(0L);
    }

    public List<String> stock(String userId) {
        return Optional.ofNullable(redis.opsForList().range(feedKey(userId), 0, -1)).orElse(List.of());
    }

    /**
     * Version actuelle du stock (0 si jamais invalidé), à lire au lancement d'un remplissage.
     */
    public long version(String userId) {
        String value = redis.opsForValue().get(versionKey(userId));
        return value == null ? 0 : Long.parseLong(value);
    }

    /**
     * RPUSH des nouveaux articles, seulement si le stock est toujours à la version expectedVersion ; requestId vaut
     * null si le lot ne vient que du repli.
     *
     * @return false si le stock a été invalidé ou vidé depuis : rien n'est écrit
     */
    public boolean append(String userId, List<StockItem> items, String requestId, long expectedVersion) {
        List<String> args = new ArrayList<>();
        args.add(String.valueOf(expectedVersion));
        args.add(String.valueOf(FEED_TTL.toSeconds()));
        args.add(requestId == null ? "" : requestId);
        for (StockItem item : items) {
            args.add(item.articleId());
            args.add(item.origin().encode());
        }
        Long appended = redis.execute(APPEND_SCRIPT,
                List.of(feedKey(userId), metaKey(userId), requestKey(userId), versionKey(userId)),
                args.toArray());
        return Long.valueOf(1).equals(appended);
    }

    /**
     * LTRIM feed:{userId} 0 keep-1 (invalidation sur événement fort).
     */
    public void trim(String userId, int keep) {
        bumpVersion(userId);
        redis.execute(TRIM_SCRIPT, List.of(feedKey(userId), metaKey(userId)),
                String.valueOf(keep), String.valueOf(FEED_TTL.toSeconds()));
    }

    public void remove(String userId, String articleId) {
        redis.opsForList().remove(feedKey(userId), 0, articleId);
        redis.opsForHash().delete(metaKey(userId), articleId);
    }

    public void markSeen(String userId, List<String> articleIds) {
        if (articleIds.isEmpty()) {
            return;
        }
        redis.opsForSet().add(seenKey(userId), articleIds.toArray(String[]::new));
        redis.expire(seenKey(userId), SEEN_TTL);
    }

    /**
     * SMISMEMBER seen:{userId} : ceux des candidats déjà servis, sans lire tout le Set (jusqu'à 30 jours d'historique).
     */
    public Set<String> seenAmong(String userId, Collection<String> candidates) {
        if (candidates.isEmpty()) {
            return Set.of();
        }
        Map<Object, Boolean> membership = redis.opsForSet().isMember(seenKey(userId), candidates.toArray());
        if (membership == null) {
            return Set.of();
        }
        Set<String> seen = new HashSet<>();
        membership.forEach((id, member) -> {
            if (Boolean.TRUE.equals(member)) {
                seen.add((String) id);
            }
        });
        return seen;
    }

    /**
     * Supprime toutes les clés de l'utilisateur (suppression de compte).
     */
    public void clear(String userId) {
        // Effacement complet, version comprise : un remplissage encore en cours peut recréer un stock, qui expire
        // en 6 h et que plus personne ne lit (le token du compte supprimé est refusé)
        redis.delete(List.of(feedKey(userId), requestKey(userId), lockKey(userId), metaKey(userId), seenKey(userId),
                versionKey(userId)));
    }

    /**
     * Vide le stock (feed, req, meta) en gardant seen:{userId} et le verrou d'un remplissage en cours.
     */
    public void resetStock(String userId) {
        bumpVersion(userId);
        redis.delete(List.of(feedKey(userId), requestKey(userId), metaKey(userId)));
    }

    // Avant la modification : un ajout qui passerait entre les deux est déjà refusé
    private void bumpVersion(String userId) {
        redis.opsForValue().increment(versionKey(userId));
        redis.expire(versionKey(userId), FEED_TTL);
    }

    /**
     * Pose feed:{userId}:lock s'il est libre ; renvoie le jeton à présenter pour le libérer.
     */
    public Optional<String> tryLock(String userId) {
        String token = UUID.randomUUID().toString();
        Boolean acquired = redis.opsForValue().setIfAbsent(lockKey(userId), token, LOCK_TTL);
        return Boolean.TRUE.equals(acquired) ? Optional.of(token) : Optional.empty();
    }

    public void unlock(String userId, String token) {
        redis.execute(UNLOCK_SCRIPT, List.of(lockKey(userId)), token);
    }
}
