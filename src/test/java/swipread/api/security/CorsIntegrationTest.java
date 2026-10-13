package swipread.api.security;

import org.junit.jupiter.api.Test;
import swipread.api.IntegrationTest;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CorsIntegrationTest extends IntegrationTest {

    static final String FRONT = "http://localhost:5173";

    @Test
    void preflightIsAllowedWithoutToken() throws Exception {
        mvc.perform(options("/v1/me")
                        .header("Origin", FRONT)
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", FRONT))
                .andExpect(header().string("Access-Control-Allow-Methods", containsString("PATCH")))
                .andExpect(header().string("Access-Control-Allow-Headers", containsString("authorization")));
    }

    @Test
    void preflightWithCustomHeadersIsAllowed() throws Exception {
        mvc.perform(options("/v1/feed")
                        .header("Origin", FRONT)
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "authorization, x-session-id, x-device"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", FRONT));
    }

    @Test
    void errorResponsesCarryCorsHeaders() throws Exception {
        // Sans en-tête CORS, le front ne pourrait pas lire le 401 pour renvoyer vers la connexion
        mvc.perform(get("/v1/me").header("Origin", FRONT))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Access-Control-Allow-Origin", FRONT));
    }

    @Test
    void unknownOriginIsRejected() throws Exception {
        mvc.perform(options("/v1/me")
                        .header("Origin", "https://evil.example")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
}
