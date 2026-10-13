package swipread.api.reco;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import swipread.api.reco.dto.RecommendationRequest;
import swipread.api.reco.dto.RecommendationResponse;
import swipread.api.reco.dto.RecommendedItem;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Faux service de reco conforme au contrat : articles tirés au hasard hors exclude,
 * score 0.5, reason explore, modelVersion mock. Comme la vraie reco, il ignore aussi
 * les articles déjà présents dans l'historique de l'utilisateur.
 */
@Component
@Profile("mock-reco")
public class MockRecommendationClient implements RecommendationClient {

    static final String MODEL_VERSION = "mock";

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public MockRecommendationClient(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public RecommendationResult recommend(RecommendationRequest request) {
        String[] exclude = Optional.ofNullable(request.exclude()).orElse(List.of()).toArray(String[]::new);
        List<RecommendedItem> items = jdbc.queryForList("""
                        SELECT a.id FROM app.articles a
                        WHERE NOT (a.id = ANY (?))
                          AND NOT EXISTS (SELECT 1 FROM app.user_articles ua
                                          WHERE ua.user_id = ? AND ua.article_id = a.id)
                        ORDER BY random()
                        LIMIT ?
                        """, String.class, exclude, request.userId(), request.count())
                .stream()
                .map(id -> new RecommendedItem(id, 0.5, "explore"))
                .toList();
        String requestId = "r_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return new RecommendationResult.Success(new RecommendationResponse(requestId, MODEL_VERSION, clock.instant(), items));
    }
}
