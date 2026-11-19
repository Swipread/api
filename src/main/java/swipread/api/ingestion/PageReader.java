package swipread.api.ingestion;

import jakarta.annotation.PreDestroy;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import swipread.api.source.SafeHttp;
import swipread.api.source.SafeHttpFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ouvre une fois la page d'un nouvel article pour compter les mots de son texte principal et trouver son image de
 * partage. Le texte n'est pas conservé. Respecte robots.txt (une lecture par hôte et par passe).
 */
@Component
class PageReader {

    static final int MAX_BYTES = 2 * 1024 * 1024;
    static final int MAX_ROBOTS_BYTES = 500 * 1024;
    private static final Logger log = LoggerFactory.getLogger(PageReader.class);
    private static final Map<String, String> ACCEPT_HTML = Map.of("Accept", "text/html, application/xhtml+xml;q=0.9");

    /** @param imageUrl null si la page n'en indique pas */
    record Page(int wordCount, String imageUrl) {
    }

    private final SafeHttp http;
    private final String robotName;
    private final Map<String, RobotsRules> robots = new ConcurrentHashMap<>();

    PageReader(SafeHttpFactory factory, IngestionProperties properties) {
        this.http = factory.create(Duration.ofSeconds(5), Duration.ofSeconds(5), properties.userAgent());
        this.robotName = properties.robotName();
    }

    /** À appeler au début de chaque passe : les robots.txt sont relus. */
    void startRun() {
        robots.clear();
    }

    /**
     * Vide si la page est interdite par robots.txt, injoignable, trop grosse, n'est pas du HTML, ou ne parle pas de
     * l'article (son titre n'y apparaît pas : page de défi anti-robot, de consentement…).
     */
    Optional<Page> read(String url, String title) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (Exception e) {
            return Optional.empty();
        }
        if (!rulesFor(uri).allows(pathAndQuery(uri))) {
            log.debug("robots.txt forbids {}", url);
            return Optional.empty();
        }
        try {
            SafeHttp.Response response = http.get(uri, ACCEPT_HTML, MAX_BYTES);
            String type = response.header("Content-Type");
            if (!response.ok() || type == null
                    || !(type.startsWith("text/html") || type.startsWith("application/xhtml+xml"))) {
                return Optional.empty();
            }
            // Jeu de caractères de l'en-tête, sinon celui que la page déclare (meta charset), détecté par jsoup
            Document page = Jsoup.parse(new ByteArrayInputStream(response.body()), charset(type), response.uri().toString());
            if (!ArticleText.isAbout(page, title)) {
                log.debug("Page {} does not show the article", url);
                return Optional.empty();
            }
            return Optional.of(new Page(ArticleText.pageWordCount(page),
                    ArticleText.shareImage(page, response.uri())));
        } catch (SafeHttp.FetchException e) {
            log.debug("Page {} not read: {}", url, e.failure());
            return Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    @PreDestroy
    void close() throws IOException {
        http.close();
    }

    /**
     * RFC 9309 : un robots.txt absent (4xx) autorise tout ; une erreur serveur (5xx) ou un hôte injoignable interdit
     * tout, faute de savoir.
     */
    private RobotsRules rulesFor(URI page) {
        String origin = page.getScheme().toLowerCase() + "://" + page.getRawAuthority();
        return robots.computeIfAbsent(origin, key -> {
            try {
                SafeHttp.Response response = http.get(URI.create(key + "/robots.txt"), Map.of(), MAX_ROBOTS_BYTES);
                if (response.ok()) {
                    return RobotsRules.parse(new String(response.body(), StandardCharsets.UTF_8), robotName);
                }
                return response.status() / 100 == 4 ? RobotsRules.ALLOW_ALL : RobotsRules.DISALLOW_ALL;
            } catch (SafeHttp.FetchException | IllegalArgumentException e) {
                return RobotsRules.DISALLOW_ALL;
            }
        });
    }

    private static String pathAndQuery(URI uri) {
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        return uri.getRawQuery() == null ? path : path + "?" + uri.getRawQuery();
    }

    /** Nom du jeu de caractères de l'en-tête s'il est connu, sinon null (jsoup le cherche dans la page). */
    private static String charset(String contentType) {
        for (String part : contentType.split(";")) {
            String p = part.strip();
            if (p.toLowerCase().startsWith("charset=")) {
                String name = p.substring(8).replace("\"", "").strip();
                return Charset.isSupported(name) ? name : null;
            }
        }
        return null;
    }
}
