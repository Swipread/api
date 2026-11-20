package swipread.api.auth;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import swipread.api.common.ApiException;
import swipread.api.mail.MailOutbox;
import swipread.api.user.User;
import swipread.api.user.UserRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Vérification de l'adresse mail : un lien valable 24 h, envoyé à l'inscription et sur demande.
 */
@Service
public class EmailVerificationService {

    static final Duration LINK_TTL = Duration.ofHours(24);
    /** Un nouveau lien au plus par minute, et cinq par jour. */
    static final Duration RESEND_INTERVAL = Duration.ofMinutes(1);
    static final int MAX_PER_DAY = 5;

    private final UserRepository users;
    private final UserTokens tokens;
    private final MailOutbox mailOutbox;
    private final AccountMails mails;
    private final Clock clock;

    EmailVerificationService(UserRepository users, UserTokens tokens, MailOutbox mailOutbox, AccountMails mails,
                             Clock clock) {
        this.users = users;
        this.tokens = tokens;
        this.mailOutbox = mailOutbox;
        this.mails = mails;
        this.clock = clock;
    }

    /**
     * Envoie un nouveau lien ; les liens précédents ne servent plus. Dans la transaction de l'appelant.
     */
    @Transactional
    public void sendLink(User user) {
        tokens.revokeAll(user.getId(), TokenPurpose.VERIFY_EMAIL);
        String token = tokens.issue(user.getId(), TokenPurpose.VERIFY_EMAIL, LINK_TTL);
        mailOutbox.enqueue(mails.verifyEmail(user, token));
    }

    /**
     * Nouveau lien demandé par le lecteur : 409 si l'adresse est déjà confirmée, 429 trop tôt ou trop souvent.
     */
    @Transactional
    public void resend(String userId) {
        User user = users.findById(userId).orElseThrow(() -> ApiException.unauthorized("Unknown user"));
        if (user.isEmailVerified()) {
            throw ApiException.conflict("Email address already verified");
        }
        Instant now = clock.instant();
        if (tokens.issuedSince(userId, TokenPurpose.VERIFY_EMAIL, now.minus(RESEND_INTERVAL)) > 0
                || tokens.issuedSince(userId, TokenPurpose.VERIFY_EMAIL, now.minus(Duration.ofDays(1))) >= MAX_PER_DAY) {
            throw ApiException.tooManyRequests("A verification link was sent recently, try again later");
        }
        sendLink(user);
    }

    /**
     * Le lien a été ouvert : l'adresse est confirmée (une seconde fois ne change rien).
     */
    @Transactional
    public void verify(String token) {
        String userId = tokens.consume(TokenPurpose.VERIFY_EMAIL, token)
                .orElseThrow(() -> ApiException.badRequest("Invalid or expired link"));
        users.findById(userId).ifPresent(user -> user.markEmailVerified(clock.instant()));
        tokens.revokeAll(userId, TokenPurpose.VERIFY_EMAIL);
    }
}
