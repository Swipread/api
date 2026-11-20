package swipread.api.event;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import swipread.api.IntegrationTest;

import java.sql.SQLException;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Migration V9 : la base impose aux événements ce que l'API vérifie déjà, et app.articles n'a plus de colonne embedding.
 */
class EventSchemaIntegrationTest extends IntegrationTest {

    private static final String INSERT = """
            INSERT INTO app.events (event_id, user_id, session_id, type, article_id, occurred_at)
            VALUES ('e_schema_test', 'u_schema_test', 's_schema_test', ?, ?, now())
            """;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void everyEventTypeOfTheApiIsAccepted() {
        try {
            for (EventType type : EventType.values()) {
                jdbc.update("DELETE FROM app.events WHERE event_id = 'e_schema_test'");
                assertThat(jdbc.update(INSERT, type.wireName(), "a_schema_test")).isEqualTo(1);
            }
        } finally {
            jdbc.update("DELETE FROM app.events WHERE event_id = 'e_schema_test'");
        }
    }

    @Test
    void anEventWithoutArticleIsRejected() {
        assertThatThrownBy(() -> jdbc.update(INSERT, "view", null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(error -> assertThat(sqlState(error)).isEqualTo("23502"));
    }

    @Test
    void anUnknownEventTypeIsRejected() {
        assertThatThrownBy(() -> jdbc.update(INSERT, "click", "a_schema_test"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(error -> assertThat(sqlState(error)).isEqualTo("23514"));
    }

    @Test
    void articlesHaveNoEmbeddingColumnNorIndex() {
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema = 'app' AND table_name = 'articles' AND column_name = 'embedding'
                """, Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT to_regclass('app.articles_embedding_idx') IS NULL", Boolean.class))
                .isTrue();
    }

    private static String sqlState(Throwable error) {
        return Arrays.stream(new Throwable[]{error, error.getCause()})
                .filter(SQLException.class::isInstance)
                .map(e -> ((SQLException) e).getSQLState())
                .findFirst().orElse(null);
    }
}
