package swipread.api.common;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class IdGeneratorTest {

    @Test
    void userIdsArePrefixedSortableUlids() {
        IdGenerator early = new IdGenerator(Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
        IdGenerator late = new IdGenerator(Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC));

        String first = early.newUserId();
        String second = late.newUserId();

        assertThat(first).matches("u_[0-9A-HJKMNP-TV-Z]{26}");
        assertThat(first).isLessThan(second);
        assertThat(early.newUserId()).isNotEqualTo(first);
    }
}
