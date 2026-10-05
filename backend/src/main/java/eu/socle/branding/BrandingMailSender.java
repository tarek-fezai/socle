// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.branding;

/**
 * Envoi d'e-mails d'instance avec l'expéditeur configuré (Admin › Branding).
 * L'implémentation par défaut ({@link LoggingBrandingMailSender}) journalise seulement ;
 * une implémentation SMTP peut la remplacer (bean {@code BrandingMailSender}).
 */
public interface BrandingMailSender {

    void send(Message message);

    /** Identifiant du canal de livraison (ex. {@code log}, {@code smtp}) — informatif. */
    default String channel() {
        return "custom";
    }

    record Message(String fromName, String fromEmail, String to, String subject, String body) {}
}
