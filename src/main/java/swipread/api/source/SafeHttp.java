package swipread.api.source;

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

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Requêtes GET vers des adresses données par des utilisateurs ou des flux (sources, pages d'articles, robots.txt),
 * avec la protection SSRF de {@link FeedAddressPolicy} : schéma et port contrôlés à chaque redirection, et résolveur
 * DNS du client HTTP = {@link FeedAddressPolicy#resolve(String)}, si bien que l'adresse contrôlée est celle de la
 * connexion (pas de DNS rebinding). Pas de proxy système, qui résoudrait lui-même l'hôte.
 */
public final class SafeHttp implements Closeable {

    public static final int MAX_REDIRECTS = 5;
    private static final Set<Integer> REDIRECT_STATUSES = Set.of(301, 302, 303, 307, 308);

    /** Pourquoi une requête n'a pas abouti à une réponse. */
    public enum Failure { NOT_ALLOWED, UNREACHABLE, TOO_MANY_REDIRECTS, TOO_LARGE }

    public static final class FetchException extends RuntimeException {
        private final Failure failure;

        FetchException(Failure failure, Throwable cause) {
            super(failure.name(), cause);
            this.failure = failure;
        }

        public Failure failure() {
            return failure;
        }
    }

    /**
     * Réponse finale, après les redirections. Le corps n'est lu que pour un statut 2xx ; il est vide sinon.
     *
     * @param uri adresse qui a répondu (après redirections)
     */
    public record Response(int status, URI uri, Map<String, String> headers, byte[] body) {

        public boolean ok() {
            return status / 100 == 2;
        }

        /** Valeur de l'en-tête, nom insensible à la casse, ou null. */
        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    private final FeedAddressPolicy addressPolicy;
    private final CloseableHttpClient http;

    SafeHttp(FeedAddressPolicy addressPolicy, Duration connectTimeout, Duration readTimeout, String userAgent) {
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
                                .setConnectTimeout(Timeout.of(connectTimeout))
                                .setSocketTimeout(Timeout.of(readTimeout))
                                .build())
                        .build())
                .setDefaultRequestConfig(RequestConfig.custom().setResponseTimeout(Timeout.of(readTimeout)).build())
                // Redirections suivies à la main : chaque destination passe par FeedAddressPolicy
                .disableRedirectHandling()
                .disableAutomaticRetries()
                .setUserAgent(userAgent)
                .build();
    }

    /**
     * @param headers  en-têtes de la requête (Accept, If-None-Match…)
     * @param maxBytes taille maximale du corps d'une réponse 2xx
     * @throws FetchException si l'adresse est interdite, injoignable, redirige trop ou renvoie un corps trop gros
     */
    public Response get(URI uri, Map<String, String> headers, int maxBytes) {
        try {
            for (int redirects = 0; ; redirects++) {
                try {
                    addressPolicy.check(uri);
                } catch (FeedProbe.UnreadableFeedException e) {
                    throw new FetchException(Failure.NOT_ALLOWED, e);
                }
                Response response = fetch(uri, headers, maxBytes);
                String location = response.header("Location");
                if (!REDIRECT_STATUSES.contains(response.status()) || location == null) {
                    return response;
                }
                if (redirects == MAX_REDIRECTS) {
                    throw new FetchException(Failure.TOO_MANY_REDIRECTS, null);
                }
                uri = uri.resolve(location);
            }
        } catch (IllegalArgumentException e) {
            // Location invalide
            throw new FetchException(Failure.NOT_ALLOWED, e);
        } catch (IOException e) {
            throw new FetchException(hasForbiddenAddress(e) ? Failure.NOT_ALLOWED : Failure.UNREACHABLE, e);
        }
    }

    @Override
    public void close() throws IOException {
        http.close();
    }

    private Response fetch(URI uri, Map<String, String> requestHeaders, int maxBytes) throws IOException {
        HttpGet request = new HttpGet(uri);
        requestHeaders.forEach(request::setHeader);
        return http.execute(request, response -> {
            Map<String, String> headers = new java.util.HashMap<>();
            for (Header header : response.getHeaders()) {
                headers.putIfAbsent(header.getName().toLowerCase(Locale.ROOT), header.getValue());
            }
            int status = response.getCode();
            HttpEntity entity = response.getEntity();
            if (status / 100 != 2 || entity == null) {
                return new Response(status, uri, headers, new byte[0]);
            }
            try (InputStream in = entity.getContent()) {
                byte[] body = in.readNBytes(maxBytes + 1);
                if (body.length > maxBytes) {
                    throw new FetchException(Failure.TOO_LARGE, null);
                }
                return new Response(status, uri, headers, body);
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
}
