package swipread.api.feed;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("swipread.feed")
public record FeedProperties(@Positive int pageSize, @Positive int stockSize, @Positive int refillThreshold) {
}
