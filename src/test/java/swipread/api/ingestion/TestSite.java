package swipread.api.ingestion;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Site d'information local pour les tests d'ingestion : un flux RSS, les pages de ses articles et un robots.txt.
 */
final class TestSite implements AutoCloseable {

    static final String ETAG = "\"v1\"";

    private final HttpServer server;
    private final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();
    private final Map<String, String> lastIfNoneMatch = new ConcurrentHashMap<>();

    TestSite() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    String url(String path) {
        return "http://localhost:" + server.getAddress().getPort() + path;
    }

    int hits(String path) {
        return hits.getOrDefault(path, new AtomicInteger()).get();
    }

    String lastIfNoneMatch(String path) {
        return lastIfNoneMatch.get(path);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    /** Date RFC 822 d'il y a {@code hours} heures, comme dans un flux RSS. */
    static String hoursAgo(int hours) {
        return DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.now().minusSeconds(hours * 3600L).atZone(ZoneOffset.UTC));
    }

    /**
     * Page d'article : son titre dans head (non compté), {@code words} mots dans l'article, plus une navigation, une
     * carte « à lire aussi » imbriquée et un pied de page, ignorés.
     */
    static String articlePage(String title, int words, String image) {
        String meta = image == null ? "" : "<meta property=\"og:image\" content=\"" + image + "\">";
        return "<!doctype html><html><head><meta charset=\"utf-8\"><title>" + title + " - Le Site</title>" + meta
                + "</head><body>"
                + "<nav>Accueil Monde Sport Culture</nav><article><p>" + "mot ".repeat(words).strip() + "</p>"
                + "<article>A lire aussi : un autre sujet tout aussi passionnant</article></article>"
                + "<footer>Mentions légales du site</footer></body></html>";
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        hits.computeIfAbsent(path, p -> new AtomicInteger()).incrementAndGet();
        String ifNoneMatch = exchange.getRequestHeaders().getFirst("If-None-Match");
        if (ifNoneMatch != null) {
            lastIfNoneMatch.put(path, ifNoneMatch);
        }
        switch (path) {
            case "/robots.txt" -> send(exchange, 200, "text/plain", "User-agent: *\nDisallow: /prive/\n");
            case "/rss" -> {
                if (ETAG.equals(ifNoneMatch)) {
                    exchange.sendResponseHeaders(304, -1);
                    exchange.close();
                    return;
                }
                exchange.getResponseHeaders().add("ETag", ETAG);
                send(exchange, 200, "application/rss+xml", feed());
            }
            case "/broken" -> send(exchange, 404, "text/plain", "not found");
            case "/many" -> send(exchange, 200, "application/rss+xml", many(35));
            case "/a/1" -> send(exchange, 200, "text/html; charset=utf-8", articlePage("Premier &amp; article", 850, "/img/page-1.jpg"));
            // Nombre de mots déclaré en schema.org : il prime sur le texte compté
            case "/a/2" -> send(exchange, 200, "text/html", articlePage("Deuxième", 120, null).replace("</head>",
                    "<script type=\"application/ld+json\">{\"@type\": \"NewsArticle\", \"wordCount\": 345}</script></head>"));
            // Défi anti-robot servi en 200 à la place de l'article
            case "/a/defi" -> send(exchange, 200, "text/html", "<html><head><title>Client Challenge</title>"
                    + "<meta property=\"og:image\" content=\"/img/challenge.jpg\"></head><body><article>"
                    + "Please enable JavaScript and cookies to continue, your request is being verified</article></body></html>");
            case "/a/pdf" -> send(exchange, 200, "application/pdf", "%PDF-1.4");
            default -> {
                if (path.startsWith("/m/")) {
                    send(exchange, 200, "text/html", articlePage("N" + path.substring(3), 300, null));
                } else {
                    send(exchange, 404, "text/plain", "not found");
                }
            }
        }
    }

    /**
     * Six entrées utiles et deux à écarter : 1 (page de 850 mots, image de la page), 2 (image Media RSS, page de 120
     * mots), prive (page interdite par robots.txt : mots du contenu du flux ; media:content sans medium), pdf (pas du
     * HTML : mots du titre et du résumé), defi (page de défi anti-robot ignorée), sans date (datée de la lecture) ;
     * une entrée de 10 jours (trop vieille) et un doublon de 1.
     */
    private String feed() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <rss version="2.0" xmlns:media="http://search.yahoo.com/mrss/" xmlns:content="http://purl.org/rss/1.0/modules/content/">
                <channel><title>Le Site</title><link>%1$s</link><description>Test</description>
                  <item><title>Premier &amp; article</title><link>%1$s/a/1#haut</link><author>Ana Dupont</author>
                    <description>&lt;p&gt;Résumé &lt;b&gt;du premier&lt;/b&gt;&lt;/p&gt;</description><pubDate>%2$s</pubDate></item>
                  <item><title>Deuxième</title><link>%1$s/a/2</link><description>Résumé deux</description>
                    <media:content url="%1$s/img/media-2.jpg" medium="image"/><pubDate>%3$s</pubDate></item>
                  <item><title>Privé</title><link>%1$s/prive/3</link><description>Résumé</description>
                    <media:content url="%1$s/img/prive" width="644" height="322"/>
                    <content:encoded>&lt;p&gt;Un deux trois quatre cinq six sept huit neuf dix&lt;/p&gt;</content:encoded>
                    <pubDate>%3$s</pubDate></item>
                  <item><title>Document</title><link>%1$s/a/pdf</link><description>Quatre mots de résumé</description>
                    <pubDate>%3$s</pubDate></item>
                  <item><title>Défi du jour</title><link>%1$s/a/defi</link><description>Un résumé de six mots ici</description>
                    <pubDate>%3$s</pubDate></item>
                  <item><title>Sans date</title><link>%1$s/a/sans-date</link><description>Rien</description></item>
                  <item><title>Ancien</title><link>%1$s/a/vieux</link><description>Trop vieux</description><pubDate>%4$s</pubDate></item>
                  <item><title>Premier, encore</title><link>%1$s/a/1</link><description>Doublon</description><pubDate>%5$s</pubDate></item>
                </channel></rss>
                """.formatted(url(""), hoursAgo(1), hoursAgo(2), hoursAgo(240), hoursAgo(5));
    }

    /** {@code count} entrées, de la plus récente (/m/0, il y a 1 h) à la plus ancienne. */
    private String many(int count) {
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < count; i++) {
            items.append("<item><title>N").append(i).append("</title><link>").append(url("/m/" + i))
                    .append("</link><description>d</description><pubDate>").append(hoursAgo(1 + i)).append("</pubDate></item>");
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><rss version=\"2.0\"><channel><title>Beaucoup</title><link>"
                + url("") + "</link><description>d</description>" + items + "</channel></rss>";
    }

    private static void send(HttpExchange exchange, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", type);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
