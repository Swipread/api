package swipread.api.auth;

import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Cookie du refresh token : HttpOnly (illisible en JavaScript), SameSite=Strict, limité aux routes /v1/auth.
 */
@Component
class RefreshCookies {

    static final String NAME = "swipread_refresh";
    static final String PATH = "/v1/auth";

    private final AuthProperties properties;

    RefreshCookies(AuthProperties properties) {
        this.properties = properties;
    }

    String set(String token) {
        return cookie(token, properties.refreshTtl());
    }

    String clear() {
        return cookie("", Duration.ZERO);
    }

    private String cookie(String value, Duration maxAge) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(properties.refreshCookieSecure())
                .sameSite("Strict")
                .path(PATH)
                .maxAge(maxAge)
                .build()
                .toString();
    }
}
