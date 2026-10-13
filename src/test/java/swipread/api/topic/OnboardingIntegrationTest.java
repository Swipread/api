package swipread.api.topic;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import swipread.api.IntegrationTest;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OnboardingIntegrationTest extends IntegrationTest {

    @Test
    void userChoosesTopicsAndSeesThemOnProfile() throws Exception {
        RegisteredUser user = registerUser();

        mvc.perform(get("/v1/topics").header("Authorization", user.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(greaterThanOrEqualTo(8)));

        mvc.perform(get("/v1/me").header("Authorization", user.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(user.userId()))
                .andExpect(jsonPath("$.locale").value("fr-FR"))
                .andExpect(jsonPath("$.topics.length()").value(0));

        putTopics(user, "[1, 2, 3]");
        // Le second PUT remplace le premier
        mvc.perform(put("/v1/me/topics").header("Authorization", user.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"topicIds\": [2, 3, 4]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", containsInAnyOrder(2, 3, 4)));

        mvc.perform(get("/v1/me").header("Authorization", user.bearer()))
                .andExpect(jsonPath("$.topics[*].id", containsInAnyOrder(2, 3, 4)));
    }

    @Test
    void unknownTopicIsRejected() throws Exception {
        RegisteredUser user = registerUser();
        mvc.perform(put("/v1/me/topics").header("Authorization", user.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"topicIds\": [1, 2, 9999]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Unknown topic ids: [9999]"));
        mvc.perform(put("/v1/me/topics").header("Authorization", user.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"topicIds\": []}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void atLeastThreeTopicsAreRequired() throws Exception {
        RegisteredUser user = registerUser();
        mvc.perform(put("/v1/me/topics").header("Authorization", user.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"topicIds\": [1, 2]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("topicIds"));
    }

    private void putTopics(RegisteredUser user, String ids) throws Exception {
        mvc.perform(put("/v1/me/topics").header("Authorization", user.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"topicIds\": " + ids + "}"))
                .andExpect(status().isOk());
    }
}
