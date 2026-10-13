package swipread.api;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Base commune : même configuration partout pour que le contexte et les conteneurs soient réutilisés.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
public abstract class IntegrationTest {

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JsonMapper json;

    protected record RegisteredUser(String userId, String token) {
        public String bearer() {
            return "Bearer " + token;
        }
    }

    protected RegisteredUser registerUser() throws Exception {
        String email = "user-" + UUID.randomUUID() + "@example.org";
        String body = mvc.perform(post("/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "password123", "displayName": "Test"}
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode node = json.readTree(body);
        return new RegisteredUser(node.get("userId").asString(), node.get("accessToken").asString());
    }

    /**
     * Compte administrateur du seed (db/seed/R__3_admin_user.sql).
     */
    protected RegisteredUser loginAdmin() throws Exception {
        String body = mvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "admin@swipread.dev", "password": "password123"}
                                """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode node = json.readTree(body);
        return new RegisteredUser(node.get("userId").asString(), node.get("accessToken").asString());
    }
}
