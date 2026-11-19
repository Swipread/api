package swipread.api.source;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;
import swipread.api.IntegrationTest;
import swipread.api.feed.TestArticles;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminSourcesIntegrationTest extends IntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    private TestFeedServer feeds;
    private int topic1;
    private int topic2;
    private int topic3;

    @BeforeEach
    void start() throws IOException {
        feeds = new TestFeedServer();
        List<Integer> ids = jdbc.queryForList("SELECT id FROM app.topics ORDER BY id LIMIT 3", Integer.class);
        topic1 = ids.get(0);
        topic2 = ids.get(1);
        topic3 = ids.get(2);
    }

    @AfterEach
    void stop() {
        feeds.close();
    }

    @Test
    void adminAddsDeactivatesAndDeletesASource() throws Exception {
        RegisteredUser admin = loginAdmin();
        // Le paramètre rend l'URL unique d'un test à l'autre (feed_url est unique en base)
        String feedUrl = feeds.url("/rss?run=" + UUID.randomUUID());

        // L'ordre des thèmes est celui de la requête : le premier sera le thème principal des articles
        JsonNode created = json.readTree(createSource(admin, feedUrl, "[%d, %d]".formatted(topic2, topic1))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Le Test"))
                .andExpect(jsonPath("$.feedUrl").value(feedUrl))
                .andExpect(jsonPath("$.language").value("fr"))
                .andExpect(jsonPath("$.license").value("CC BY 4.0"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.topics.length()").value(2))
                .andExpect(jsonPath("$.topics[0].id").value(topic2))
                .andExpect(jsonPath("$.topics[1].id").value(topic1))
                .andExpect(jsonPath("$.topics[0].slug").isString())
                .andExpect(jsonPath("$.lastFetchedAt").doesNotExist())
                .andExpect(jsonPath("$.lastError").doesNotExist())
                .andReturn().getResponse().getContentAsString());
        int id = created.get("id").asInt();

        createSource(admin, feedUrl).andExpect(status().isConflict());

        mvc.perform(get("/v1/admin/sources").header("Authorization", admin.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + id + ")].siteUrl").value("https://test.example.org"))
                .andExpect(jsonPath("$[?(@.id == " + id + ")].topics[0].id").value(topic2));

        mvc.perform(patch("/v1/admin/sources/" + id).header("Authorization", admin.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\": false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
        mvc.perform(get("/v1/admin/sources").header("Authorization", admin.bearer()))
                .andExpect(jsonPath("$[?(@.id == " + id + ")].active").value(false));

        mvc.perform(delete("/v1/admin/sources/" + id).header("Authorization", admin.bearer()))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/v1/admin/sources/" + id).header("Authorization", admin.bearer()))
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app.source_topics WHERE source_id = ?", Integer.class, id))
                .isZero();
    }

    @Test
    void adminChangesTheTopicsOfASource() throws Exception {
        RegisteredUser admin = loginAdmin();
        int id = json.readTree(createSource(admin, feeds.url("/rss?run=" + UUID.randomUUID()),
                        "[%d, %d]".formatted(topic1, topic2))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asInt();

        // Inverser l'ordre : chaque thème prend la position de l'autre
        patchSource(admin, id, "{\"topicIds\": [%d, %d]}".formatted(topic2, topic1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.topics[0].id").value(topic2))
                .andExpect(jsonPath("$.topics[1].id").value(topic1))
                .andExpect(jsonPath("$.active").value(true));
        patchSource(admin, id, "{\"active\": false, \"topicIds\": [%d]}".formatted(topic3))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.topics.length()").value(1))
                .andExpect(jsonPath("$.topics[0].id").value(topic3))
                .andExpect(jsonPath("$.active").value(false));
        // Seulement active : les thèmes ne changent pas
        patchSource(admin, id, "{\"active\": true}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.topics[0].id").value(topic3));
        assertThat(jdbc.queryForList("SELECT position FROM app.source_topics WHERE source_id = ?", Integer.class, id))
                .containsExactly(1);
    }

    @Test
    void topicIdsAreChecked() throws Exception {
        RegisteredUser admin = loginAdmin();
        String feedUrl = feeds.url("/rss?run=" + UUID.randomUUID());
        createSource(admin, feedUrl, "[]")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("topicIds"));
        createSource(admin, feedUrl, "[%d, %d, %d, %d]".formatted(topic1, topic2, topic3, topic1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("topicIds"));
        createSource(admin, feedUrl, "[%d, %d]".formatted(topic1, topic1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Topic ids must not repeat"));
        createSource(admin, feedUrl, "[%d, 999999]".formatted(topic1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Unknown topic ids: [999999]"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app.sources WHERE feed_url = ?", Integer.class, feedUrl))
                .isZero();

        int id = json.readTree(createSource(admin, feedUrl, "[%d]".formatted(topic1))
                .andReturn().getResponse().getContentAsString()).get("id").asInt();
        patchSource(admin, id, "{\"topicIds\": []}").andExpect(status().isBadRequest());
        patchSource(admin, id, "{\"topicIds\": [999999]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Unknown topic ids: [999999]"));
        patchSource(admin, id, "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Nothing to update: give active or topicIds"));
        patchSource(admin, 999999, "{\"active\": false}").andExpect(status().isNotFound());
    }

    @Test
    void feedWithoutArticlesIsUnprocessable() throws Exception {
        RegisteredUser admin = loginAdmin();
        createSource(admin, feeds.url("/empty"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Feed has no articles"));
        createSource(admin, feeds.url("/missing"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Feed answered HTTP 404"));
        createSource(admin, feeds.url("/html"))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void sourceWithArticlesCannotBeDeleted() throws Exception {
        TestArticles.seed(jdbc, 1);
        Integer id = jdbc.queryForObject("SELECT id FROM app.sources WHERE feed_url = 'https://test.example.org/rss'",
                Integer.class);
        mvc.perform(delete("/v1/admin/sources/" + id).header("Authorization", loginAdmin().bearer()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Source has articles: deactivate it instead"));
    }

    @Test
    void invalidBodiesAreRejected() throws Exception {
        RegisteredUser admin = loginAdmin();
        mvc.perform(post("/v1/admin/sources").header("Authorization", admin.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "", "siteUrl": "ftp://x", "feedUrl": "not a url", "language": "french"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(5));
        mvc.perform(patch("/v1/admin/sources/1").header("Authorization", admin.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void readersAreForbidden() throws Exception {
        RegisteredUser reader = registerUser();
        mvc.perform(get("/v1/admin/sources").header("Authorization", reader.bearer()))
                .andExpect(status().isForbidden());
        createSource(reader, feeds.url("/rss")).andExpect(status().isForbidden());
        mvc.perform(delete("/v1/admin/sources/1").header("Authorization", reader.bearer()))
                .andExpect(status().isForbidden());
    }

    private ResultActions createSource(RegisteredUser user, String feedUrl) throws Exception {
        return createSource(user, feedUrl, "[%d]".formatted(topic1));
    }

    private ResultActions createSource(RegisteredUser user, String feedUrl, String topicIds) throws Exception {
        return mvc.perform(post("/v1/admin/sources").header("Authorization", user.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name": " Le Test ", "siteUrl": "https://test.example.org", "feedUrl": "%s",
                         "language": "fr", "license": "CC BY 4.0", "topicIds": %s}
                        """.formatted(feedUrl, topicIds)));
    }

    private ResultActions patchSource(RegisteredUser user, int id, String body) throws Exception {
        return mvc.perform(patch("/v1/admin/sources/" + id).header("Authorization", user.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
