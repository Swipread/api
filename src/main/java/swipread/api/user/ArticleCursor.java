package swipread.api.user;

import swipread.api.common.ApiException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;

/**
 * Curseur opaque des listes d'articles paginées par date : position (date, article_id) du dernier élément renvoyé.
 */
record ArticleCursor(Instant at, String articleId) {

    String encode() {
        String raw = at + "|" + articleId;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    static ArticleCursor decode(String value) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\|", 2);
            return new ArticleCursor(Instant.parse(parts[0]), parts[1]);
        } catch (IllegalArgumentException | DateTimeParseException | ArrayIndexOutOfBoundsException e) {
            throw ApiException.badRequest("Invalid cursor");
        }
    }
}
