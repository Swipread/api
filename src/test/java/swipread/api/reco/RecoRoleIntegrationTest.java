package swipread.api.reco;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import swipread.api.IntegrationTest;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Migration V5 : le rôle swipread_reco lit app sans les données sensibles des comptes et écrit dans reco.
 * Les requêtes sont jouées avec SET ROLE, comme le ferait un utilisateur de la reco rattaché à ce rôle.
 */
class RecoRoleIntegrationTest extends IntegrationTest {

    private static final String DENIED = "42501";

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void roleCannotLogIn() {
        assertThat(jdbc.queryForObject("SELECT rolcanlogin FROM pg_roles WHERE rolname = 'swipread_reco'", Boolean.class))
                .isFalse();
    }

    @Test
    void readsAppButNotAccountSecrets() {
        asReco(statement -> {
            assertThat(run(statement, "SELECT count(*) FROM app.articles")).isNull();
            assertThat(run(statement, "SELECT count(*) FROM app.events")).isNull();
            assertThat(run(statement, "SELECT count(*) FROM app.user_articles")).isNull();
            assertThat(run(statement, "SELECT id, locale, created_at FROM app.users")).isNull();
            assertThat(run(statement, "SELECT email FROM app.users")).isEqualTo(DENIED);
            assertThat(run(statement, "SELECT password_hash FROM app.users")).isEqualTo(DENIED);
        });
    }

    @Test
    void cannotWriteApp() {
        asReco(statement -> {
            assertThat(run(statement, "INSERT INTO app.topics (slug, label) VALUES ('reco-test', 'Reco test')"))
                    .isEqualTo(DENIED);
            assertThat(run(statement, "DELETE FROM app.articles")).isEqualTo(DENIED);
            assertThat(run(statement, "CREATE TABLE app.reco_test (id int)")).isEqualTo(DENIED);
        });
    }

    @Test
    void writesRecoAndCreatesItsOwnTables() {
        String table = "reco.it_" + UUID.randomUUID().toString().replace("-", "");
        asReco(statement -> {
            assertThat(run(statement, "INSERT INTO reco.model_runs (version, started_at) VALUES ('it', now())")).isNull();
            assertThat(run(statement, "DELETE FROM reco.model_runs WHERE version = 'it'")).isNull();
            assertThat(run(statement, "CREATE TABLE " + table + " (id int)")).isNull();
            assertThat(run(statement, "DROP TABLE " + table)).isNull();
        });
    }

    @Test
    void tablesAddedLaterToAppAreReadable() {
        // Comme une future migration, ou la partition du mois suivant créée par le job
        String table = "app.it_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE TABLE " + table + " (id int)");
        try {
            asReco(statement -> assertThat(run(statement, "SELECT count(*) FROM " + table)).isNull());
        } finally {
            jdbc.execute("DROP TABLE " + table);
        }
    }

    private void asReco(StatementCheck check) {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET ROLE swipread_reco");
                try {
                    check.accept(statement);
                } finally {
                    statement.execute("RESET ROLE");
                }
            }
            return null;
        });
    }

    /**
     * @return null si la requête passe, sinon le SQLState de l'erreur
     */
    private static String run(Statement statement, String sql) {
        try {
            statement.execute(sql);
            return null;
        } catch (SQLException e) {
            return e.getSQLState();
        }
    }

    @FunctionalInterface
    private interface StatementCheck {
        void accept(Statement statement) throws SQLException;
    }
}
