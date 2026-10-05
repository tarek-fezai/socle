// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.branding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Implémentation par défaut : aucun SMTP configuré — l'envoi est seulement journalisé. */
public class LoggingBrandingMailSender implements BrandingMailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingBrandingMailSender.class);

    @Override
    public void send(Message message) {
        log.info("[branding-mail] (non envoyé : aucun SMTP configuré) from=\"{}\" <{}> to={} subject=\"{}\"",
                message.fromName(), message.fromEmail(), message.to(), message.subject());
    }

    @Override
    public String channel() {
        return "log";
    }
}
