// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.linkpreview;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Anti-SSRF : résolution DNS puis refus des IP privées / loopback / link-local / ULA / métadonnées.
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
     * @return adresses résolues (pour tests / logs)
     */
    public static List<InetAddress> resolveAndRejectPrivate(String host) {
        InetAddress[] addrs;
        try {
            addrs = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw rejected("hôte introuvable");
        }
        if (addrs == null || addrs.length == 0) {
            throw rejected("hôte introuvable");
        }
        List<InetAddress> out = new ArrayList<>();
        for (InetAddress a : addrs) {
            if (isBlocked(a)) {
                throw rejected("adresse réseau privée ou réservée");
            }
            out.add(a);
        }
        return out;
    }

    public static boolean isBlocked(InetAddress addr) {
        if (addr == null || addr.isAnyLocalAddress() || addr.isLoopbackAddress()
                || addr.isLinkLocalAddress() || addr.isSiteLocalAddress()
                || addr.isMulticastAddress()) {
            return true;
        }
        byte[] b = addr.getAddress();
        if (b.length == 4) {
            int a0 = b[0] & 0xff;
            int a1 = b[1] & 0xff;
            // 0.0.0.0/8, 10/8, 127/8, 169.254/16, 172.16/12, 192.168/16, 100.64/10 (CGNAT)
            if (a0 == 0 || a0 == 10 || a0 == 127) {
                return true;
            }
            if (a0 == 169 && a1 == 254) {
                return true; // link-local + metadata 169.254.169.254
            }
            if (a0 == 172 && a1 >= 16 && a1 <= 31) {
                return true;
            }
            if (a0 == 192 && a1 == 168) {
                return true;
            }
            if (a0 == 100 && a1 >= 64 && a1 <= 127) {
                return true;
            }
        } else if (b.length == 16) {
            // ::1, fe80::/10, fc00::/7 (ULA incl. fd00::/8)
            if (addr.isLoopbackAddress()) {
                return true;
            }
            int b0 = b[0] & 0xff;
            if (b0 == 0xfe && (b[1] & 0xc0) == 0x80) {
                return true; // fe80::/10
            }
            if ((b0 & 0xfe) == 0xfc) {
                return true; // fc00::/7
            }
        }
        return false;
    }

    private static ResponseStatusException rejected(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Aperçu de lien refusé : " + reason);
    }
}
