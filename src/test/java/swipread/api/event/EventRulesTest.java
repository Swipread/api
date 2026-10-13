package swipread.api.event;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import swipread.api.common.ApiException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static swipread.api.event.EventFixtures.NOW;
import static swipread.api.event.EventFixtures.event;

class EventRulesTest {

    @ParameterizedTest
    @CsvSource({"like,true", "save,true", "share,true", "hide,true", "unlike,false", "unsave,false", "view,false",
            "open,false", "impression,false"})
    void strongEventsFollowContract(String type, boolean strong) {
        Map<String, Object> payload = type.equals("share") ? Map.of("channel", "copy") : Map.of("dwellMs", 10);
        assertThat(StrongEventPolicy.isStrong(event("e_1", type, payload), 850)).isEqualTo(strong);
    }

    @Test
    void readIsStrongAboveEightyPercentScrollDepth() {
        assertThat(StrongEventPolicy.isStrong(event("e_1", "read", Map.of("readMs", 1000, "scrollDepth", 0.81)), null)).isTrue();
        assertThat(StrongEventPolicy.isStrong(event("e_2", "read", Map.of("readMs", 1000, "scrollDepth", 0.8)), null)).isFalse();
    }

    @Test
    void readWithoutScrollDepthIsStrongAfterSixtyPercentOfEstimatedReadingTime() {
        // 230 mots = 1 minute estimée, donc 60 % = 36 s
        assertThat(StrongEventPolicy.estimatedReadingMs(230)).isEqualTo(60_000.0);
        assertThat(StrongEventPolicy.isStrong(event("e_1", "read", Map.of("readMs", 36_000)), 230)).isTrue();
        assertThat(StrongEventPolicy.isStrong(event("e_2", "read", Map.of("readMs", 35_999)), 230)).isFalse();
        // Le seuil s'applique aussi quand scrollDepth est présent mais faible
        assertThat(StrongEventPolicy.isStrong(event("e_3", "read", Map.of("readMs", 36_000, "scrollDepth", 0.2)), 230)).isTrue();
        // Longueur inconnue ou nulle : pas de règle de durée
        assertThat(StrongEventPolicy.isStrong(event("e_4", "read", Map.of("readMs", 999_999)), null)).isFalse();
        assertThat(StrongEventPolicy.isStrong(event("e_5", "read", Map.of("readMs", 999_999)), 0)).isFalse();
    }

    @Test
    void payloadsAreValidatedPerType() {
        assertThatCode(() -> EventPayloads.validate(event("e_1", "view", Map.of("dwellMs", 4200)))).doesNotThrowAnyException();
        assertThatCode(() -> EventPayloads.validate(event("e_2", "hide", null))).doesNotThrowAnyException();
        assertThatThrownBy(() -> EventPayloads.validate(event("e_3", "view", Map.of())))
                .isInstanceOf(ApiException.class).hasMessageContaining("dwellMs is required");
        assertThatThrownBy(() -> EventPayloads.validate(event("e_4", "read", Map.of("readMs", 10, "scrollDepth", 1.5))))
                .hasMessageContaining("scrollDepth must be between 0 and 1");
        assertThatCode(() -> EventPayloads.validate(event("e_7", "read", Map.of("readMs", 10)))).doesNotThrowAnyException();
        assertThatThrownBy(() -> EventPayloads.validate(event("e_8", "read", Map.of("scrollDepth", 0.5))))
                .hasMessageContaining("readMs is required");
        assertThatThrownBy(() -> EventPayloads.validate(event("e_5", "share", Map.of())))
                .hasMessageContaining("channel is required");
        assertThatThrownBy(() -> EventPayloads.validate(event("e_6", "view", Map.of("dwellMs", "long"))))
                .hasMessageContaining("dwellMs must be a number");
    }

    @Test
    void userArticleChangeMapsEachType() {
        var at = NOW.minusSeconds(3);
        assertThat(UserArticleChange.of(event("e", "view", Map.of("dwellMs", 4200))).dwellMs()).isEqualTo(4200);
        assertThat(UserArticleChange.of(event("e", "read", Map.of("readMs", 1, "scrollDepth", 0.5))).maxScroll()).isEqualTo(0.5);

        UserArticleChange like = UserArticleChange.of(event("e", "like", null));
        assertThat(like.updateLiked()).isTrue();
        assertThat(like.likedAt()).isEqualTo(at);

        UserArticleChange unlike = UserArticleChange.of(event("e", "unlike", null));
        assertThat(unlike.updateLiked()).isTrue();
        assertThat(unlike.likedAt()).isNull();

        UserArticleChange save = UserArticleChange.of(event("e", "save", null));
        assertThat(save.updateSaved()).isTrue();
        assertThat(save.savedAt()).isEqualTo(at);

        UserArticleChange unsave = UserArticleChange.of(event("e", "unsave", null));
        assertThat(unsave.updateSaved()).isTrue();
        assertThat(unsave.savedAt()).isNull();

        assertThat(UserArticleChange.of(event("e", "hide", null)).hiddenAt()).isEqualTo(at);
        assertThat(UserArticleChange.of(event("e", "open", null)).openedAt()).isEqualTo(at);
        assertThat(UserArticleChange.of(event("e", "read", Map.of("readMs", 1))).openedAt()).isEqualTo(at);
        assertThat(UserArticleChange.of(event("e", "view", Map.of("dwellMs", 1))).openedAt()).isNull();
        assertThat(UserArticleChange.of(event("e", "impression", null)))
                .isEqualTo(new UserArticleChange(at, 0, null, false, null, false, null, null, null));
    }
}
