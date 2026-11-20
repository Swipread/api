package swipread.api.auth;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import swipread.api.auth.dto.ChangePasswordRequest;
import swipread.api.auth.dto.LoginRequest;
import swipread.api.auth.dto.RegisterRequest;
import swipread.api.auth.dto.TokenResponse;
import swipread.api.common.ApiException;
import swipread.api.common.IdGenerator;
import swipread.api.security.JwtService;
import swipread.api.user.User;
import swipread.api.user.UserRepository;

import java.time.Clock;
import java.util.Locale;
import java.util.Optional;

@Service
public class AuthService {

    static final String DEFAULT_LOCALE = "fr-FR";

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final IdGenerator idGenerator;
    private final Clock clock;
    private final RefreshTokenService refreshTokens;
    // Hash factice pour que le temps de réponse ne révèle pas si l'email existe
    private final String dummyHash;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, JwtService jwtService,
                       IdGenerator idGenerator, Clock clock, RefreshTokenService refreshTokens) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.idGenerator = idGenerator;
        this.clock = clock;
        this.refreshTokens = refreshTokens;
        this.dummyHash = passwordEncoder.encode("dummy-password");
    }

    @Transactional
    public TokenResponse register(RegisterRequest request) {
        String email = normalize(request.email());
        if (users.existsByEmail(email)) {
            throw ApiException.conflict("Email already registered");
        }
        String locale = Optional.ofNullable(request.locale()).orElse(DEFAULT_LOCALE);
        User user = new User(idGenerator.newUserId(), email, passwordEncoder.encode(request.password()),
                request.displayName().strip(), locale, clock.instant());
        try {
            users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("Email already registered");
        }
        return tokenFor(user);
    }

    @Transactional(readOnly = true)
    public TokenResponse login(LoginRequest request) {
        Optional<User> user = users.findByEmail(normalize(request.email()));
        String hash = user.map(User::getPasswordHash).orElse(dummyHash);
        boolean matches = passwordEncoder.matches(request.password(), hash);
        if (user.isEmpty() || !matches) {
            throw ApiException.unauthorized("Invalid email or password");
        }
        return tokenFor(user.get());
    }

    /**
     * Change le mot de passe après vérification de l'actuel, et ferme dans la même transaction toutes les autres
     * sessions de l'utilisateur (celle du refresh token présenté, s'il y en a un, reste ouverte).
     */
    @Transactional
    public void changePassword(String userId, ChangePasswordRequest request, String presentedRefreshToken) {
        User user = users.findById(userId).orElseThrow(() -> ApiException.unauthorized("Unknown user"));
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw ApiException.fieldRejected("currentPassword", "WrongPassword", "Current password is incorrect");
        }
        if (passwordEncoder.matches(request.newPassword(), user.getPasswordHash())) {
            throw ApiException.fieldRejected("newPassword", "SamePassword",
                    "New password must differ from the current one");
        }
        user.changePasswordHash(passwordEncoder.encode(request.newPassword()));
        refreshTokens.revokeOtherSessions(userId, presentedRefreshToken);
    }

    /**
     * Nouvel access token après un renouvellement : le rôle est relu en base, une promotion prend donc effet ici.
     */
    @Transactional(readOnly = true)
    public TokenResponse accessTokenFor(String userId) {
        return users.findById(userId).map(this::tokenFor)
                .orElseThrow(() -> ApiException.unauthorized("Invalid refresh token"));
    }

    private TokenResponse tokenFor(User user) {
        JwtService.IssuedToken token = jwtService.issue(user.getId(), user.getRole().name());
        return new TokenResponse(token.value(), "Bearer", token.expiresInSeconds(), user.getId());
    }

    private static String normalize(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }
}
