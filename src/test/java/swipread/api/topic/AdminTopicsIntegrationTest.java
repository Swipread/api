package swipread.api.topic;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import swipread.api.IntegrationTest;
import swipread.api.feed.TestArticles;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminTopicsIntegrationTest extends IntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void adminCreatesListsAndRenamesTopics() throws Exception {
        RegisteredUser admin = loginAdmin();
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        JsonNode created = json.readTree(mvc.perform(post("/v1/admin/topics").header("Authorization", admin.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\": \" Jeux vidéo " + suffix + " \"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.slug").value("jeux-video-" + suffix))
                .andExpect(jsonPath("$.label").value("Jeux vidéo " + suffix))
                .andExpect(jsonPath("$.articleCount").value(0))
                .andExpect(jsonPath("$.readerCount").value(0))
                .andReturn().getResponse().getContentAsString());
        int id = created.get("id").asInt();

        // Même slug une fois les accents et la casse retirés
        mvc.perform(post("/v1/admin/topics").header("Authorization", admin.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\": \"JEUX VIDEO " + suffix + "\"}"))
                .andExpect(status().isConflict());

        // Compteurs : un article et un lecteur
        TestArticles.seed(jdbc, 1);
        jdbc.update("INSERT INTO app.article_topics (article_id, topic_id, confidence) VALUES ('a_it0001', ?, 0.7)", id);
        RegisteredUser reader = registerUser();
        mvc.perform(put("/v1/me/topics").header("Authorization", reader.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topicIds\": [1, 2, " + id + "]}"))
                .andExpect(status().isOk());

        mvc.perform(get("/v1/admin/topics").header("Authorization", admin.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + id + ")].articleCount").value(1))
                .andExpect(jsonPath("$[?(@.id == " + id + ")].readerCount").value(1))
                .andExpect(jsonPath("$[?(@.slug == 'tech')].label").value("Tech"));

        mvc.perform(patch("/v1/admin/topics/" + id).header("Authorization", admin.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\": \"Jeux " + suffix + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.label").value("Jeux " + suffix))
                .andExpect(jsonPath("$.slug").value("jeux-video-" + suffix))
                .andExpect(jsonPath("$.readerCount").value(1));

        // Le nouveau thème est proposé à l'onboarding
        mvc.perform(get("/v1/topics").header("Authorization", reader.bearer()))
                .andExpect(jsonPath("$[?(@.id == " + id + ")].label").value("Jeux " + suffix));
    }

    @Test
    void invalidRequestsAreRejected() throws Exception {
        RegisteredUser admin = loginAdmin();
        mvc.perform(post("/v1/admin/topics").header("Authorization", admin.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"label\": \" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("label"));
        mvc.perform(post("/v1/admin/topics").header("Authorization", admin.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"label\": \"!!!\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(patch("/v1/admin/topics/999999").header("Authorization", admin.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"label\": \"Nope\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void readersAreForbidden() throws Exception {
        RegisteredUser reader = registerUser();
        mvc.perform(get("/v1/admin/topics").header("Authorization", reader.bearer()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/v1/admin/topics").header("Authorization", reader.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"label\": \"Hack\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/v1/admin/topics/1").header("Authorization", reader.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"label\": \"Hack\"}"))
                .andExpect(status().isForbidden());
    }
}
