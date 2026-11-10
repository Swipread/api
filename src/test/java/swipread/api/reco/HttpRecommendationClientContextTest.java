package swipread.api.reco;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import swipread.api.TestcontainersConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Production wiring: without the mock-reco profile, the application must start with the HTTP recommender client.
 * The test profile group normally includes mock-reco; it is emptied here, which costs a dedicated context.
 */
@SpringBootTest(properties = "spring.profiles.group.test=")
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class HttpRecommendationClientContextTest {

    @Autowired
    private RecommendationClient client;

    @Test
    void startsWithTheHttpClientOutsideTheMockProfile() {
        assertThat(client).isInstanceOf(HttpRecommendationClient.class);
    }
}
