package swipread.api.source;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Set;

/**
 * Protection SSRF de la vérification des flux : l'API télécharge une URL choisie par un administrateur, qui ne doit
 * viser ni la machine, ni le réseau interne, ni les métadonnées du cloud.
 * <p>
 * {@link #check(URI)} contrôle le schéma et le port de chaque URL, redirections comprises. {@link #resolve(String)}
 * est le résolveur DNS du client HTTP : les adresses contrôlées sont exactement celles auxquelles il se connecte, un
 * domaine qui change d'adresse entre le contrôle et la connexion (DNS rebinding) ne passe donc pas.
 */
@Component
class FeedAddressPolicy {

    static final String NOT_ALLOWED = "Feed address is not allowed";
    private static final Set<Integer> ALLOWED_PORTS = Set.of(80, 443);

    interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    /**
     * Adresse interdite rencontrée à la résolution. Hérite d'UnknownHostException, seule exception que le client
     * HTTP attend d'un résolveur.
     */
    static class ForbiddenAddressException extends UnknownHostException {
        ForbiddenAddressException(String host) {
            super(host + ": " + NOT_ALLOWED);
        }
    }

    private final boolean allowLocalFeeds;
    private final Resolver resolver;

    @Autowired
    FeedAddressPolicy(SourceProperties properties) {
        this(properties.allowLocalFeeds(), InetAddress::getAllByName);
    }

    FeedAddressPolicy(boolean allowLocalFeeds, Resolver resolver) {
        this.allowLocalFeeds = allowLocalFeeds;
        this.resolver = resolver;
    }

    /**
     * @throws FeedProbe.UnreadableFeedException si le schéma n'est pas http(s) ou si le port n'est pas 80 ou 443
     */
    void check(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!scheme.equals("http") && !scheme.equals("https") || uri.getHost() == null) {
            throw new FeedProbe.UnreadableFeedException(NOT_ALLOWED, null);
        }
        if (!allowLocalFeeds && uri.getPort() != -1 && !ALLOWED_PORTS.contains(uri.getPort())) {
            throw new FeedProbe.UnreadableFeedException(NOT_ALLOWED, null);
        }
    }

    /**
     * Résout l'hôte et refuse si une seule des adresses est interne : le client peut se connecter à n'importe laquelle.
     *
     * @throws ForbiddenAddressException si une adresse est interdite
     * @throws UnknownHostException      si l'hôte est inconnu
     */
    InetAddress[] resolve(String host) throws UnknownHostException {
        InetAddress[] addresses = resolver.resolve(host);
        if (!allowLocalFeeds
                && (addresses.length == 0 || Arrays.stream(addresses).anyMatch(FeedAddressPolicy::isForbidden))) {
            throw new ForbiddenAddressException(host);
        }
        return addresses;
    }

    static boolean isForbidden(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            return isForbiddenIpv4(bytes);
        }
        if (address instanceof Inet6Address) {
            // fc00::/7 : adresses locales uniques
            if ((bytes[0] & 0xfe) == 0xfc) {
                return true;
            }
            // ::ffff:a.b.c.d, ::a.b.c.d et 64:ff9b::/96 (NAT64) embarquent une IPv4
            if (embedsIpv4(bytes)) {
                return isForbiddenIpv4(Arrays.copyOfRange(bytes, 12, 16));
            }
        }
        return false;
    }

    private static boolean isForbiddenIpv4(byte[] b) {
        int first = b[0] & 0xff;
        int second = b[1] & 0xff;
        return first == 0                                   // 0.0.0.0/8
                || first == 10                              // 10/8
                || first == 127                             // bouclage
                || first == 100 && (second & 0xc0) == 64    // 100.64/10 (CGNAT)
                || first == 169 && second == 254            // lien local, métadonnées cloud
                || first == 172 && (second & 0xf0) == 16    // 172.16/12
                || first == 192 && second == 168            // 192.168/16
                || first >= 224;                            // multicast, réservé, diffusion
    }

    private static boolean embedsIpv4(byte[] b) {
        boolean zeroPrefix = true;
        for (int i = 0; i < 10; i++) {
            zeroPrefix &= b[i] == 0;
        }
        boolean mapped = zeroPrefix && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff;
        boolean compatible = zeroPrefix && b[10] == 0 && b[11] == 0;
        boolean nat64 = (b[0] & 0xff) == 0x00 && (b[1] & 0xff) == 0x64 && (b[2] & 0xff) == 0xff && (b[3] & 0xff) == 0x9b
                && Arrays.stream(new int[]{4, 5, 6, 7, 8, 9, 10, 11}).allMatch(i -> b[i] == 0);
        return mapped || compatible || nat64;
    }
}
