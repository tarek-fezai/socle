// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.mail;

import eu.socle.branding.BrandingMailSender;
import jakarta.mail.Authenticator;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;

/** Envoi SMTP. Ne journalise jamais mot de passe, username ni corps contenant un jeton. */
public class SmtpMailSender implements BrandingMailSender {

    private static final Logger log = LoggerFactory.getLogger(SmtpMailSender.class);

    private final SmtpProperties properties;

    public SmtpMailSender(SmtpProperties properties) {
        this.properties = properties;
    }

    @Override
    public void send(Message message) {
        try {
            Session session = session();
            MimeMessage mime = new MimeMessage(session);
            String from = message.fromEmail() != null && !message.fromEmail().isBlank()
                    ? message.fromEmail()
                    : properties.from();
            if (from == null || from.isBlank()) {
                throw new IllegalStateException("Expéditeur SMTP manquant (SOCLE_SMTP_FROM ou branding)");
            }
            mime.setFrom(new InternetAddress(from, message.fromName(), "UTF-8"));
            mime.setRecipient(jakarta.mail.Message.RecipientType.TO, new InternetAddress(message.to()));
            mime.setSubject(message.subject(), "UTF-8");
            if (message.htmlBody() != null && !message.htmlBody().isBlank()) {
                MimeMultipart mixed = new MimeMultipart("alternative");
                MimeBodyPart text = new MimeBodyPart();
                text.setText(message.body() == null ? "" : message.body(), "UTF-8");
                text.setHeader("Content-Transfer-Encoding", "base64");
                MimeBodyPart html = new MimeBodyPart();
                html.setContent(message.htmlBody(), "text/html; charset=UTF-8");
                html.setHeader("Content-Transfer-Encoding", "base64");
                mixed.addBodyPart(text);
                mixed.addBodyPart(html);
                mime.setContent(mixed);
            } else {
                mime.setText(message.body() == null ? "" : message.body(), "UTF-8");
            }
            Transport.send(mime);
            log.info("SMTP envoyé to-domain={} subject-len={}", domainOf(message.to()),
                    message.subject() == null ? 0 : message.subject().length());
        } catch (Exception e) {
            log.error("SMTP envoi échoué: {}", e.getClass().getSimpleName());
            throw new IllegalStateException("Envoi SMTP impossible", e);
        }
    }

    @Override
    public String channel() {
        return "smtp";
    }

    Session session() {
        Properties props = new Properties();
        props.put("mail.smtp.host", properties.host());
        props.put("mail.smtp.port", String.valueOf(properties.port()));
        props.put("mail.smtp.connectiontimeout", "8000");
        props.put("mail.smtp.timeout", "8000");
        props.put("mail.debug", "false");
        SmtpProperties.TlsMode tls = properties.tlsMode();
        if (tls == SmtpProperties.TlsMode.IMPLICIT) {
            props.put("mail.smtp.ssl.enable", "true");
            props.put("mail.smtp.ssl.required", "true");
        } else if (tls == SmtpProperties.TlsMode.STARTTLS) {
            props.put("mail.smtp.starttls.enable", "true");
            props.put("mail.smtp.starttls.required", "true");
        }
        String user = properties.username();
        String pass = properties.password();
        if (user != null && !user.isBlank()) {
            props.put("mail.smtp.auth", "true");
            return Session.getInstance(props, new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(user, pass == null ? "" : pass);
                }
            });
        }
        return Session.getInstance(props);
    }

    private static String domainOf(String email) {
        if (email == null) {
            return "-";
        }
        int at = email.lastIndexOf('@');
        return at < 0 ? "-" : email.substring(at + 1);
    }
}
