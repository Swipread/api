package swipread.api.security;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Service
public class JwtService {

    static final String ISSUER = "swipread-api";
    // Rôles applicatifs (READER, ADMIN), convertis en autorités ROLE_* par le Resource Server
    static final String ROLES_CLAIM = "roles";

    private final JwtEncoder encoder;
    private final JwtProperties properties;
    private final Clock clock;

    public JwtService(JwtEncoder encoder, JwtProperties properties, Clock clock) {
        this.encoder = encoder;
        this.properties = properties;
        this.clock = clock;
    }

    public IssuedToken issue(String userId, String role) {
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(userId)
                .claim(ROLES_CLAIM, List.of(role))
                .issuedAt(now)
                .expiresAt(now.plus(properties.ttl()))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(token, properties.ttl().toSeconds());
    }

    public record IssuedToken(String value, long expiresInSeconds) {
    }
}
