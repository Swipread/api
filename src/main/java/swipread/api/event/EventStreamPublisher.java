package swipread.api.event;

import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;

/**
 * XADD events MINID ~ (now - 7 j) * data {json}. Appelé seulement par {@link OutboxRelay}.
 */
@Component
class EventStreamPublisher {

    static final String STREAM = "events";
    static final String FIELD = "data";
    static final Duration RETENTION = Duration.ofDays(7);

    private final StringRedisTemplate redis;
    private final Clock clock;

    EventStreamPublisher(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    /**
     * @param message JSON du message au format du contrat
     */
    void publish(String message) {
        long minId = clock.instant().minus(RETENTION).toEpochMilli();
        XAddOptions options = XAddOptions.none().minId(RecordId.of(minId, 0)).approximateTrimming(true);
        redis.opsForStream().add(
                StreamRecords.string(Map.of(FIELD, message)).withStreamKey(STREAM),
                options);
    }
}
