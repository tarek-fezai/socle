// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.invite;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import eu.socle.config.SocleProperties;
import eu.socle.mail.SmtpProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InvitationCapabilityTest {

    @Mock SmtpProperties smtp;
    @Mock SocleProperties socle;
    @Mock SocleProperties.Instance instance;
    @Mock Environment environment;

    @Test
    void missingHostAndPublicUrl_namesOnly() {
        var snap = InvitationCapability.evaluate(false, "  ", SmtpProperties.TlsMode.STARTTLS, false);
        assertThat(snap.enabled()).isFalse();
        assertThat(snap.missing()).containsExactly("SOCLE_PUBLIC_BASE_URL", "SOCLE_SMTP_HOST");
    }

    @Test
    void tlsNoneOutsideDevLocal_namesTls() {
        var snap = InvitationCapability.evaluate(true, "https://docs.example", SmtpProperties.TlsMode.NONE, false);
        assertThat(snap.enabled()).isFalse();
        assertThat(snap.missing()).containsExactly("SOCLE_SMTP_TLS");
    }

    @Test
    void devOrLocalTlsNone_withHostAndUrl_isEnabled() {
        var snap = InvitationCapability.evaluate(true, "https://docs.example", SmtpProperties.TlsMode.NONE, true);
        assertThat(snap.enabled()).isTrue();
        assertThat(snap.missing()).isEmpty();
    }

    @Test
    void startupWarnsOnceWithNames() {
        when(smtp.configured()).thenReturn(false);
        when(smtp.tlsMode()).thenReturn(SmtpProperties.TlsMode.STARTTLS);
        when(socle.instance()).thenReturn(instance);
        when(instance.effectivePublicBaseUrl()).thenReturn(null);
        when(environment.acceptsProfiles(any(Profiles.class))).thenReturn(false);
        InvitationCapability capability = new InvitationCapability(smtp, socle, environment);

        Logger logger = (Logger) LoggerFactory.getLogger(InvitationCapability.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            capability.warnIfDisabled();
            capability.warnIfDisabled();
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.getFirst().getFormattedMessage())
                .contains("SOCLE_PUBLIC_BASE_URL")
                .contains("SOCLE_SMTP_HOST")
                .doesNotContain("smtp.example")
                .doesNotContain("https://");
    }
}
