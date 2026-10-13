package swipread.api.common;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Clock;

/**
 * Génère des identifiants préfixés au format ULID (triables par date de création).
 */
@Component
public class IdGenerator {

    private static final char[] CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();

    private final SecureRandom random = new SecureRandom();
    private final Clock clock;

    public IdGenerator(Clock clock) {
        this.clock = clock;
    }

    public String newUserId() {
        return "u_" + ulid();
    }

    public String newEventId() {
        return "e_" + ulid();
    }

    String ulid() {
        long time = clock.millis();
        byte[] entropy = new byte[10];
        random.nextBytes(entropy);
        char[] out = new char[26];
        // 48 bits de timestamp sur 10 caractères
        for (int i = 9; i >= 0; i--) {
            out[i] = CROCKFORD[(int) (time & 31)];
            time >>>= 5;
        }
        // 80 bits d'aléa sur 16 caractères
        long hi = 0;
        for (int i = 0; i < 5; i++) {
            hi = (hi << 8) | (entropy[i] & 0xFF);
        }
        long lo = 0;
        for (int i = 5; i < 10; i++) {
            lo = (lo << 8) | (entropy[i] & 0xFF);
        }
        for (int i = 17; i >= 10; i--) {
            out[i] = CROCKFORD[(int) (hi & 31)];
            hi >>>= 5;
        }
        for (int i = 25; i >= 18; i--) {
            out[i] = CROCKFORD[(int) (lo & 31)];
            lo >>>= 5;
        }
        return new String(out);
    }
}
