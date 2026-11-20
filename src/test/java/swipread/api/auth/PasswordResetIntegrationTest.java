package swipread.api.auth;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;
import swipread.api.IntegrationTest;
import swipread.api.Mailpit;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PasswordResetIntegrationTest extends IntegrationTest {

    private static final String SUBJECT = "Choisissez un nouveau mot de passe";
    private static final String OLD = "password123";
    private static final String NEW = "a-new-password-456";

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
    void theLinkSetsANewPasswordOnceAndSignsOutEverySession() throws Exception {
        String email = newEmail();
        String cookie = refreshCookie(register(email));
        String userId = userId(email);

        // Adresse en majuscules : même compte
        forgot(email.toUpperCase()).andExpect(status().isAccepted());
        String text = mailpit.awaitText(email, SUBJECT);
        assertThat(text).contains("Bonjour Test,", "valable une heure",
                "Choisir un nouveau mot de passe : https://swipread.test/reinitialiser-mot-de-passe?token=");
        String token = Mailpit.token(text);

        reset(token, NEW).andExpect(status().isNoContent());

        login(email, OLD).andExpect(status().isUnauthorized());
        login(email, NEW).andExpect(status().isOk());
        mvc.perform(post("/v1/auth/refresh").cookie(new Cookie(RefreshCookies.NAME, cookie)))
                .andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT email_verified_at IS NOT NULL FROM app.users WHERE id = ?",
                Boolean.class, userId)).isTrue();
        assertThat(mailpit.awaitText(email, "Votre mot de passe a été réinitialisé"))
                .contains("Tous vos appareils ont été déconnectés", "Mot de passe oublié");

        reset(token, "yet-another-789").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Invalid or expired link"));
    }

    @Test
    void anUnknownAddressGetsTheSameAnswerAndNoMail() throws Exception {
        String email = newEmail();

        forgot(email).andExpect(status().isAccepted()).andExpect(r -> assertThat(r.getResponse().getContentLength()).isZero());

        // Le relais passe toutes les 200 ms en test : rien n'est en attente pour cette adresse
        Thread.sleep(600);
        assertThat(mailpit.count(email)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app.mail_outbox WHERE recipient = ?", Integer.class,
                email)).isZero();
    }

    @Test
    void aNewLinkReplacesTheOldOneAndTheLimitIsSilent() throws Exception {
        String email = newEmail();
        register(email);
        String userId = userId(email);

        forgot(email).andExpect(status().isAccepted());
        String first = Mailpit.token(mailpit.awaitText(email, SUBJECT));

        // Moins d'une minute après : même réponse, pas de nouveau lien
        forgot(email).andExpect(status().isAccepted());
        assertThat(resetLinks(userId)).isEqualTo(1);

        sentMinutesAgo(userId, 2);
        forgot(email).andExpect(status().isAccepted());
        assertThat(resetLinks(userId)).isEqualTo(2);
        reset(first, NEW).andExpect(status().isBadRequest());

        // Cinq liens dans la journée : plus aucun
        sentMinutesAgo(userId, 2);
        for (int i = 0; i < 3; i++) {
            jdbc.update("""
                    INSERT INTO app.user_tokens (id, user_id, purpose, token_hash, created_at, expires_at)
                    VALUES (gen_random_uuid(), ?, 'reset_password', md5(random()::text), now() - interval '2 hours', now())
                    """, userId);
        }
        forgot(email).andExpect(status().isAccepted());
        assertThat(resetLinks(userId)).isEqualTo(5);
    }

    @Test
    void anExpiredOrForeignLinkIsRefused() throws Exception {
        String email = newEmail();
        register(email);
        String userId = userId(email);
        forgot(email).andExpect(status().isAccepted());
        String token = Mailpit.token(mailpit.awaitText(email, SUBJECT));

        // Le lien de confirmation d'adresse ne sert pas à changer le mot de passe
        String verifyLink = Mailpit.token(mailpit.awaitText(email, "Confirmez votre adresse e-mail"));
        reset(verifyLink, NEW).andExpect(status().isBadRequest());

        jdbc.update("UPDATE app.user_tokens SET expires_at = now() - interval '1 second' WHERE user_id = ?", userId);
        reset(token, NEW).andExpect(status().isBadRequest());
        login(email, OLD).andExpect(status().isOk());
    }

    @Test
    void theRequestsFollowTheSignUpRules() throws Exception {
        forgot("not-an-email").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("email"));
        reset("some-token", "short").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("newPassword"))
                .andExpect(jsonPath("$.errors[0].code").value("Size"));
        mvc.perform(post("/v1/auth/password/reset").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anAccessTokenIsNotNeededButStillWorks() throws Exception {
        RegisteredUser user = registerUser();
        mvc.perform(post("/v1/auth/password/forgot").header("Authorization", user.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\": \"someone@example.org\"}"))
                .andExpect(status().isAccepted());
        mvc.perform(get("/v1/me").header("Authorization", user.bearer())).andExpect(status().isOk());
    }

    private ResultActions forgot(String email) throws Exception {
        return mvc.perform(post("/v1/auth/password/forgot").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"" + email + "\"}"));
    }

    private ResultActions reset(String token, String password) throws Exception {
        return mvc.perform(post("/v1/auth/password/reset").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\": \"" + token + "\", \"newPassword\": \"" + password + "\"}"));
    }

    private ResultActions register(String email) throws Exception {
        return mvc.perform(post("/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"email": "%s", "password": "%s", "displayName": "Test"}
                """.formatted(email, OLD))).andExpect(status().isCreated());
    }

    private ResultActions login(String email, String password) throws Exception {
        return mvc.perform(post("/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                {"email": "%s", "password": "%s"}
                """.formatted(email, password)));
    }

    private static String refreshCookie(ResultActions result) {
        String header = result.andReturn().getResponse().getHeader(HttpHeaders.SET_COOKIE);
        return header.substring(RefreshCookies.NAME.length() + 1, header.indexOf(';'));
    }

    private String userId(String email) {
        return jdbc.queryForObject("SELECT id FROM app.users WHERE email = ?", String.class, email);
    }

    private int resetLinks(String userId) {
        return jdbc.queryForObject("SELECT count(*) FROM app.user_tokens WHERE user_id = ? AND purpose = 'reset_password'",
                Integer.class, userId);
    }

    private void sentMinutesAgo(String userId, int minutes) {
        jdbc.update("UPDATE app.user_tokens SET created_at = created_at - make_interval(mins => ?) WHERE user_id = ?",
                minutes, userId);
    }

    private static String newEmail() {
        return "reset-" + UUID.randomUUID() + "@example.org";
    }
}
