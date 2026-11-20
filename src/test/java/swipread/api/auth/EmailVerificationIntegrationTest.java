package swipread.api.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;
import swipread.api.IntegrationTest;
import swipread.api.Mailpit;

import java.time.Duration;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class EmailVerificationIntegrationTest extends IntegrationTest {

    private static final String SUBJECT = "Confirmez votre adresse e-mail";

    @Autowired
    private JdbcTemplate jdbc;

    @Value("${mailpit.api-url}")
    private String mailpitUrl;

    private Mailpit mailpit;

    @BeforeEach
    void setUp() {
        mailpit = new Mailpit(mailpitUrl);
    }

    @Test
    void signUpSendsALinkThatConfirmsTheAddressOnce() throws Exception {
        RegisteredUser user = registerUser();
        String email = email(user);
        me(user).andExpect(jsonPath("$.emailVerified").value(false));

        String text = mailpit.awaitText(email, SUBJECT);
        assertThat(text).contains("Bonjour Test,", "Confirmer mon adresse : https://swipread.test/verifier-email?token=");

        // Public : pas de jeton d'accès
        verify(Mailpit.token(text)).andExpect(status().isNoContent());
        me(user).andExpect(jsonPath("$.emailVerified").value(true));
        verify(Mailpit.token(text)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Invalid or expired link"));
    }

    @Test
    void aNewLinkReplacesTheOldOneAndIsLimited() throws Exception {
        RegisteredUser user = registerUser();
        String email = email(user);
        String first = Mailpit.token(mailpit.awaitText(email, SUBJECT));

        // Le lien de l'inscription a moins d'une minute
        resend(user).andExpect(status().isTooManyRequests());

        sentMinutesAgo(user, 2);
        resend(user).andExpect(status().isAccepted());
        await(() -> mailpit.count(email) == 2);

        verify(first).andExpect(status().isBadRequest());
        me(user).andExpect(jsonPath("$.emailVerified").value(false));

        // Cinq liens dans la journée : plus aucun
        sentMinutesAgo(user, 2);
        for (int i = 0; i < 3; i++) {
            jdbc.update("""
                    INSERT INTO app.user_tokens (id, user_id, purpose, token_hash, created_at, expires_at)
                    VALUES (gen_random_uuid(), ?, 'verify_email', md5(random()::text), now() - interval '2 hours', now())
                    """, user.userId());
        }
        resend(user).andExpect(status().isTooManyRequests());
    }

    @Test
    void anExpiredLinkIsRefusedAndAVerifiedAddressNeedsNoNewLink() throws Exception {
        RegisteredUser user = registerUser();
        String token = Mailpit.token(mailpit.awaitText(email(user), SUBJECT));
        jdbc.update("UPDATE app.user_tokens SET expires_at = now() - interval '1 second' WHERE user_id = ?", user.userId());

        verify(token).andExpect(status().isBadRequest());

        jdbc.update("UPDATE app.users SET email_verified_at = now() WHERE id = ?", user.userId());
        resend(user).andExpect(status().isConflict());
    }

    @Test
    void anUnknownOrMalformedLinkIsRefused() throws Exception {
        verify("not-a-token").andExpect(status().isBadRequest());
        mvc.perform(post("/v1/auth/email/verify").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void changingThePasswordSendsANotice() throws Exception {
        RegisteredUser user = registerUser();
        String email = email(user);

        mvc.perform(put("/v1/auth/password")
                        .header("Authorization", user.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\": \"password123\", \"newPassword\": \"another-one-123\"}"))
                .andExpect(status().isNoContent());

        assertThat(mailpit.awaitText(email, "Votre mot de passe a été modifié"))
                .contains("Bonjour Test,", "Vos autres appareils ont été déconnectés", "Mot de passe oublié");
    }

    private ResultActions verify(String token) throws Exception {
        return mvc.perform(post("/v1/auth/email/verify").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\": \"" + token + "\"}"));
    }

    private ResultActions resend(RegisteredUser user) throws Exception {
        return mvc.perform(post("/v1/me/verification-email").header("Authorization", user.bearer()));
    }

    private ResultActions me(RegisteredUser user) throws Exception {
        return mvc.perform(get("/v1/me").header("Authorization", user.bearer())).andExpect(status().isOk());
    }

    private void sentMinutesAgo(RegisteredUser user, int minutes) {
        jdbc.update("UPDATE app.user_tokens SET created_at = created_at - make_interval(mins => ?) WHERE user_id = ?",
                minutes, user.userId());
    }

    private String email(RegisteredUser user) {
        return jdbc.queryForObject("SELECT email FROM app.users WHERE id = ?", String.class, user.userId());
    }

    private static void await(Callable<Boolean> condition) {
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).until(condition);
    }
}
