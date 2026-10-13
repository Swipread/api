package swipread.api.auth;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import swipread.api.auth.RefreshTokenService.Rotation;
import swipread.api.auth.dto.LoginRequest;
import swipread.api.auth.dto.RegisterRequest;
import swipread.api.auth.dto.TokenResponse;
import swipread.api.common.ApiException;

@RestController
@RequestMapping("/v1/auth")
@Tag(name = "Auth")
@SecurityRequirements
class AuthController {

    private static final String REFRESH_COOKIE_DOC = "Refresh token, set as an HttpOnly cookie by register, login and refresh";

    private final AuthService authService;
    private final RefreshTokenService refreshTokens;
    private final RefreshCookies cookies;

    AuthController(AuthService authService, RefreshTokenService refreshTokens, RefreshCookies cookies) {
        this.authService = authService;
        this.refreshTokens = refreshTokens;
        this.cookies = cookies;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create an account and return an access token",
            description = "Also sets the refresh token cookie (`swipread_refresh`, HttpOnly, path /v1/auth).")
    @ApiResponse(responseCode = "201", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "409", description = "Email already registered")
    TokenResponse register(@Valid @RequestBody RegisterRequest request, HttpServletResponse response) {
        return withRefreshCookie(authService.register(request), response);
    }

    @PostMapping("/login")
    @Operation(summary = "Exchange credentials for an access token",
            description = "Also sets the refresh token cookie (`swipread_refresh`, HttpOnly, path /v1/auth).")
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "401", description = "Invalid email or password")
    TokenResponse login(@Valid @RequestBody LoginRequest request, HttpServletResponse response) {
        return withRefreshCookie(authService.login(request), response);
    }

    @PostMapping("/refresh")
    @Operation(summary = "Get a new access token from the refresh token cookie",
            description = """
                    Rotates the refresh token: the cookie is replaced and the presented token can no longer be used. \
                    A token replaced less than 10 seconds ago (concurrent tabs) still gets an access token, without \
                    a new cookie. A token reused later revokes the whole session (401). Send it with credentials \
                    (cookie), without Authorization header.""")
    @Parameter(in = ParameterIn.COOKIE, name = RefreshCookies.NAME, description = REFRESH_COOKIE_DOC)
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "401", description = "Missing, unknown, expired, revoked or reused refresh token")
    TokenResponse refresh(@Parameter(hidden = true) @CookieValue(name = RefreshCookies.NAME, required = false) String token,
                          HttpServletResponse response) {
        Rotation rotation = token == null || token.isBlank() ? new Rotation.Rejected() : refreshTokens.rotate(token);
        return switch (rotation) {
            case Rotation.Rotated(String userId, String next) -> {
                response.addHeader(HttpHeaders.SET_COOKIE, cookies.set(next));
                yield authService.accessTokenFor(userId);
            }
            case Rotation.Grace(String userId) -> authService.accessTokenFor(userId);
            case Rotation.Rejected() -> {
                response.addHeader(HttpHeaders.SET_COOKIE, cookies.clear());
                throw ApiException.unauthorized("Invalid refresh token");
            }
        };
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Revoke the refresh token and clear its cookie",
            description = "The access token stays valid until it expires (15 minutes): the client must forget it.")
    @Parameter(in = ParameterIn.COOKIE, name = RefreshCookies.NAME, description = REFRESH_COOKIE_DOC)
    @ApiResponse(responseCode = "204", description = "Logged out")
    void logout(@Parameter(hidden = true) @CookieValue(name = RefreshCookies.NAME, required = false) String token,
                HttpServletResponse response) {
        if (token != null && !token.isBlank()) {
            refreshTokens.revoke(token);
        }
        response.addHeader(HttpHeaders.SET_COOKIE, cookies.clear());
    }

    private TokenResponse withRefreshCookie(TokenResponse token, HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookies.set(refreshTokens.issue(token.userId())));
        return token;
    }
}
