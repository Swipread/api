package swipread.api.feed;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import swipread.api.IntegrationTest;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FeedStoreIntegrationTest extends IntegrationTest {

    @Autowired
    private FeedStore store;

    @Autowired
    private StringRedisTemplate redis;

    private final String userId = "u_store_" + UUID.randomUUID();

    @Test
    void appendWritesStockMetaAndRequestAtTheExpectedVersion() {
        long version = store.version(userId);
        assertThat(version).isZero();

        assertThat(store.append(userId, items("a_1", "a_2"), "r_1", version)).isTrue();

        assertThat(store.stock(userId)).containsExactly("a_1", "a_2");
        assertThat(redis.opsForHash().size("feed:" + userId + ":meta")).isEqualTo(2);
        assertThat(redis.opsForValue().get("feed:" + userId + ":req")).isEqualTo("r_1");
        assertThat(redis.getExpire("feed:" + userId)).isPositive();
    }

    @Test
    void batchComputedBeforeAnInvalidationIsDiscarded() {
        store.append(userId, items("a_1", "a_2", "a_3"), null, 0);
        long started = store.version(userId);

        store.trim(userId, 1);

        assertThat(store.version(userId)).isEqualTo(started + 1);
        assertThat(store.append(userId, items("a_old"), "r_old", started)).isFalse();
        assertThat(store.stock(userId)).containsExactly("a_1");
        assertThat(redis.hasKey("feed:" + userId + ":req")).isFalse();
        assertThat(store.append(userId, items("a_new"), "r_new", store.version(userId))).isTrue();
        assertThat(store.stock(userId)).containsExactly("a_1", "a_new");
    }

    @Test
    void topicsResetAlsoDiscardsRunningRefills() {
        long started = store.version(userId);
        store.resetStock(userId);
        assertThat(store.append(userId, items("a_old"), null, started)).isFalse();
        assertThat(redis.hasKey("feed:" + userId)).isFalse();
    }

    @Test
    void accountDeletionErasesTheVersionToo() {
        store.trim(userId, 0);
        store.clear(userId);
        assertThat(redis.keys("*" + userId + "*")).isEmpty();
    }

    private static List<StockItem> items(String... ids) {
        return Arrays.stream(ids).map(id -> new StockItem(id, FeedOrigin.FALLBACK)).toList();
    }
}
