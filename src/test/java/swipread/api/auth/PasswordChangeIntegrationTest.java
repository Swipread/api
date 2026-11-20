package swipread.api.auth;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.ResultActions;
import swipread.api.IntegrationTest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PasswordChangeIntegrationTest extends IntegrationTest {

    private static final String OLD = "password123";
    private static final String NEW = "a-new-password-456";

    @Test
    void changesThePasswordAndSignsOutOnlyTheOtherSessions() throws Exception {
        String email = newEmail();
        Session laptop = session(register(email));
        Session phone = session(login(email, OLD));

        changePassword(laptop.accessToken(), laptop.cookie(), OLD, NEW).andExpect(status().isNoContent());

        refresh(laptop.cookie()).andExpect(status().isOk());
        refresh(phone.cookie()).andExpect(status().isUnauthorized());
        login(email, OLD).andExpect(status().isUnauthorized());
        login(email, NEW).andExpect(status().isOk());
    }

    @Test
    void withoutCookieEverySessionIsSignedOut() throws Exception {
        String email = newEmail();
        Session laptop = session(register(email));

        changePassword(laptop.accessToken(), null, OLD, NEW).andExpect(status().isNoContent());

        refresh(laptop.cookie()).andExpect(status().isUnauthorized());
    }

    @Test
    void aCookieOfAnotherUserKeepsNothing() throws Exception {
        Session mine = session(register(newEmail()));
        Session other = session(register(newEmail()));

        changePassword(mine.accessToken(), other.cookie(), OLD, NEW).andExpect(status().isNoContent());

        refresh(mine.cookie()).andExpect(status().isUnauthorized());
        refresh(other.cookie()).andExpect(status().isOk());
    }

    @Test
    void aWrongCurrentPasswordIsAFieldErrorAndChangesNothing() throws Exception {
        String email = newEmail();
        Session laptop = session(register(email));
        Session phone = session(login(email, OLD));

        changePassword(laptop.accessToken(), laptop.cookie(), "not-my-password", NEW)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("currentPassword"))
                .andExpect(jsonPath("$.errors[0].code").value("WrongPassword"));

        login(email, OLD).andExpect(status().isOk());
        refresh(phone.cookie()).andExpect(status().isOk());
    }

    @Test
    void theNewPasswordMustDifferAndFollowTheSignUpRules() throws Exception {
        Session laptop = session(register(newEmail()));

        changePassword(laptop.accessToken(), laptop.cookie(), OLD, OLD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("newPassword"))
                .andExpect(jsonPath("$.errors[0].code").value("SamePassword"));
        changePassword(laptop.accessToken(), laptop.cookie(), OLD, "short")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("newPassword"))
                .andExpect(jsonPath("$.errors[0].code").value("Size"));
    }

    @Test
    void needsAnAccessToken() throws Exception {
        mvc.perform(put("/v1/auth/password").contentType(MediaType.APPLICATION_JSON).content(body(OLD, NEW)))
                .andExpect(status().isUnauthorized());
    }

    private record Session(String accessToken, String cookie) {
    }

    private Session session(ResultActions result) throws Exception {
        MockHttpServletResponse response = result.andReturn().getResponse();
        String header = response.getHeader(HttpHeaders.SET_COOKIE);
        assertThat(header).startsWith(RefreshCookies.NAME + "=");
        return new Session(json.readTree(response.getContentAsString()).get("accessToken").asString(),
                header.substring(RefreshCookies.NAME.length() + 1, header.indexOf(';')));
    }

    private ResultActions changePassword(String accessToken, String cookie, String current, String next) throws Exception {
        var request = put("/v1/auth/password").header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON).content(body(current, next));
        if (cookie != null) {
            request.cookie(new Cookie(RefreshCookies.NAME, cookie));
        }
        return mvc.perform(request);
    }

    private ResultActions refresh(String cookie) throws Exception {
        return mvc.perform(post("/v1/auth/refresh").cookie(new Cookie(RefreshCookies.NAME, cookie)));
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

    private static String body(String current, String next) {
        return """
                {"currentPassword": "%s", "newPassword": "%s"}
                """.formatted(current, next);
    }

    private static String newEmail() {
        return "password-" + UUID.randomUUID() + "@example.org";
    }
}
