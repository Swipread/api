package swipread.api.reco;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.StreamMessageListenerContainer.StreamMessageListenerContainerOptions;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.UUID;

/**
 * Côté « reco » du mock (contrat, Faux service de reco) : consomme le stream events et se contente de logger chaque
 * message. Groupe de consommateurs dédié : les messages lus sont acquittés, la vraie reco aura son propre groupe.
 */
@Component
@Profile("mock-reco")
class MockEventsConsumer implements SmartLifecycle {

    static final String STREAM = "events";
    static final String GROUP = "mock-reco";

    private static final Logger log = LoggerFactory.getLogger(MockEventsConsumer.class);

    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final StreamMessageListenerContainer<String, MapRecord<String, String, String>> container;
    private volatile boolean running;
    private volatile Consumer consumer;

    MockEventsConsumer(RedisConnectionFactory connectionFactory, StringRedisTemplate redis, JsonMapper json) {
        this.redis = redis;
        this.json = json;
        this.container = StreamMessageListenerContainer.create(connectionFactory,
                StreamMessageListenerContainerOptions.builder()
                        .pollTimeout(Duration.ofSeconds(1))
                        .errorHandler(e -> log.warn("Mock reco: events stream read failed", e))
                        .build());
    }

    @Override
    public void start() {
        createGroupIfMissing();
        // Un nom par instance : plusieurs API en dev partagent le même groupe sans relire les mêmes messages
        consumer = Consumer.from(GROUP, "mock-" + UUID.randomUUID());
        container.receiveAutoAck(consumer, StreamOffset.create(STREAM, ReadOffset.lastConsumed()), this::handle);
        container.start();
        running = true;
    }

    @Override
    public void stop() {
        container.stop();
        running = false;
        // Sans quoi chaque redémarrage laisse un consommateur inactif dans le groupe
        try {
            redis.opsForStream().deleteConsumer(STREAM, consumer);
        } catch (RuntimeException e) {
            log.debug("Mock reco: could not remove consumer {}", consumer.getName(), e);
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    void handle(MapRecord<String, String, String> record) {
        String data = record.getValue().get("data");
        try {
            JsonNode message = json.readTree(data);
            log.info("Mock reco received {} for {} (article {}, event {})", text(message, "type"),
                    text(message, "userId"), text(message, "articleId"), text(message, "eventId"));
        } catch (RuntimeException e) {
            log.warn("Mock reco: unreadable message {} on the events stream: {}", record.getId(), data);
        }
    }

    void createGroupIfMissing() {
        try {
            // XGROUP CREATE events mock-reco $ MKSTREAM : seuls les messages publiés après le démarrage sont lus
            redis.opsForStream().createGroup(STREAM, ReadOffset.latest(), GROUP);
        } catch (RedisSystemException e) {
            if (!isBusyGroup(e)) {
                throw e;
            }
        }
    }

    // BUSYGROUP (groupe déjà créé, par exemple au démarrage précédent) est porté par la cause, pas par l'exception
    private static boolean isBusyGroup(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t.getMessage() != null && t.getMessage().contains("BUSYGROUP")) {
                return true;
            }
        }
        return false;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "-" : value.asString();
    }
}
