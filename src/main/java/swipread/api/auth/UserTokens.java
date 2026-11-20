package swipread.api.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Liens à usage unique envoyés par mail : 32 octets aléatoires, stockés hachés (SHA-256) dans app.user_tokens.
 */
@Component
public class UserTokens {

    private static final Logger log = LoggerFactory.getLogger(UserTokens.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final Clock clock;

    UserTokens(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * @return le jeton en clair, à mettre dans le lien ; seul son hachage est gardé
     */
    @Transactional
    public String issue(String userId, TokenPurpose purpose, Duration ttl) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant now = clock.instant();
        jdbc.update("""
                        INSERT INTO app.user_tokens (id, user_id, purpose, token_hash, created_at, expires_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """, UUID.randomUUID(), userId, purpose.value(), RefreshTokenService.hash(token),
                Timestamp.from(now), Timestamp.from(now.plus(ttl)));
        return token;
    }

    /**
     * Utilise le jeton s'il est connu, de cet usage, pas encore utilisé et pas expiré ; il ne servira plus.
     *
     * @return l'utilisateur du jeton
     */
    @Transactional
    public Optional<String> consume(TokenPurpose purpose, String token) {
        Timestamp now = Timestamp.from(clock.instant());
        List<String> users = jdbc.queryForList("""
                UPDATE app.user_tokens SET used_at = ?
                WHERE token_hash = ? AND purpose = ? AND used_at IS NULL AND expires_at > ?
                RETURNING user_id
                """, String.class, now, RefreshTokenService.hash(token), purpose.value(), now);
        return users.stream().findFirst();
    }

    /**
     * Rend inutilisables les jetons de cet usage qui n'ont pas servi (un nouveau lien remplace les anciens).
     */
    @Transactional
    public void revokeAll(String userId, TokenPurpose purpose) {
        jdbc.update("UPDATE app.user_tokens SET used_at = ? WHERE user_id = ? AND purpose = ? AND used_at IS NULL",
                Timestamp.from(clock.instant()), userId, purpose.value());
    }

    /**
     * @return le nombre de jetons de cet usage émis pour l'utilisateur depuis {@code since}
     */
    @Transactional(readOnly = true)
    public int issuedSince(String userId, TokenPurpose purpose, Instant since) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM app.user_tokens WHERE user_id = ? AND purpose = ? AND created_at > ?
                """, Integer.class, userId, purpose.value(), Timestamp.from(since));
        return count == null ? 0 : count;
    }

    /**
     * Purge quotidienne : jetons expirés depuis plus d'un jour (utilisés ou non).
     */
    @Scheduled(cron = "0 35 3 * * *", zone = "UTC")
    void purgeExpired() {
        int deleted = jdbc.update("DELETE FROM app.user_tokens WHERE expires_at < ?",
                Timestamp.from(clock.instant().minus(Duration.ofDays(1))));
        if (deleted > 0) {
            log.info("Purged {} expired mail links", deleted);
        }
    }
}
