package swipread.api.ingestion;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RobotsRulesTest {

    @Test
    void usesTheRobotsOwnGroupOverTheDefaultOne() {
        RobotsRules rules = RobotsRules.parse("""
                User-agent: *
                Disallow: /

                User-agent: SwipreadBot
                Disallow: /prive/
                """, "SwipreadBot");
        assertThat(rules.allows("/articles/1")).isTrue();
        assertThat(rules.allows("/prive/2")).isFalse();
    }

    @Test
    void fallsBackToTheDefaultGroup() {
        RobotsRules rules = RobotsRules.parse("""
                # Commentaire
                User-agent: AutreBot
                Disallow: /

                User-agent: *
                Disallow: /admin   # fin de ligne
                """, "SwipreadBot");
        assertThat(rules.allows("/admin/x")).isFalse();
        assertThat(rules.allows("/articles")).isTrue();
    }

    @Test
    void longestRuleWinsAndAllowWinsTies() {
        RobotsRules rules = RobotsRules.parse("""
                User-agent: *
                Disallow: /actu/
                Allow: /actu/public/
                Disallow: /egal
                Allow: /egal
                """, "SwipreadBot");
        assertThat(rules.allows("/actu/prive")).isFalse();
        assertThat(rules.allows("/actu/public/1")).isTrue();
        assertThat(rules.allows("/egal")).isTrue();
    }

    @Test
    void handlesWildcardsAndEndAnchors() {
        RobotsRules rules = RobotsRules.parse("""
                User-agent: *
                Disallow: /*.pdf$
                Disallow: /*?session=
                """, "SwipreadBot");
        assertThat(rules.allows("/docs/guide.pdf")).isFalse();
        assertThat(rules.allows("/docs/guide.pdf?v=2")).isTrue();
        assertThat(rules.allows("/page?session=42")).isFalse();
        assertThat(rules.allows("/page?lang=fr")).isTrue();
    }

    @Test
    void sharesRulesBetweenAgentsOfOneGroupAndIgnoresEmptyDisallow() {
        RobotsRules rules = RobotsRules.parse("""
                User-agent: AutreBot
                User-agent: SwipreadBot
                Disallow: /un/
                Disallow:
                """, "SwipreadBot");
        assertThat(rules.allows("/un/x")).isFalse();
        assertThat(rules.allows("/deux")).isTrue();
        assertThat(RobotsRules.parse("", "SwipreadBot").allows("/x")).isTrue();
        assertThat(RobotsRules.DISALLOW_ALL.allows("/")).isFalse();
        assertThat(RobotsRules.ALLOW_ALL.allows("/x")).isTrue();
    }
}
