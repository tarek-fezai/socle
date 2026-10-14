// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.invite;

import eu.socle.branding.BrandingMailSender;
import eu.socle.config.SocleProperties;
import eu.socle.mail.SmtpProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class InvitationMailer {

    private final BrandingMailSender mailSender;
    private final SocleProperties socleProperties;
    private final SmtpProperties smtpProperties;
    private final JdbcTemplate jdbc;

    public InvitationMailer(
            BrandingMailSender mailSender,
            SocleProperties socleProperties,
            SmtpProperties smtpProperties,
            JdbcTemplate jdbc
    ) {
        this.mailSender = mailSender;
        this.socleProperties = socleProperties;
        this.smtpProperties = smtpProperties;
        this.jdbc = jdbc;
    }

    public void sendInvite(String to, String acceptUrl, String orgName) {
        String org = orgName == null || orgName.isBlank()
                ? socleProperties.instance().effectiveDisplayName()
                : orgName;
        Sender sender = sender();
        String subject = "Invitation à rejoindre " + org;
        String text = "Vous êtes invité(e) à rejoindre " + org + ".\n\n"
                + "Ouvrez ce lien pour accepter (connexion requise) :\n" + acceptUrl + "\n\n"
                + "Si vous n'attendiez pas cet e-mail, ignorez-le.";
        String html = "<p>Vous êtes invité(e) à rejoindre <strong>" + escape(org) + "</strong>.</p>"
                + "<p><a href=\"" + escape(acceptUrl) + "\">Accepter l'invitation</a></p>"
                + "<p>Si vous n'attendiez pas cet e-mail, ignorez-le.</p>";
        mailSender.send(new BrandingMailSender.Message(
                sender.name(), sender.email(), to, subject, text, html));
    }

    public String acceptUrl(String token) {
        String base = socleProperties.instance().effectivePublicBaseUrl();
        if (base == null) {
            throw new IllegalStateException("SOCLE_PUBLIC_BASE_URL requis pour envoyer une invitation");
        }
        return base + "/invitations/accept?token=" + token;
    }

    private Sender sender() {
        List<Sender> rows = jdbc.query(
                "SELECT sender_email, sender_name FROM branding_settings WHERE id = true",
                (rs, i) -> new Sender(rs.getString("sender_name"), rs.getString("sender_email")));
        String from = smtpProperties.from();
        String name = socleProperties.instance().effectiveDisplayName();
        if (!rows.isEmpty() && rows.getFirst().email() != null && !rows.getFirst().email().isBlank()) {
            from = rows.getFirst().email();
            if (rows.getFirst().name() != null && !rows.getFirst().name().isBlank()) {
                name = rows.getFirst().name();
            }
        }
        return new Sender(name, from);
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private record Sender(String name, String email) {}
}
