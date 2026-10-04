// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.linkpreview;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.net.InetAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Anti-SSRF : résolution DNS puis refus des IP privées / loopback / link-local / ULA / métadonnées
 * / plages de benchmarking / NAT64 / 6to4 / IPv4 encapsulées.
 * Ne crée aucune connexion sortante — uniquement résolution + contrôles.
 */
public final class SsrfGuard {

    private SsrfGuard() {}

    public static URI requireHttpUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            throw rejected("URL vide");
        }
        URI uri;
        try {
            uri = URI.create(raw.trim());
        } catch (IllegalArgumentException e) {
            throw rejected("URL invalide");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw rejected("schéma http(s) requis");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw rejected("hôte manquant");
        }
        int port = uri.getPort();
        if (port == -1) {
            port = "https".equals(scheme) ? 443 : 80;
        }
        if (port != 80 && port != 443) {
            throw rejected("ports 80/443 uniquement");
        }
        return uri;
    }

    public static void requireAllowedDomain(URI uri, Set<String> allowed) {
        if (allowed == null || allowed.isEmpty()) {
            throw rejected("aucun domaine autorisé");
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        for (String d : allowed) {
            if (host.equals(d) || host.endsWith("." + d)) {
                return;
            }
        }
        throw rejected("domaine hors liste blanche");
    }

    /**
     * Résout le nom et refuse toute adresse non publique.
     * @return adresses résolues (uniquement publiques)
     */
    public static List<InetAddress> resolveAndRejectPrivate(String host) {
        return Arrays.asList(new ValidatingDnsResolver().resolveOrReject(host));
    }

    public static boolean isBlocked(InetAddress addr) {
        if (addr == null || addr.isAnyLocalAddress() || addr.isLoopbackAddress()
                || addr.isLinkLocalAddress() || addr.isSiteLocalAddress()
                || addr.isMulticastAddress()) {
            return true;
        }
        byte[] b = addr.getAddress();
        if (b.length == 4) {
            return isBlockedIpv4(b);
        }
        if (b.length == 16) {
            return isBlockedIpv6(b, addr);
        }
        return true;
    }

    private static boolean isBlockedIpv4(byte[] b) {
        int a0 = b[0] & 0xff;
        int a1 = b[1] & 0xff;
        int a2 = b[2] & 0xff;
        int a3 = b[3] & 0xff;
        // 0.0.0.0/8, 10/8, 127/8
        if (a0 == 0 || a0 == 10 || a0 == 127) {
            return true;
        }
        // 169.254/16 (link-local + metadata)
        if (a0 == 169 && a1 == 254) {
            return true;
        }
        // 172.16/12
        if (a0 == 172 && a1 >= 16 && a1 <= 31) {
            return true;
        }
        // 192.168/16
        if (a0 == 192 && a1 == 168) {
            return true;
        }
        // 100.64/10 CGNAT
        if (a0 == 100 && a1 >= 64 && a1 <= 127) {
            return true;
        }
        // 198.18.0.0/15 (benchmarking)
        if (a0 == 198 && (a1 == 18 || a1 == 19)) {
            return true;
        }
        // 240.0.0.0/4 (classe E / réservé)
        if (a0 >= 240) {
            return true;
        }
        // 255.255.255.255
        if (a0 == 255 && a1 == 255 && a2 == 255 && a3 == 255) {
            return true;
        }
        return false;
    }

    private static boolean isBlockedIpv6(byte[] b, InetAddress addr) {
        if (addr.isLoopbackAddress()) {
            return true;
        }
        int b0 = b[0] & 0xff;
        int b1 = b[1] & 0xff;
        // fe80::/10
        if (b0 == 0xfe && (b1 & 0xc0) == 0x80) {
            return true;
        }
        // fc00::/7 (ULA incl. fd00::/8)
        if ((b0 & 0xfe) == 0xfc) {
            return true;
        }
        // 64:ff9b::/96 (NAT64 well-known prefix) — plage entière refusée
        if (b0 == 0x00 && b1 == 0x64
                && (b[2] & 0xff) == 0xff && (b[3] & 0xff) == 0x9b
                && b[4] == 0 && b[5] == 0 && b[6] == 0 && b[7] == 0
                && b[8] == 0 && b[9] == 0 && b[10] == 0 && b[11] == 0) {
            return true;
        }
        // 2002::/16 (6to4) — plage entière refusée
        if (b0 == 0x20 && b1 == 0x02) {
            return true;
        }
        // IPv4-mapped ::ffff:x.x.x.x
        if (isIpv4Mapped(b) || isIpv4Compatible(b)) {
            return isBlockedIpv4(new byte[]{b[12], b[13], b[14], b[15]});
        }
        return false;
    }

    private static boolean isIpv4Mapped(byte[] b) {
        for (int i = 0; i < 10; i++) {
            if (b[i] != 0) {
                return false;
            }
        }
        return (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff;
    }

    /** ::x.x.x.x (deprecated IPv4-compatible). */
    private static boolean isIpv4Compatible(byte[] b) {
        for (int i = 0; i < 12; i++) {
            if (b[i] != 0) {
                return false;
            }
        }
        // exclude :: and ::1 (déjà couverts)
        return !(b[12] == 0 && b[13] == 0 && b[14] == 0 && (b[15] == 0 || b[15] == 1));
    }

    private static ResponseStatusException rejected(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Aperçu de lien refusé : " + reason);
    }
}
