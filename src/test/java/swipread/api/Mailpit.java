package swipread.api;

import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.awaitility.Awaitility.await;

/**
 * Lecture des mails reçus par le Mailpit des tests, par son API HTTP.
 */
public final class Mailpit {

    private static final Pattern TOKEN = Pattern.compile("[?&]token=([A-Za-z0-9_-]+)");

    private final RestClient client;

    public Mailpit(String apiUrl) {
        this.client = RestClient.create(apiUrl);
    }

    /**
     * @return le texte du premier mail reçu par {@code to} dont l'objet est {@code subject}, attendu 10 s au plus
     */
    public String awaitText(String to, String subject) {
        String id = await().atMost(Duration.ofSeconds(10)).until(() -> find(to, subject), found -> found != null);
        return client.get().uri("/api/v1/message/{id}", id).retrieve().body(JsonNode.class).get("Text").asString();
    }

    public int count(String to) {
        return search(to).get("messages").size();
    }

    /**
     * @return le paramètre token du premier lien du texte
     */
    public static String token(String text) {
        Matcher matcher = TOKEN.matcher(text);
        if (!matcher.find()) {
            throw new AssertionError("No link with a token in:\n" + text);
        }
        return matcher.group(1);
    }

    private String find(String to, String subject) {
        for (JsonNode message : search(to).get("messages")) {
            if (message.get("Subject").asString().equals(subject)) {
                return message.get("ID").asString();
            }
        }
        return null;
    }

    private JsonNode search(String to) {
        return client.get().uri("/api/v1/search?query={q}", "to:\"" + to + "\"").retrieve().body(JsonNode.class);
    }
}
