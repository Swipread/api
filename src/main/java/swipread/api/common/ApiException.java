package swipread.api.common;

import org.springframework.http.HttpStatus;

/**
 * Erreur métier traduite en ProblemDetail par {@link ApiExceptionHandler}.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String title;
    private final FieldRejection field;

    public ApiException(HttpStatus status, String title, String detail) {
        this(status, title, detail, null);
    }

    private ApiException(HttpStatus status, String title, String detail, FieldRejection field) {
        super(detail);
        this.status = status;
        this.title = title;
        this.field = field;
    }

    /**
     * Champ refusé par une règle métier : même format que les erreurs de validation (errors[] avec field et code),
     * pour que le client l'affiche sous le champ.
     */
    public record FieldRejection(String field, String code) {
    }

    public static ApiException fieldRejected(String field, String code, String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, "Bad request", detail, new FieldRejection(field, code));
    }

    public static ApiException badRequest(String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, "Bad request", detail);
    }

    public static ApiException unauthorized(String detail) {
        return new ApiException(HttpStatus.UNAUTHORIZED, "Unauthorized", detail);
    }

    public static ApiException notFound(String detail) {
        return new ApiException(HttpStatus.NOT_FOUND, "Not found", detail);
    }

    public static ApiException conflict(String detail) {
        return new ApiException(HttpStatus.CONFLICT, "Conflict", detail);
    }

    public static ApiException unprocessable(String detail) {
        return new ApiException(HttpStatus.valueOf(422), "Unprocessable content", detail);
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getTitle() {
        return title;
    }

    public FieldRejection getField() {
        return field;
    }
}
