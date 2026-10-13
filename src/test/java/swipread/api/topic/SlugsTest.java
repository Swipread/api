package swipread.api.topic;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class SlugsTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Tech|tech",
            "  Économie  |economie",
            "Santé & bien-être|sante-bien-etre",
            "IA 2.0|ia-2-0",
            "!!!|''"})
    void slugIsAsciiLowercaseWithDashes(String label, String slug) {
        assertThat(Slugs.of(label)).isEqualTo(slug);
    }
}
