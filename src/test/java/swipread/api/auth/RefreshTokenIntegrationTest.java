package swipread.api.auth;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.ResultActions;
import swipread.api.IntegrationTest;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RefreshTokenIntegrationTest extends IntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void loginSetsAnHttpOnlyCookieAndAShortAccessToken() throws Exception {
        String email = newEmail();
        register(email);
        MockHttpServletResponse response = mvc.perform(post("/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(email)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andReturn().getResponse();

        String setCookie = response.getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).startsWith("swipread_refresh=").contains("HttpOnly", "SameSite=Strict", "Path=/v1/auth",
                "Max-Age=2592000");
        // Profil test servi en HTTP
        assertThat(setCookie).doesNotContain("Secure");
    }

    @Test
    void refreshRotatesTheCookieAndReturnsAWorkingAccessToken() throws Exception {
        String first = register(newEmail());

        MockHttpServletResponse response = refresh(first).andExpect(status().isOk()).andReturn().getResponse();
        String second = cookieValue(response);
        String accessToken = json.readTree(response.getContentAsString()).get("accessToken").asString();

        assertThat(second).isNotBlank().isNotEqualTo(first);
        mvc.perform(get("/v1/me").header("Authorization", "Bearer " + accessToken)).andExpect(status().isOk());
        refresh(second).andExpect(status().isOk());
    }

    @Test
    void replacedTokenStillWorksDuringTheGracePeriodWithoutNewCookie() throws Exception {
        String first = register(newEmail());
        String second = cookieValue(refresh(first).andReturn().getResponse());

        // Un autre onglet présente l'ancien token juste après
        MockHttpServletResponse concurrent = refresh(first).andExpect(status().isOk()).andReturn().getResponse();

        assertThat(concurrent.getHeader(HttpHeaders.SET_COOKIE)).isNull();
        refresh(second).andExpect(status().isOk());
    }

    @Test
    void reuseAfterTheGracePeriodRevokesTheWholeSession() throws Exception {
        String first = register(newEmail());
        String second = cookieValue(refresh(first).andReturn().getResponse());
        jdbc.update("UPDATE app.refresh_tokens SET revoked_at = revoked_at - interval '1 minute' WHERE token_hash = ?",
                RefreshTokenService.hash(first));

        refresh(first).andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));
        // Le token légitime de la même famille est révoqué aussi
        refresh(second).andExpect(status().isUnauthorized());
    }

    @Test
    void missingUnknownOrExpiredTokensAreRejected() throws Exception {
        mvc.perform(post("/v1/auth/refresh")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Invalid refresh token"));
        refresh("not-a-token").andExpect(status().isUnauthorized());

        String token = register(newEmail());
        jdbc.update("UPDATE app.refresh_tokens SET expires_at = now() - interval '1 second' WHERE token_hash = ?",
                RefreshTokenService.hash(token));
        refresh(token).andExpect(status().isUnauthorized());
    }

    @Test
    void logoutRevokesTheTokenAndClearsTheCookie() throws Exception {
        String token = register(newEmail());

        mvc.perform(post("/v1/auth/logout").cookie(new Cookie(RefreshCookies.NAME, token)))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));
        refresh(token).andExpect(status().isUnauthorized());
        // Sans cookie : rien à révoquer, réponse identique
        mvc.perform(post("/v1/auth/logout")).andExpect(status().isNoContent());
    }

    @Test
    void deletingTheAccountDeletesItsRefreshTokens() throws Exception {
        String email = newEmail();
        MockHttpServletResponse registered = mvc.perform(post("/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(registration(email)))
                .andReturn().getResponse();
        String userId = json.readTree(registered.getContentAsString()).get("userId").asString();
        String accessToken = json.readTree(registered.getContentAsString()).get("accessToken").asString();

        mvc.perform(delete("/v1/me").header("Authorization", "Bearer " + accessToken)).andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM app.refresh_tokens WHERE user_id = ?", Long.class, userId))
                .isZero();
        refresh(cookieValue(registered)).andExpect(status().isUnauthorized());
    }

    @Test
    void corsAllowsTheFrontendToSendTheCookie() throws Exception {
        mvc.perform(options("/v1/auth/refresh")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"));
    }

    @Test
    void refreshTokensAreStoredHashed() throws Exception {
        String token = register(newEmail());
        List<String> stored = jdbc.queryForList("SELECT token_hash FROM app.refresh_tokens WHERE token_hash = ? OR token_hash = ?",
                String.class, token, RefreshTokenService.hash(token));
        assertThat(stored).containsExactly(RefreshTokenService.hash(token));
    }

    private ResultActions refresh(String token) throws Exception {
        return mvc.perform(post("/v1/auth/refresh").cookie(new Cookie(RefreshCookies.NAME, token)));
    }

    /**
     * @return la valeur du cookie de refresh posé à l'inscription
     */
    private String register(String email) throws Exception {
        return cookieValue(mvc.perform(post("/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(registration(email)))
                .andExpect(status().isCreated())
                .andReturn().getResponse());
    }

    private static String cookieValue(MockHttpServletResponse response) {
        String header = response.getHeader(HttpHeaders.SET_COOKIE);
        assertThat(header).startsWith(RefreshCookies.NAME + "=");
        return header.substring(RefreshCookies.NAME.length() + 1, header.indexOf(';'));
    }

    private static String newEmail() {
        return "refresh-" + UUID.randomUUID() + "@example.org";
    }

    private static String registration(String email) {
        return """
                {"email": "%s", "password": "password123", "displayName": "Test"}
                """.formatted(email);
    }

    private static String credentials(String email) {
        return """
                {"email": "%s", "password": "password123"}
                """.formatted(email);
    }
}
