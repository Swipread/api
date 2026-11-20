package swipread.api.ingestion;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import swipread.api.IntegrationTest;

import java.io.IOException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class IngestionIntegrationTest extends IntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private IngestionService ingestion;

    private TestSite site;
    private int topic1;
    private int topic2;

    @BeforeEach
    void start() throws IOException {
        site = new TestSite();
        List<Integer> topics = jdbc.queryForList("SELECT id FROM app.topics ORDER BY id LIMIT 2", Integer.class);
        topic1 = topics.get(0);
        topic2 = topics.get(1);
    }

    @AfterEach
    void stop() {
        site.close();
    }

    @Test
    void savesNewArticlesWithTheirPageWordsImagesAndSourceTopics() {
        int source = source("/rss", topic2, topic1);

        assertThat(ingestion.read(toRead(source))).isEqualTo(6);

        Map<String, Object> first = article("/a/1");
        assertThat(first.get("id").toString()).startsWith("a_");
        assertThat(first.get("title")).isEqualTo("Premier & article");
        assertThat(first.get("summary")).isEqualTo("Résumé du premier");
        assertThat(first.get("author")).isEqualTo("Ana Dupont");
        assertThat(first.get("language")).isEqualTo("en");
        // Mots du texte principal de la page, sans la navigation ni le pied de page ; image de la page
        assertThat(first.get("word_count")).isEqualTo(850);
        assertThat(first.get("word_count_from_summary")).isEqualTo(false);
        assertThat(first.get("image_url")).isEqualTo(site.url("/img/page-1.jpg"));
        assertThat(((Timestamp) first.get("published_at")).toInstant())
                .isCloseTo(Instant.now().minus(1, ChronoUnit.HOURS), within(5));

        Map<String, Object> second = article("/a/2");
        assertThat(second.get("word_count")).isEqualTo(345);
        assertThat(second.get("image_url")).isEqualTo(site.url("/img/media-2.jpg"));

        // Page interdite par robots.txt : jamais demandée, mots du contenu du flux
        assertThat(site.hits("/prive/3")).isZero();
        assertThat(article("/prive/3").get("word_count")).isEqualTo(10);
        assertThat(article("/prive/3").get("word_count_from_summary")).isEqualTo(false);
        assertThat(article("/prive/3").get("image_url")).isEqualTo(site.url("/img/prive"));
        // Page de défi anti-robot : ni ses mots ni son image, mots du titre et du résumé
        Map<String, Object> challenge = article("/a/defi");
        assertThat(challenge.get("word_count")).isEqualTo(9);
        assertThat(challenge.get("word_count_from_summary")).isEqualTo(true);
        assertThat(challenge.get("image_url")).isNull();
        // Pas du HTML : mots du titre et du résumé
        assertThat(article("/a/pdf").get("word_count")).isEqualTo(5);
        assertThat(article("/a/pdf").get("word_count_from_summary")).isEqualTo(true);
        // Sans date : datée de la lecture
        assertThat(((Timestamp) article("/a/sans-date").get("published_at")).toInstant())
                .isCloseTo(Instant.now(), within(1));
        // Trop vieux (10 jours), et doublon de /a/1 : écartés
        assertThat(count("SELECT count(*) FROM app.articles WHERE url = ?", site.url("/a/vieux"))).isZero();
        assertThat(count("SELECT count(*) FROM app.articles WHERE source_id = ?", source)).isEqualTo(6);
        assertThat(site.hits("/robots.txt")).isEqualTo(1);

        // Thèmes de la source dans leur ordre : le premier est le thème principal
        assertThat(jdbc.queryForList("""
                        SELECT topic_id, confidence FROM app.article_topics WHERE article_id = ? ORDER BY confidence DESC
                        """, first.get("id")))
                .containsExactly(Map.of("topic_id", topic2, "confidence", 1.0f), Map.of("topic_id", topic1, "confidence", 0.9f));

        Map<String, Object> state = jdbc.queryForMap(
                "SELECT etag, last_fetched_at, last_error FROM app.sources WHERE id = ?", source);
        assertThat(state.get("etag")).isEqualTo(TestSite.ETAG);
        assertThat(state.get("last_fetched_at")).isNotNull();
        assertThat(state.get("last_error")).isNull();
    }

    @Test
    void rereadsTheFeedOnlyWhenItChanged() {
        int source = source("/rss", topic1);
        assertThat(ingestion.read(toRead(source))).isEqualTo(6);
        Timestamp firstFetch = jdbc.queryForObject("SELECT last_fetched_at FROM app.sources WHERE id = ?",
                Timestamp.class, source);

        // L'ETag reçu est renvoyé : le site répond 304, rien n'est relu
        assertThat(ingestion.read(toRead(source))).isZero();
        assertThat(site.lastIfNoneMatch("/rss")).isEqualTo(TestSite.ETAG);
        assertThat(site.hits("/a/1")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT last_fetched_at FROM app.sources WHERE id = ?", Timestamp.class, source))
                .isAfterOrEqualTo(firstFetch);

        // Sans ETag, le flux est relu mais ses articles sont déjà connus
        jdbc.update("UPDATE app.sources SET etag = NULL WHERE id = ?", source);
        assertThat(ingestion.read(toRead(source))).isZero();
        assertThat(site.hits("/a/1")).isEqualTo(1);
    }

    @Test
    void takesAtMostThirtyNewArticlesPerRead() {
        int source = source("/many", topic1);
        assertThat(ingestion.read(toRead(source))).isEqualTo(30);
        // Les plus récents d'abord : /m/0 à /m/29
        assertThat(count("SELECT count(*) FROM app.articles WHERE url = ?", site.url("/m/0"))).isOne();
        assertThat(count("SELECT count(*) FROM app.articles WHERE url = ?", site.url("/m/30"))).isZero();
        // Le passage suivant prend la suite
        jdbc.update("UPDATE app.sources SET etag = NULL WHERE id = ?", source);
        assertThat(ingestion.read(toRead(source))).isEqualTo(5);
    }

    @Test
    void aFailingSourceKeepsItsErrorWithoutStoppingTheOthers() {
        int broken = source("/broken", topic1);
        int good = source("/rss", topic1);
        int withoutTopics = source("/many");
        int inactive = source("/many", topic1);
        jdbc.update("UPDATE app.sources SET active = false WHERE id = ?", inactive);

        IngestionService.Run run = ingestion.runOnce();

        assertThat(run.skipped()).isFalse();
        assertThat(jdbc.queryForObject("SELECT last_error FROM app.sources WHERE id = ?", String.class, broken))
                .isEqualTo("Feed answered HTTP 404");
        assertThat(count("SELECT count(*) FROM app.articles WHERE source_id = ?", good)).isEqualTo(6);
        // Sans thème ou inactive : pas lue
        assertThat(jdbc.queryForObject("SELECT last_fetched_at FROM app.sources WHERE id = ?", Timestamp.class,
                withoutTopics)).isNull();
        assertThat(jdbc.queryForObject("SELECT last_fetched_at FROM app.sources WHERE id = ?", Timestamp.class,
                inactive)).isNull();

        // Une lecture réussie efface l'erreur
        jdbc.update("UPDATE app.sources SET feed_url = ? WHERE id = ?", site.url("/rss?fixed=" + UUID.randomUUID()), broken);
        ingestion.read(toRead(broken));
        assertThat(jdbc.queryForObject("SELECT last_error FROM app.sources WHERE id = ?", String.class, broken)).isNull();
    }

    @Test
    void aSecondInstanceSkipsTheRunWhileOneIsRunning() {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SELECT pg_advisory_lock(" + IngestionService.LOCK_KEY + ")");
                try {
                    assertThat(ingestion.runOnce().skipped()).isTrue();
                } finally {
                    statement.execute("SELECT pg_advisory_unlock(" + IngestionService.LOCK_KEY + ")");
                }
            }
            return null;
        });
        assertThat(ingestion.runOnce().skipped()).isFalse();
    }

    /** Source active en anglais, avec ces thèmes dans cet ordre ; l'adresse du flux est unique à chaque appel. */
    private int source(String path, int... topics) {
        Integer id = jdbc.queryForObject("""
                        INSERT INTO app.sources (name, site_url, feed_url, language) VALUES ('Le Site', ?, ?, 'en')
                        RETURNING id
                        """, Integer.class, site.url(""), site.url(path + "?run=" + UUID.randomUUID()));
        for (int i = 0; i < topics.length; i++) {
            jdbc.update("INSERT INTO app.source_topics (source_id, topic_id, position) VALUES (?, ?, ?)", id, topics[i], i + 1);
        }
        return id;
    }

    private IngestionService.SourceToRead toRead(int source) {
        return jdbc.queryForObject("SELECT id, feed_url, language, etag, last_modified FROM app.sources WHERE id = ?",
                (rs, row) -> new IngestionService.SourceToRead(rs.getInt("id"), rs.getString("feed_url"),
                        rs.getString("language"), rs.getString("etag"), rs.getString("last_modified")), source);
    }

    private Map<String, Object> article(String path) {
        return jdbc.queryForMap("SELECT * FROM app.articles WHERE url = ?", site.url(path));
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private static org.assertj.core.data.TemporalUnitOffset within(int minutes) {
        return new org.assertj.core.data.TemporalUnitWithinOffset(minutes, ChronoUnit.MINUTES);
    }
}
