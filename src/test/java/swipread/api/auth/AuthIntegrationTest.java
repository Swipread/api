package swipread.api.auth;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import swipread.api.IntegrationTest;

import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthIntegrationTest extends IntegrationTest {

    @Test
    void registerThenLoginReturnsTokens() throws Exception {
        String email = "Alice-" + UUID.randomUUID() + "@Example.org";
        mvc.perform(post("/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "password123", "displayName": "Alice"}
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(startsWith("u_")))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.accessToken").isNotEmpty());

        mvc.perform(post("/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "password123"}
                                """.formatted(email.toLowerCase())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
    }

    @Test
    void duplicateEmailIsConflict() throws Exception {
        String body = """
                {"email": "dup-%s@example.org", "password": "password123", "displayName": "Dup"}
                """.formatted(UUID.randomUUID());
        mvc.perform(post("/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mvc.perform(post("/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void invalidRegistrationReturnsFieldErrors() throws Exception {
        mvc.perform(post("/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "not-an-email", "password": "short", "displayName": ""}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors.length()").value(3));
    }

    @Test
    void validationErrorsAreInEnglishWithAConstraintCode() throws Exception {
        // Le navigateur du front envoie Accept-Language: fr ; Hibernate Validator a un catalogue français
        mvc.perform(post("/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .header("Accept-Language", "fr-FR,fr;q=0.9")
                        .content("""
                                {"email": "not-an-email", "password": "short", "displayName": ""}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'email')].code").value(contains("Email")))
                .andExpect(jsonPath("$.errors[?(@.field == 'password')].code").value(contains("Size")))
                .andExpect(jsonPath("$.errors[?(@.field == 'displayName')].code").value(contains("NotBlank")))
                .andExpect(jsonPath("$.errors[?(@.field == 'displayName')].message").value(contains("must not be blank")));
    }

    @Test
    void wrongPasswordIsUnauthorized() throws Exception {
        String email = "bob-" + UUID.randomUUID() + "@example.org";
        mvc.perform(post("/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "password123", "displayName": "Bob"}
                                """.formatted(email)))
                .andExpect(status().isCreated());

        expectLoginRejected(email, "wrong-password");
        expectLoginRejected("nobody@example.org", "password123");
    }

    private void expectLoginRejected(String email, String password) throws Exception {
        mvc.perform(post("/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "%s"}
                                """.formatted(email, password)))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void protectedEndpointRequiresValidToken() throws Exception {
        mvc.perform(get("/v1/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", startsWith("Bearer")))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
        mvc.perform(get("/v1/me").header("Authorization", "Bearer not.a.jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }
}
