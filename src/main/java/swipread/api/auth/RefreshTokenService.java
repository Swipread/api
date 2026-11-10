package swipread.api.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Refresh tokens opaques (32 octets aléatoires), stockés hachés dans app.refresh_tokens.
 * <p>
 * Rotation : chaque renouvellement révoque le token présenté et en émet un nouveau dans la même famille, valable de
 * nouveau refreshTtl (durée glissante). Un token déjà remplacé, présenté après le délai de grâce, signale un vol :
 * toute la famille est révoquée.
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final AuthProperties properties;
    private final Clock clock;

    public RefreshTokenService(JdbcTemplate jdbc, AuthProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
    }

    public sealed interface Rotation {
        /** Nouveau token émis : poser le cookie. */
        record Rotated(String userId, String token) implements Rotation {
        }

        /** Token remplacé il y a moins de refreshGrace : nouvel access token, cookie inchangé. */
        record Grace(String userId) implements Rotation {
        }

        record Rejected() implements Rotation {
        }
    }

    /**
     * Nouvelle famille, à la connexion ou à l'inscription.
     */
    @Transactional
    public String issue(String userId) {
        return insert(userId, UUID.randomUUID(), clock.instant()).value();
    }

    @Transactional
    public Rotation rotate(String presented) {
        Instant now = clock.instant();
        List<StoredToken> found = jdbc.query("""
                        SELECT id, user_id, family_id, expires_at, revoked_at, replaced_by
                        FROM app.refresh_tokens WHERE token_hash = ? FOR UPDATE
                        """, (rs, n) -> new StoredToken(rs.getObject(1, UUID.class), rs.getString(2),
                        rs.getObject(3, UUID.class), rs.getTimestamp(4).toInstant(),
                        rs.getTimestamp(5) == null ? null : rs.getTimestamp(5).toInstant(), rs.getObject(6, UUID.class)),
                hash(presented));
        if (found.isEmpty()) {
            return new Rotation.Rejected();
        }
        StoredToken token = found.getFirst();
        if (!token.expiresAt().isAfter(now)) {
            return new Rotation.Rejected();
        }
        if (token.revokedAt() == null) {
            NewToken next = insert(token.userId(), token.familyId(), now);
            jdbc.update("UPDATE app.refresh_tokens SET revoked_at = ?, replaced_by = ? WHERE id = ?",
                    Timestamp.from(now), next.id(), token.id());
            return new Rotation.Rotated(token.userId(), next.value());
        }
        boolean replacedRecently = token.replacedBy() != null
                && token.revokedAt().plus(properties.refreshGrace()).isAfter(now);
        if (replacedRecently && familyStillActive(token.familyId())) {
            return new Rotation.Grace(token.userId());
        }
        if (token.replacedBy() != null) {
            log.warn("Refresh token reused after rotation for {}: revoking its family", token.userId());
            revokeFamily(token.familyId(), now);
        }
        return new Rotation.Rejected();
    }

    /**
     * Déconnexion : révoque toute la famille du token présenté (la session de cet appareil).
     */
    @Transactional
    public void revoke(String presented) {
        jdbc.query("SELECT family_id FROM app.refresh_tokens WHERE token_hash = ?",
                        (rs, n) -> rs.getObject(1, UUID.class), hash(presented))
                .forEach(family -> revokeFamily(family, clock.instant()));
    }

    /**
     * Purge quotidienne des tokens expirés depuis plus d'un jour.
     */
    @Scheduled(cron = "0 30 3 * * *", zone = "UTC")
    void purgeExpired() {
        int deleted = jdbc.update("DELETE FROM app.refresh_tokens WHERE expires_at < ?",
                Timestamp.from(clock.instant().minus(Duration.ofDays(1))));
        if (deleted > 0) {
            log.info("Purged {} expired refresh tokens", deleted);
        }
    }

    private NewToken insert(String userId, UUID familyId, Instant now) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        NewToken token = new NewToken(UUID.randomUUID(), Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
        jdbc.update("""
                        INSERT INTO app.refresh_tokens (id, user_id, family_id, token_hash, created_at, expires_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """, token.id(), userId, familyId, hash(token.value()), Timestamp.from(now),
                Timestamp.from(now.plus(properties.refreshTtl())));
        return token;
    }

    private record NewToken(UUID id, String value) {
    }

    private boolean familyStillActive(UUID familyId) {
        Integer active = jdbc.queryForObject(
                "SELECT count(*) FROM app.refresh_tokens WHERE family_id = ? AND revoked_at IS NULL", Integer.class,
                familyId);
        return active != null && active > 0;
    }

    private void revokeFamily(UUID familyId, Instant now) {
        jdbc.update("UPDATE app.refresh_tokens SET revoked_at = ? WHERE family_id = ? AND revoked_at IS NULL",
                Timestamp.from(now), familyId);
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private record StoredToken(UUID id, String userId, UUID familyId, Instant expiresAt, Instant revokedAt,
                               UUID replacedBy) {
    }
}
