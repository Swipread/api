package swipread.api.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import swipread.api.common.ApiException;
import swipread.api.mail.MailOutbox;
import swipread.api.user.User;
import swipread.api.user.UserRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

/**
 * Mot de passe oublié : un lien valable 1 h envoyé à l'adresse du compte, qui permet d'en choisir un nouveau.
 * <p>
 * La demande répond toujours pareil, que l'adresse ait un compte ou non, et même au-delà de la limite d'envois :
 * la réponse ne révèle pas quelles adresses sont inscrites.
 */
@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    static final Duration LINK_TTL = Duration.ofHours(1);
    /** Un lien au plus par minute, et cinq par jour, pour un même compte. */
    static final Duration RESEND_INTERVAL = Duration.ofMinutes(1);
    static final int MAX_PER_DAY = 5;

    private final UserRepository users;
    private final UserTokens tokens;
    private final RefreshTokenService refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final MailOutbox mailOutbox;
    private final AccountMails mails;
    private final Clock clock;

    PasswordResetService(UserRepository users, UserTokens tokens, RefreshTokenService refreshTokens,
                         PasswordEncoder passwordEncoder, MailOutbox mailOutbox, AccountMails mails, Clock clock) {
        this.users = users;
        this.tokens = tokens;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.mailOutbox = mailOutbox;
        this.mails = mails;
        this.clock = clock;
    }

    /**
     * Envoie un lien si l'adresse a un compte et que la limite n'est pas atteinte ; sinon ne fait rien, sans le dire.
     * Les liens précédents ne servent plus.
     */
    @Transactional
    public void requestLink(String email) {
        Optional<User> found = users.findByEmail(email.strip().toLowerCase(Locale.ROOT));
        if (found.isEmpty()) {
            return;
        }
        User user = found.get();
        Instant now = clock.instant();
        if (tokens.issuedSince(user.getId(), TokenPurpose.RESET_PASSWORD, now.minus(RESEND_INTERVAL)) > 0
                || tokens.issuedSince(user.getId(), TokenPurpose.RESET_PASSWORD, now.minus(Duration.ofDays(1)))
                >= MAX_PER_DAY) {
            log.info("Password reset link not sent to {}: sent recently", user.getId());
            return;
        }
        tokens.revokeAll(user.getId(), TokenPurpose.RESET_PASSWORD);
        String token = tokens.issue(user.getId(), TokenPurpose.RESET_PASSWORD, LINK_TTL);
        mailOutbox.enqueue(mails.resetPassword(user, token));
    }

    /**
     * Le lien a été ouvert : nouveau mot de passe, toutes les sessions fermées, avis envoyé. Le lien prouve aussi que
     * le lecteur reçoit les mails de cette adresse : elle est confirmée.
     */
    @Transactional
    public void reset(String token, String newPassword) {
        String userId = tokens.consume(TokenPurpose.RESET_PASSWORD, token)
                .orElseThrow(() -> ApiException.badRequest("Invalid or expired link"));
        User user = users.findById(userId).orElseThrow(() -> ApiException.badRequest("Invalid or expired link"));
        Instant now = clock.instant();
        user.changePasswordHash(passwordEncoder.encode(newPassword));
        user.markEmailVerified(now);
        tokens.revokeAll(userId, TokenPurpose.RESET_PASSWORD);
        tokens.revokeAll(userId, TokenPurpose.VERIFY_EMAIL);
        refreshTokens.revokeOtherSessions(userId, null);
        mailOutbox.enqueue(mails.passwordReset(user, now));
    }
}
