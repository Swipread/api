package swipread.api.source;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * Petit serveur HTTP local qui sert des flux de test.
 */
final class TestFeedServer implements AutoCloseable {

    static final String RSS_TWO_ITEMS = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0"><channel><title>Test</title><link>https://example.org</link><description>d</description>
              <item><title>Un</title><link>https://example.org/1</link></item>
              <item><title>Deux</title><link>https://example.org/2</link></item>
            </channel></rss>
            """;
    static final String RSS_EMPTY = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0"><channel><title>Vide</title><link>https://example.org</link><description>d</description></channel></rss>
            """;
    static final String ATOM_ONE_ENTRY = """
            <?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom"><title>Atom</title><id>urn:test</id><updated>2026-10-12T10:00:00Z</updated>
              <entry><title>Un</title><id>urn:test:1</id><updated>2026-10-12T10:00:00Z</updated></entry>
            </feed>
            """;
    static final String HTML = "<!doctype html><html><body>Pas un flux</body></html>";
    static final String XXE = """
            <?xml version="1.0"?>
            <!DOCTYPE rss [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <rss version="2.0"><channel><title>&xxe;</title><item><title>Un</title></item></channel></rss>
            """;

    private final HttpServer server;

    TestFeedServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        serve("/rss", 200, RSS_TWO_ITEMS);
        serve("/empty", 200, RSS_EMPTY);
        serve("/atom", 200, ATOM_ONE_ENTRY);
        serve("/html", 200, HTML);
        serve("/xxe", 200, XXE);
        serve("/missing", 404, "not found");
        redirect("/redirect", "/rss");
        redirect("/redirect-blocked", "/blocked/rss");
        redirect("/loop", "/loop");
        serve("/blocked/rss", 200, RSS_TWO_ITEMS);
        server.start();
    }

    String url(String path) {
        return "http://localhost:" + server.getAddress().getPort() + path;
    }

    private void serve(String path, int status, String body) {
        server.createContext(path, exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/xml; charset=utf-8");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
    }

    private void redirect(String path, String location) {
        server.createContext(path, exchange -> {
            exchange.getResponseHeaders().add("Location", location);
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
