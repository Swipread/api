package swipread.api.reco;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;

@Validated
@ConfigurationProperties("swipread.reco")
public record RecoProperties(@NotNull URI baseUrl, @NotNull Duration timeout) {
}
