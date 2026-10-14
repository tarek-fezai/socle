// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.mail;

import eu.socle.branding.BrandingMailSender;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class SmtpTlsStartupTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SmtpConfig.class);

    @Test
    void hostWithoutTls_outsideDev_refused() {
        runner.withPropertyValues("socle.smtp.host=smtp.example", "socle.smtp.tls=")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure()).rootCause()
                            .hasMessageContaining("SOCLE_SMTP_TLS obligatoire");
                });
    }

    @Test
    void hostWithStarttls_starts() {
        runner.withPropertyValues(
                        "socle.smtp.host=smtp.example",
                        "socle.smtp.port=587",
                        "socle.smtp.tls=starttls",
                        "socle.smtp.from=socle@example.com")
                .run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(BrandingMailSender.class));
    }

    @Test
    void hostWithoutTls_dev_starts() {
        runner.withPropertyValues(
                        "socle.smtp.host=mailpit",
                        "socle.smtp.port=1025",
                        "socle.smtp.tls=none",
                        "socle.smtp.from=socle@localhost",
                        "spring.profiles.active=dev")
                .run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(BrandingMailSender.class));
    }

    @Test
    void notConfigured_startsWithLogChannel() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(BrandingMailSender.class).channel()).isEqualTo("log");
        });
    }
}
