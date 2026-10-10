// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.pat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** Démarrage refusé sans pepper (ou pepper < 32 octets) hors profil dev. */
class PatPepperStartupTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PatConfig.class);

    @Test
    void noPepper_outsideDev_startupRefused() {
        runner.run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("SOCLE_PAT_PEPPER obligatoire");
        });
        runner.withPropertyValues("socle.pat.pepper=   ").run(ctx -> assertThat(ctx).hasFailed());
        runner.withPropertyValues("spring.profiles.active=prod").run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void shortPepper_refusedEvenInDev() {
        runner.withPropertyValues("socle.pat.pepper=" + "x".repeat(31)).run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining("trop court");
        });
        runner.withPropertyValues("socle.pat.pepper=" + "x".repeat(31), "spring.profiles.active=dev")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void pepper32Bytes_starts() {
        runner.withPropertyValues("socle.pat.pepper=" + "x".repeat(32))
                .run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(PatHasher.class));
    }

    @Test
    void noPepper_devOrLocalProfile_startsWithEphemeralPepper() {
        runner.withPropertyValues("spring.profiles.active=dev")
                .run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(PatHasher.class));
        runner.withPropertyValues("spring.profiles.active=local")
                .run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(PatHasher.class));
    }
}
