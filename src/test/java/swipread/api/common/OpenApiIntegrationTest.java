package swipread.api.common;

import org.junit.jupiter.api.Test;
import swipread.api.IntegrationTest;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OpenApiIntegrationTest extends IntegrationTest {

    @Test
    void apiDocsArePublicAndDescribeEndpoints() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.securitySchemes.bearer.scheme").value("bearer"))
                .andExpect(jsonPath("$.paths['/v1/feed'].get").exists())
                .andExpect(jsonPath("$.paths['/v1/events'].post").exists())
                .andExpect(jsonPath("$.paths['/v1/me/saved'].get").exists())
                .andExpect(jsonPath("$.paths['/v1/me/history'].get").exists())
                .andExpect(jsonPath("$.paths['/v1/auth/login'].post.security").isEmpty())
                // L'identifiant issu du token ne doit pas apparaître comme paramètre
                .andExpect(content().string(not(containsString("\"userId\",\"in\""))));
    }

    @Test
    void responsesAreJsonAndErrorsAreProblemDetails() throws Exception {
        String problem = "#/components/schemas/ProblemDetail";
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("\"*/*\""))))
                .andExpect(jsonPath("$.components.schemas.ProblemDetail.properties.errors.items['$ref']")
                        .value("#/components/schemas/FieldError"))
                .andExpect(jsonPath("$.components.schemas.FieldError.properties.code.type").value("string"))
                .andExpect(jsonPath("$.paths['/v1/auth/register'].post.responses['201'].content['application/json'].schema['$ref']")
                        .value("#/components/schemas/TokenResponse"))
                .andExpect(jsonPath("$.paths['/v1/auth/register'].post.responses['400'].content['application/problem+json'].schema['$ref']")
                        .value(problem))
                .andExpect(jsonPath("$.paths['/v1/auth/register'].post.responses['409'].content['application/problem+json'].schema['$ref']")
                        .value(problem))
                .andExpect(jsonPath("$.paths['/v1/auth/register'].post.responses['401']").doesNotExist())
                .andExpect(jsonPath("$.paths['/v1/auth/login'].post.responses['401'].content['application/problem+json'].schema['$ref']")
                        .value(problem))
                .andExpect(jsonPath("$.paths['/v1/feed'].get.responses['401'].content['application/problem+json'].schema['$ref']")
                        .value(problem))
                .andExpect(jsonPath("$.paths['/v1/topics'].get.responses['400']").doesNotExist())
                .andExpect(jsonPath("$.paths['/v1/admin/topics'].get.responses['403'].content['application/problem+json'].schema['$ref']")
                        .value(problem))
                .andExpect(jsonPath("$.paths['/v1/me/topics'].put.responses['403']").doesNotExist())
                .andExpect(jsonPath("$.components.schemas.TokenResponse.properties.expiresIn.description")
                        .value("Lifetime of the access token, in seconds"));
    }
}
