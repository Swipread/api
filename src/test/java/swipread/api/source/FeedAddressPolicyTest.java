package swipread.api.source;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FeedAddressPolicyTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "0.0.0.0", "127.0.0.1", "10.1.2.3", "100.64.0.1", "169.254.169.254", "172.16.0.1", "172.31.255.255",
            "192.168.1.1", "224.0.0.1", "255.255.255.255",
            "::", "::1", "fe80::1", "fc00::1", "fd12:3456::1", "::ffff:127.0.0.1", "::ffff:10.0.0.1",
            "64:ff9b::a9fe:a9fe", "ff02::1"})
    void forbidsInternalAddresses(String ip) throws UnknownHostException {
        assertThat(FeedAddressPolicy.isForbidden(InetAddress.getByName(ip))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"93.184.216.34", "172.32.0.1", "100.128.0.1", "2606:2800:220:1::1", "64:ff9b::5db8:d822"})
    void allowsPublicAddresses(String ip) throws UnknownHostException {
        assertThat(FeedAddressPolicy.isForbidden(InetAddress.getByName(ip))).isFalse();
    }

    @Test
    void acceptsAPublicFeedOnStandardPorts() {
        FeedAddressPolicy policy = resolvingTo("93.184.216.34");
        assertThatCode(() -> policy.check(URI.create("https://news.example.org/rss"))).doesNotThrowAnyException();
        assertThatCode(() -> policy.check(URI.create("http://news.example.org:80/rss"))).doesNotThrowAnyException();
        assertThatCode(() -> policy.check(URI.create("https://news.example.org:443/rss"))).doesNotThrowAnyException();
    }

    @Test
    void refusesOtherPortsAndSchemes() {
        FeedAddressPolicy policy = resolvingTo("93.184.216.34");
        assertNotAllowed(policy, "http://news.example.org:8080/rss");
        assertNotAllowed(policy, "ftp://news.example.org/rss");
        assertNotAllowed(policy, "file:///etc/passwd");
    }

    @Test
    void resolvesAPublicHost() throws UnknownHostException {
        assertThat(resolvingTo("93.184.216.34").resolve("news.example.org"))
                .containsExactly(InetAddress.getByName("93.184.216.34"));
    }

    @Test
    void refusesAHostWithOneInternalAddress() {
        assertThatThrownBy(() -> resolvingTo("93.184.216.34", "10.0.0.5").resolve("news.example.org"))
                .isInstanceOf(FeedAddressPolicy.ForbiddenAddressException.class);
    }

    @Test
    void unknownHostIsNotReportedAsForbidden() {
        FeedAddressPolicy policy = new FeedAddressPolicy(false, host -> {
            throw new UnknownHostException(host);
        });
        assertThatThrownBy(() -> policy.resolve("unknown.example.org"))
                .isInstanceOf(UnknownHostException.class)
                .isNotInstanceOf(FeedAddressPolicy.ForbiddenAddressException.class);
    }

    @Test
    void localFeedsCanBeAllowedButNotOtherSchemes() throws UnknownHostException {
        FeedAddressPolicy policy = new FeedAddressPolicy(true, host -> InetAddress.getAllByName("127.0.0.1"));
        assertThatCode(() -> policy.check(URI.create("http://localhost:54321/rss"))).doesNotThrowAnyException();
        assertThat(policy.resolve("localhost")).hasSize(1);
        assertNotAllowed(policy, "file:///etc/passwd");
    }

    private static FeedAddressPolicy resolvingTo(String... ips) {
        return new FeedAddressPolicy(false, host -> {
            InetAddress[] addresses = new InetAddress[ips.length];
            for (int i = 0; i < ips.length; i++) {
                addresses[i] = InetAddress.getByName(ips[i]);
            }
            return addresses;
        });
    }

    private static void assertNotAllowed(FeedAddressPolicy policy, String url) {
        assertThatThrownBy(() -> policy.check(URI.create(url)))
                .isInstanceOf(FeedProbe.UnreadableFeedException.class)
                .hasMessage("Feed address is not allowed");
    }
}
