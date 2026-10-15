// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.invite;

import eu.socle.config.SocleProperties;
import eu.socle.mail.SmtpProperties;
import eu.socle.web.ApiErrors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Envoi d'invitations : optionnel. Absent ou TLS {@code none} hors {@code dev}/{@code local}
 * → capacité désactivée, un seul WARN au démarrage, jamais d'arrêt du processus.
 * La réponse ne contient que les noms de variables, jamais leurs valeurs.
 */
@Component
public class InvitationCapability {

    static final String SMTP_HOST = "SOCLE_SMTP_HOST";
    static final String PUBLIC_BASE_URL = "SOCLE_PUBLIC_BASE_URL";
    static final String SMTP_TLS = "SOCLE_SMTP_TLS";

    private static final Logger log = LoggerFactory.getLogger(InvitationCapability.class);
    /** Même exception que {@link eu.socle.mail.SmtpConfig} : Mailpit / GreenMail. */
    static final Profiles CLEARTEXT_PROFILES = Profiles.of("dev", "local");

    private final SmtpProperties smtpProperties;
    private final SocleProperties socleProperties;
    private final Environment environment;
    private final AtomicBoolean warned = new AtomicBoolean();

    public InvitationCapability(
            SmtpProperties smtpProperties,
            SocleProperties socleProperties,
            Environment environment
    ) {
        this.smtpProperties = smtpProperties;
        this.socleProperties = socleProperties;
        this.environment = environment;
    }

    public Snapshot snapshot() {
        SocleProperties.Instance instance = socleProperties.instance();
        String publicBaseUrl = instance == null ? null : instance.effectivePublicBaseUrl();
        return evaluate(
                smtpProperties.configured(),
                publicBaseUrl,
                smtpProperties.tlsMode(),
                environment.acceptsProfiles(CLEARTEXT_PROFILES));
    }

    public void requireEnabled() {
        Snapshot current = snapshot();
        if (!current.enabled()) {
            throw ApiErrors.invitationsNotConfigured(current.missing());
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warnIfDisabled() {
        Snapshot current = snapshot();
        if (current.enabled() || !warned.compareAndSet(false, true)) {
            return;
        }
        log.warn("Envoi d'invitations impossible ({})", String.join(", ", current.missing()));
    }

    static Snapshot evaluate(boolean smtpHostSet, String publicBaseUrl, SmtpProperties.TlsMode tls, boolean cleartextAllowed) {
        List<String> missing = new ArrayList<>();
        if (publicBaseUrl == null || publicBaseUrl.isBlank()) {
            missing.add(PUBLIC_BASE_URL);
        }
        if (!smtpHostSet) {
            missing.add(SMTP_HOST);
        } else if (tls == SmtpProperties.TlsMode.NONE && !cleartextAllowed) {
            missing.add(SMTP_TLS);
        }
        return new Snapshot(missing.isEmpty(), List.copyOf(missing));
    }

    public record Snapshot(boolean enabled, List<String> missing) {}
}
