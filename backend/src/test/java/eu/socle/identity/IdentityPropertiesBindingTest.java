// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.identity;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IdentityPropertiesBindingTest {

    @Test
    void accessPolicy_bindsKebabCaseModes_andLists() {
        Map<String, String> source = Map.of(
                "socle.identity.access-policy.mode", "provisioned-only",
                "socle.identity.access-policy.allowed-groups[0]", "g1",
                "socle.identity.access-policy.allowed-groups[1]", "g2",
                "socle.identity.access-policy.groups-claim", "roles",
                "socle.identity.access-policy.allowed-email-domains[0]", "corp.com",
                "socle.identity.passkey-acr-values", "phr",
                "socle.identity.idp-display-name", "Acme",
                "socle.identity.support-contact", "help@acme.example");

        IdentityProperties p = new Binder(new MapConfigurationPropertySource(source))
                .bind("socle.identity", IdentityProperties.class).get();

        assertThat(p.getAccessPolicy().getMode()).isEqualTo(IdentityProperties.AccessMode.PROVISIONED_ONLY);
        assertThat(p.getAccessPolicy().getAllowedGroups()).containsExactly("g1", "g2");
        assertThat(p.getAccessPolicy().getGroupsClaim()).isEqualTo("roles");
        assertThat(p.getAccessPolicy().getAllowedEmailDomains()).containsExactly("corp.com");
        assertThat(p.getPasskeyAcrValues()).isEqualTo("phr");
        assertThat(p.getIdpDisplayName()).isEqualTo("Acme");
        assertThat(p.getSupportContact()).isEqualTo("help@acme.example");
    }

    @Test
    void accessPolicy_requireGroupMode_binds() {
        IdentityProperties p = new Binder(new MapConfigurationPropertySource(
                Map.of("socle.identity.access-policy.mode", "require-group")))
                .bind("socle.identity", IdentityProperties.class).get();
        assertThat(p.getAccessPolicy().getMode()).isEqualTo(IdentityProperties.AccessMode.REQUIRE_GROUP);
    }

    @Test
    void defaults_areOpenJit() {
        IdentityProperties p = new IdentityProperties();
        assertThat(p.getAccessPolicy().getMode()).isEqualTo(IdentityProperties.AccessMode.JIT);
        assertThat(p.getAccessPolicy().getAllowedGroups()).isEmpty();
        assertThat(p.getAccessPolicy().getAllowedEmailDomains()).isEmpty();
        assertThat(p.getAccessPolicy().getGroupsClaim()).isEqualTo("groups");
        assertThat(p.getPasskeyAcrValues()).isEmpty();
    }
}
