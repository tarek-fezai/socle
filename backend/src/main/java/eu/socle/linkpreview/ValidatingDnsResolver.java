// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.linkpreview;

import org.apache.hc.client5.http.DnsResolver;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Résout le nom, refuse toute adresse bloquée, et ne renvoie que les adresses validées.
 * Le client HTTP ne peut donc se connecter qu'à ces adresses (anti DNS-rebinding).
 */
public final class ValidatingDnsResolver implements DnsResolver {

    private final Function<String, InetAddress[]> lookup;

    public ValidatingDnsResolver() {
        this(host -> {
            try {
                return InetAddress.getAllByName(host);
            } catch (UnknownHostException e) {
                return null;
            }
        });
    }

    /** Constructeur test : injecte une résolution DNS simulée. */
    public ValidatingDnsResolver(Function<String, InetAddress[]> lookup) {
        this.lookup = Objects.requireNonNull(lookup);
    }

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        if (host == null || host.isBlank()) {
            throw new UnknownHostException("hôte vide");
        }
        InetAddress[] addrs;
        try {
            addrs = lookup.apply(host);
        } catch (ResponseStatusException e) {
            throw new UnknownHostException(e.getReason() != null ? e.getReason() : "refusé");
        }
        if (addrs == null || addrs.length == 0) {
            throw new UnknownHostException("hôte introuvable: " + host);
        }
        List<InetAddress> safe = new ArrayList<>();
        for (InetAddress a : addrs) {
            if (SsrfGuard.isBlocked(a)) {
                throw new UnknownHostException("adresse réseau privée ou réservée: " + a.getHostAddress());
            }
            safe.add(a);
        }
        if (safe.isEmpty()) {
            throw new UnknownHostException("aucune adresse publique pour " + host);
        }
        return safe.toArray(InetAddress[]::new);
    }

    @Override
    public String resolveCanonicalHostname(String host) {
        return host;
    }

    /** Variante qui lève {@link ResponseStatusException} (API métier) plutôt que UnknownHostException. */
    public InetAddress[] resolveOrReject(String host) {
        try {
            return resolve(host);
        } catch (UnknownHostException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Aperçu de lien refusé : " + (e.getMessage() != null ? e.getMessage() : "hôte refusé"));
        }
    }
}
