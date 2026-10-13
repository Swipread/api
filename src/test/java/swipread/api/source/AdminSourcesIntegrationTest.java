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
import java.util.UUID;

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

    @BeforeEach
    void start() throws IOException {
        feeds = new TestFeedServer();
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

        JsonNode created = json.readTree(createSource(admin, feedUrl)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Le Test"))
                .andExpect(jsonPath("$.feedUrl").value(feedUrl))
                .andExpect(jsonPath("$.language").value("fr"))
                .andExpect(jsonPath("$.license").value("CC BY 4.0"))
                .andExpect(jsonPath("$.active").value(true))
                .andReturn().getResponse().getContentAsString());
        int id = created.get("id").asInt();

        createSource(admin, feedUrl).andExpect(status().isConflict());

        mvc.perform(get("/v1/admin/sources").header("Authorization", admin.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + id + ")].siteUrl").value("https://test.example.org"));

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
                .andExpect(jsonPath("$.errors.length()").value(4));
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
        return mvc.perform(post("/v1/admin/sources").header("Authorization", user.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name": " Le Test ", "siteUrl": "https://test.example.org", "feedUrl": "%s",
                         "language": "fr", "license": "CC BY 4.0"}
                        """.formatted(feedUrl)));
    }
}
