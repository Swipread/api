package swipread.api.common;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;

@Configuration(proxyBeanMethods = false)
@OpenAPIDefinition(
        info = @Info(title = "Swipread API", version = "v1"),
        security = @SecurityRequirement(name = OpenApiConfig.BEARER))
@SecurityScheme(name = OpenApiConfig.BEARER, type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
public class OpenApiConfig {

    public static final String BEARER = "bearer";

    static final String PROBLEM_DETAIL = "ProblemDetail";
    static final String FIELD_ERROR = "FieldError";
    static final String ADMIN_ROUTES = "/v1/admin/";

    /**
     * Toutes les réponses 4xx sont des ProblemDetail. Les 400, 401 et 403 (routes admin) sont ajoutées d'office ;
     * les autres codes sont déclarés par {@code @ApiResponse} sur les opérations concernées, et reçoivent ici leur
     * schéma.
     */
    @Bean
    OpenApiCustomizer problemDetailResponses() {
        return openApi -> {
            openApi.getComponents()
                    .addSchemas(FIELD_ERROR, new ObjectSchema()
                            .addProperty("field", new StringSchema())
                            .addProperty("code", new StringSchema()
                                    .description("Violated constraint, e.g. NotBlank, Size, Email, Pattern"))
                            .addProperty("message", new StringSchema().description("Always in English")))
                    .addSchemas(PROBLEM_DETAIL, new ObjectSchema()
                            .description("Error body (RFC 9457)")
                            .addProperty("type", new StringSchema())
                            .addProperty("title", new StringSchema())
                            .addProperty("status", new IntegerSchema())
                            .addProperty("detail", new StringSchema())
                            .addProperty("instance", new StringSchema())
                            .addProperty("errors", new ArraySchema()
                                    .items(new Schema<>().$ref(FIELD_ERROR))
                                    .description("Invalid fields, only on request validation failures")));
            openApi.getPaths().forEach((route, path) -> path.readOperations()
                    .forEach(operation -> addErrors(route, operation)));
        };
    }

    private static void addErrors(String route, Operation operation) {
        ApiResponses responses = operation.getResponses();
        boolean hasInput = operation.getRequestBody() != null
                || (operation.getParameters() != null && !operation.getParameters().isEmpty());
        if (hasInput) {
            responses.putIfAbsent("400", new ApiResponse().description("Invalid request"));
        }
        // Sécurité nulle : la sécurité globale (bearer) s'applique ; liste vide : opération publique
        boolean secured = operation.getSecurity() == null || !operation.getSecurity().isEmpty();
        if (secured) {
            responses.putIfAbsent("401", new ApiResponse().description("Missing or invalid access token"));
        }
        if (route.startsWith(ADMIN_ROUTES)) {
            responses.putIfAbsent("403", new ApiResponse().description("Requires the ADMIN role"));
        }
        responses.forEach((code, response) -> {
            if (code.startsWith("4")) {
                response.setContent(new Content().addMediaType(MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                        new io.swagger.v3.oas.models.media.MediaType().schema(new Schema<>().$ref(PROBLEM_DETAIL))));
            }
        });
    }
}
