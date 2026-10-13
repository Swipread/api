package swipread.api.source;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RomeFeedProbeTest {

    private final RomeFeedProbe probe = new RomeFeedProbe(new FeedAddressPolicy(true, InetAddress::getAllByName));
    private TestFeedServer server;

    @BeforeEach
    void start() throws IOException {
        server = new TestFeedServer();
    }

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void countsRssAndAtomEntries() {
        assertThat(probe.countEntries(server.url("/rss"))).isEqualTo(2);
        assertThat(probe.countEntries(server.url("/atom"))).isEqualTo(1);
        assertThat(probe.countEntries(server.url("/empty"))).isZero();
    }

    @Test
    void rejectsWhatIsNotAFeed() {
        assertThatThrownBy(() -> probe.countEntries(server.url("/html")))
                .isInstanceOf(FeedProbe.UnreadableFeedException.class)
                .hasMessage("Not a valid RSS or Atom feed");
        assertThatThrownBy(() -> probe.countEntries(server.url("/missing")))
                .isInstanceOf(FeedProbe.UnreadableFeedException.class)
                .hasMessage("Feed answered HTTP 404");
    }

    @Test
    void refusesExternalEntities() {
        assertThatThrownBy(() -> probe.countEntries(server.url("/xxe")))
                .isInstanceOf(FeedProbe.UnreadableFeedException.class);
    }

    @Test
    void unreachableHostIsUnreadable() throws IOException {
        int freePort;
        try (ServerSocket socket = new ServerSocket(0)) {
            freePort = socket.getLocalPort();
        }
        assertThatThrownBy(() -> probe.countEntries("http://localhost:" + freePort + "/rss"))
                .isInstanceOf(FeedProbe.UnreadableFeedException.class)
                .hasMessage("Feed is unreachable");
    }

    @Test
    void followsRedirects() {
        assertThat(probe.countEntries(server.url("/redirect"))).isEqualTo(2);
    }

    @Test
    void checksEveryRedirectTarget() {
        // Politique de test : tout est permis sauf les chemins /blocked
        FeedAddressPolicy policy = new FeedAddressPolicy(true, InetAddress::getAllByName) {
            @Override
            void check(URI uri) {
                if (uri.getPath().startsWith("/blocked")) {
                    throw new FeedProbe.UnreadableFeedException(NOT_ALLOWED, null);
                }
            }
        };
        RomeFeedProbe guarded = new RomeFeedProbe(policy);
        assertThatThrownBy(() -> guarded.countEntries(server.url("/redirect-blocked")))
                .isInstanceOf(FeedProbe.UnreadableFeedException.class)
                .hasMessage("Feed address is not allowed");
    }

    @Test
    void stopsAfterTooManyRedirects() {
        assertThatThrownBy(() -> probe.countEntries(server.url("/loop")))
                .isInstanceOf(FeedProbe.UnreadableFeedException.class)
                .hasMessage("Feed redirects too many times");
    }

    @Test
    void connectsThroughThePolicyResolver() {
        // Nom inventé, connu du seul résolveur de la politique : si le client HTTP faisait sa propre résolution DNS,
        // la connexion échouerait. Contrôle et connexion utilisent donc bien la même adresse.
        RomeFeedProbe resolving = new RomeFeedProbe(new FeedAddressPolicy(true,
                host -> host.equals("feeds.swipread.test")
                        ? new InetAddress[]{InetAddress.getByName("127.0.0.1")}
                        : InetAddress.getAllByName(host)));
        String url = server.url("/rss").replace("localhost", "feeds.swipread.test");
        assertThat(resolving.countEntries(url)).isEqualTo(2);
    }

    @Test
    void checksTheAddressItConnectsTo() {
        // Port 80 et schéma http autorisés : seule la résolution, faite par le client HTTP lui-même, peut refuser.
        // Un domaine qui pointerait vers 127.0.0.1 au moment de la connexion (DNS rebinding) est donc arrêté.
        RomeFeedProbe strict = new RomeFeedProbe(new FeedAddressPolicy(false,
                host -> new InetAddress[]{InetAddress.getByName("127.0.0.1")}));
        assertThatThrownBy(() -> strict.countEntries("http://rebinding.example.org/rss"))
                .isInstanceOf(FeedProbe.UnreadableFeedException.class)
                .hasMessage("Feed address is not allowed");
    }

    @Test
    void unknownHostIsUnreachable() {
        RomeFeedProbe strict = new RomeFeedProbe(new FeedAddressPolicy(false, host -> {
            throw new UnknownHostException(host);
        }));
        assertThatThrownBy(() -> strict.countEntries("https://unknown.example.org/rss"))
                .isInstanceOf(FeedProbe.UnreadableFeedException.class)
                .hasMessage("Feed is unreachable");
    }

    @Test
    void refusesLocalFeedsByDefault() {
        RomeFeedProbe strict = new RomeFeedProbe(new FeedAddressPolicy(false, InetAddress::getAllByName));
        assertThatThrownBy(() -> strict.countEntries(server.url("/rss")))
                .isInstanceOf(FeedProbe.UnreadableFeedException.class)
                .hasMessage("Feed address is not allowed");
    }
}
