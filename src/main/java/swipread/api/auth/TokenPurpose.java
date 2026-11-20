package swipread.api.auth;

/**
 * Usage d'un lien envoyé par mail ; la valeur est celle de la colonne app.user_tokens.purpose.
 */
public enum TokenPurpose {
    VERIFY_EMAIL("verify_email"),
    RESET_PASSWORD("reset_password");

    private final String value;

    TokenPurpose(String value) {
        this.value = value;
    }

    String value() {
        return value;
    }
}
