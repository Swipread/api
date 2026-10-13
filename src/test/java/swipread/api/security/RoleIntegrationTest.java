package swipread.api.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import swipread.api.IntegrationTest;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RoleIntegrationTest extends IntegrationTest {

    // Aucune route admin n'existe encore : un 404 prouve que l'autorisation est passée
    static final String ADMIN_ROUTE = "/v1/admin/ping";

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void newUserIsReaderAndCannotReachAdminRoutes() throws Exception {
        RegisteredUser user = registerUser();

        mvc.perform(get("/v1/me").header("Authorization", user.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("READER"));
        mvc.perform(get(ADMIN_ROUTE).header("Authorization", user.bearer()))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    void adminRoutesRequireAToken() throws Exception {
        mvc.perform(get(ADMIN_ROUTE))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void seededAdminReachesAdminRoutes() throws Exception {
        String token = login("admin@swipread.dev");

        mvc.perform(get("/v1/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"));
        mvc.perform(get(ADMIN_ROUTE).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void promotionTakesEffectWithTheNextToken() throws Exception {
        String email = "promoted-" + UUID.randomUUID() + "@example.org";
        String before = register(email);
        jdbc.update("UPDATE app.users SET role = 'ADMIN' WHERE email = ?", email);

        mvc.perform(get(ADMIN_ROUTE).header("Authorization", "Bearer " + before))
                .andExpect(status().isForbidden());
        mvc.perform(get(ADMIN_ROUTE).header("Authorization", "Bearer " + login(email)))
                .andExpect(status().isNotFound());
    }

    private String register(String email) throws Exception {
        return token(post("/v1/auth/register"), """
                {"email": "%s", "password": "password123", "displayName": "Promoted"}
                """.formatted(email));
    }

    private String login(String email) throws Exception {
        return token(post("/v1/auth/login"), """
                {"email": "%s", "password": "password123"}
                """.formatted(email));
    }

    private String token(MockHttpServletRequestBuilder request, String body) throws Exception {
        String response = mvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("accessToken").asString();
    }
}
