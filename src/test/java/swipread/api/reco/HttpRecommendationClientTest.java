package swipread.api.reco;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import swipread.api.reco.dto.RecommendationContext;
import swipread.api.reco.dto.RecommendationRequest;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class HttpRecommendationClientTest {

    private HttpServer server;
    private final AtomicReference<String> receivedBody = new AtomicReference<>();
    private volatile int status;
    private volatile String responseBody;
    private volatile long delayMs;
    private HttpRecommendationClient client;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/recommendations", exchange -> {
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
        URI baseUrl = URI.create("http://localhost:" + server.getAddress().getPort());
        client = new HttpRecommendationClient(RestClient.builder(), new RecoProperties(baseUrl, Duration.ofMillis(300)));
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void parsesSuccessfulResponseAndSendsContractPayload() {
        respond(200, """
                {"requestId": "r_7c21", "modelVersion": "content-v1", "generatedAt": "2026-10-12T10:40:00Z",
                 "items": [{"articleId": "a_387", "score": 0.91, "reason": "similar"}]}
                """);

        RecommendationResult result = client.recommend(request());

        assertThat(result).isInstanceOfSatisfying(RecommendationResult.Success.class, success -> {
            assertThat(success.response().requestId()).isEqualTo("r_7c21");
            assertThat(success.response().items()).singleElement()
                    .satisfies(item -> assertThat(item.articleId()).isEqualTo("a_387"));
        });
        assertThat(receivedBody.get())
                .contains("\"userId\":\"u_42\"", "\"count\":50", "\"exclude\":[\"a_101\"]", "\"sessionId\":\"s_8f3a\"");
    }

    @Test
    void mapsErrorStatuses() {
        respond(400, "");
        assertThat(failureReason()).isEqualTo(RecommendationResult.Reason.BAD_REQUEST);
        respond(404, "");
        assertThat(failureReason()).isEqualTo(RecommendationResult.Reason.UNKNOWN_USER);
        respond(503, "");
        assertThat(failureReason()).isEqualTo(RecommendationResult.Reason.UNAVAILABLE);
    }

    @Test
    void timesOutAfter300Ms() {
        respond(200, "{\"requestId\": \"r_1\", \"items\": []}");
        delayMs = 1_000;

        long start = System.nanoTime();
        RecommendationResult.Reason reason = failureReason();
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertThat(reason).isEqualTo(RecommendationResult.Reason.UNAVAILABLE);
        assertThat(elapsedMs).isLessThan(900);
    }

    @Test
    void unreachableServiceIsUnavailable() {
        server.stop(0);
        assertThat(failureReason()).isEqualTo(RecommendationResult.Reason.UNAVAILABLE);
    }

    private RecommendationResult.Reason failureReason() {
        RecommendationResult result = client.recommend(request());
        assertThat(result).isInstanceOf(RecommendationResult.Failure.class);
        return ((RecommendationResult.Failure) result).reason();
    }

    private void respond(int status, String body) {
        this.status = status;
        this.responseBody = body;
    }

    private static RecommendationRequest request() {
        return new RecommendationRequest("u_42", 50, List.of("a_101"), new RecommendationContext("s_8f3a", "fr-FR", "mobile"));
    }
}
