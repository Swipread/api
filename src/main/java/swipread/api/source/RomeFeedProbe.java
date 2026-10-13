package swipread.api.source;

import com.rometools.rome.io.FeedException;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import jakarta.annotation.PreDestroy;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Set;

/**
 * Lit un flux avec Apache HttpClient 5. Son résolveur DNS est {@link FeedAddressPolicy#resolve(String)} : l'adresse
 * contrôlée est celle de la connexion (pas de DNS rebinding). Pas de proxy système, qui résoudrait lui-même l'hôte.
 */
@Component
class RomeFeedProbe implements FeedProbe {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(10);
    static final int MAX_BYTES = 5 * 1024 * 1024;
    static final int MAX_REDIRECTS = 5;
    private static final Set<Integer> REDIRECT_STATUSES = Set.of(301, 302, 303, 307, 308);

    private final FeedAddressPolicy addressPolicy;
    private final CloseableHttpClient http;

    RomeFeedProbe(FeedAddressPolicy addressPolicy) {
        this.addressPolicy = addressPolicy;
        DnsResolver dns = new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) throws UnknownHostException {
                return addressPolicy.resolve(host);
            }

            @Override
            public String resolveCanonicalHostname(String host) {
                return host;
            }
        };
        this.http = HttpClients.custom()
                .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                        .setDnsResolver(dns)
                        .setDefaultConnectionConfig(ConnectionConfig.custom()
                                .setConnectTimeout(Timeout.of(CONNECT_TIMEOUT))
                                .setSocketTimeout(Timeout.of(READ_TIMEOUT))
                                .build())
                        .build())
                .setDefaultRequestConfig(RequestConfig.custom().setResponseTimeout(Timeout.of(READ_TIMEOUT)).build())
                // Redirections suivies à la main : chaque destination passe par FeedAddressPolicy
                .disableRedirectHandling()
                .disableAutomaticRetries()
                .setUserAgent("Swipread/1.0 (+source check)")
                .build();
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
        try {
            for (int redirects = 0; ; redirects++) {
                addressPolicy.check(uri);
                Fetched fetched = fetch(uri);
                if (fetched.location() == null) {
                    return fetched.body();
                }
                if (redirects == MAX_REDIRECTS) {
                    throw new UnreadableFeedException("Feed redirects too many times", null);
                }
                uri = uri.resolve(fetched.location());
            }
        } catch (IllegalArgumentException e) {
            // Location invalide
            throw new UnreadableFeedException(FeedAddressPolicy.NOT_ALLOWED, e);
        } catch (IOException e) {
            if (hasForbiddenAddress(e)) {
                throw new UnreadableFeedException(FeedAddressPolicy.NOT_ALLOWED, e);
            }
            throw new UnreadableFeedException("Feed is unreachable", e);
        }
    }

    private Fetched fetch(URI uri) throws IOException {
        HttpGet request = new HttpGet(uri);
        request.setHeader("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml;q=0.9, */*;q=0.1");
        return http.execute(request, response -> {
            Header location = response.getFirstHeader("Location");
            if (REDIRECT_STATUSES.contains(response.getCode()) && location != null) {
                return new Fetched(null, location.getValue());
            }
            if (response.getCode() / 100 != 2) {
                throw new UnreadableFeedException("Feed answered HTTP " + response.getCode(), null);
            }
            HttpEntity entity = response.getEntity();
            if (entity == null) {
                return new Fetched(new byte[0], null);
            }
            try (InputStream in = entity.getContent()) {
                byte[] body = in.readNBytes(MAX_BYTES + 1);
                if (body.length > MAX_BYTES) {
                    throw new UnreadableFeedException("Feed is larger than " + MAX_BYTES / (1024 * 1024) + " MB", null);
                }
                return new Fetched(body, null);
            }
        });
    }

    private static boolean hasForbiddenAddress(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof FeedAddressPolicy.ForbiddenAddressException) {
                return true;
            }
        }
        return false;
    }

    private record Fetched(byte[] body, String location) {
    }
}
