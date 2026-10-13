package swipread.api.reco;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import swipread.api.reco.dto.RecommendationRequest;
import swipread.api.reco.dto.RecommendationResponse;

import java.net.http.HttpClient;

@Component
@Profile("!mock-reco")
public class HttpRecommendationClient implements RecommendationClient {

    private static final Logger log = LoggerFactory.getLogger(HttpRecommendationClient.class);

    private final RestClient restClient;

    public HttpRecommendationClient(RestClient.Builder builder, RecoProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(properties.timeout()).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.timeout());
        this.restClient = builder.clone()
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public RecommendationResult recommend(RecommendationRequest request) {
        try {
            return restClient.post()
                    .uri("/v1/recommendations")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .exchange((req, res) -> toResult(request, res.getStatusCode(),
                            res.getStatusCode().is2xxSuccessful() ? res.bodyTo(RecommendationResponse.class) : null));
        } catch (RestClientException e) {
            log.warn("Recommendation service unavailable for {}: {}", request.userId(), e.getMessage());
            return new RecommendationResult.Failure(RecommendationResult.Reason.UNAVAILABLE, e.getMessage());
        }
    }

    private RecommendationResult toResult(RecommendationRequest request, HttpStatusCode status, RecommendationResponse body) {
        if (status.is2xxSuccessful()) {
            if (body == null || body.items() == null) {
                log.warn("Recommendation service returned an empty body for {}", request.userId());
                return new RecommendationResult.Failure(RecommendationResult.Reason.UNAVAILABLE, "Empty body");
            }
            return new RecommendationResult.Success(body);
        }
        return switch (status.value()) {
            case 400 -> {
                log.error("Recommendation request rejected as invalid for {}: {}", request.userId(), request);
                yield new RecommendationResult.Failure(RecommendationResult.Reason.BAD_REQUEST, "400");
            }
            case 404 -> new RecommendationResult.Failure(RecommendationResult.Reason.UNKNOWN_USER, "404");
            default -> {
                log.warn("Recommendation service answered {} for {}", status.value(), request.userId());
                yield new RecommendationResult.Failure(RecommendationResult.Reason.UNAVAILABLE, String.valueOf(status.value()));
            }
        };
    }
}
