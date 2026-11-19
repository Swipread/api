package swipread.api.source;

import com.rometools.rome.io.FeedException;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Map;

/**
 * Vérifie un flux à la création d'une source, par {@link SafeHttp} (protection SSRF, redirections contrôlées).
 */
@Component
class RomeFeedProbe implements FeedProbe {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(10);
    static final int MAX_BYTES = 5 * 1024 * 1024;
    static final int MAX_REDIRECTS = SafeHttp.MAX_REDIRECTS;
    static final Map<String, String> ACCEPT_FEEDS = Map.of("Accept",
            "application/rss+xml, application/atom+xml, application/xml, text/xml;q=0.9, */*;q=0.1");

    private final SafeHttp http;

    RomeFeedProbe(FeedAddressPolicy addressPolicy) {
        this.http = new SafeHttp(addressPolicy, CONNECT_TIMEOUT, READ_TIMEOUT, "Swipread/1.0 (+source check)");
    }

    @Override
    public int countEntries(String feedUrl) {
        byte[] body = download(feedUrl);
        try {
            // Rome refuse les DOCTYPE par défaut, ce qui protège des entités externes (XXE)
            return new SyndFeedInput().build(new XmlReader(new ByteArrayInputStream(body))).getEntries().size();
        } catch (FeedException | IOException | IllegalArgumentException e) {
            throw new UnreadableFeedException("Not a valid RSS or Atom feed", e);
        }
    }

    @PreDestroy
    void close() throws IOException {
        http.close();
    }

    private byte[] download(String feedUrl) {
        URI uri;
        try {
            uri = new URI(feedUrl);
        } catch (URISyntaxException e) {
            throw new UnreadableFeedException(FeedAddressPolicy.NOT_ALLOWED, e);
        }
        SafeHttp.Response response;
        try {
            response = http.get(uri, ACCEPT_FEEDS, MAX_BYTES);
        } catch (SafeHttp.FetchException e) {
            throw new UnreadableFeedException(message(e.failure()), e);
        }
        if (!response.ok()) {
            throw new UnreadableFeedException("Feed answered HTTP " + response.status(), null);
        }
        return response.body();
    }

    /** Messages montrés tels quels à l'admin (contrat de l'API). */
    static String message(SafeHttp.Failure failure) {
        return switch (failure) {
            case NOT_ALLOWED -> FeedAddressPolicy.NOT_ALLOWED;
            case UNREACHABLE -> "Feed is unreachable";
            case TOO_MANY_REDIRECTS -> "Feed redirects too many times";
            case TOO_LARGE -> "Feed is larger than " + MAX_BYTES / (1024 * 1024) + " MB";
        };
    }
}
