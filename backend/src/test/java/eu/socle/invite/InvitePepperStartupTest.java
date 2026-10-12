// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.invite;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class InvitePepperStartupTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(InviteConfig.class);

    @Test
    void noPepper_outsideDev_startupRefused() {
        runner.run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("SOCLE_INVITE_PEPPER obligatoire");
        });
    }

    @Test
    void shortPepper_refusedEvenInDev() {
        runner.withPropertyValues("socle.invite.pepper=" + "x".repeat(31), "spring.profiles.active=dev")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining("trop court");
                });
    }

    @Test
    void pepper32Bytes_starts() {
        runner.withPropertyValues("socle.invite.pepper=" + "x".repeat(32))
                .run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(InviteHasher.class));
    }

    @Test
    void noPepper_devOrLocal_startsWithEphemeral() {
        runner.withPropertyValues("spring.profiles.active=dev")
                .run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(InviteHasher.class));
        runner.withPropertyValues("spring.profiles.active=local")
                .run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(InviteHasher.class));
    }
}
