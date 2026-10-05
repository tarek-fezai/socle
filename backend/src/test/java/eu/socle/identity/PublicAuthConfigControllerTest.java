// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.socle.config.SocleProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PublicAuthConfigControllerTest {

    IdentityProperties properties;
    PublicAuthConfigController controller;

    @BeforeEach
    void setUp() {
        properties = new IdentityProperties();
        properties.setPasskeyAcrValues("urn:example:passkey");
        properties.setIdpDisplayName("Acme SSO");
        properties.setSupportContact("support@acme.example");
        properties.getAccessPolicy().setMode(IdentityProperties.AccessMode.REQUIRE_GROUP);
        properties.getAccessPolicy().setAllowedGroups(List.of("top-secret-group"));
        properties.getAccessPolicy().setAllowedEmailDomains(List.of("confidential-domain.example"));
        controller = new PublicAuthConfigController(
                properties, new SocleProperties(null, null, null, new SocleProperties.Instance("Acme Docs", null, null), null));
    }

    @Test
    void exposesDisplayFields() {
        Map<String, Object> body = controller.authConfig();

        assertThat(body)
                .containsEntry("passkeyAcrValues", "urn:example:passkey")
                .containsEntry("idpDisplayName", "Acme SSO")
                .containsEntry("supportContact", "support@acme.example")
                .containsEntry("organizationName", "Acme Docs");
    }

    @Test
    void defaultsAreEmptyStrings() {
        controller = new PublicAuthConfigController(
                new IdentityProperties(), new SocleProperties(null, null, null, null, null));

        Map<String, Object> body = controller.authConfig();

        assertThat(body)
                .containsEntry("passkeyAcrValues", "")
                .containsEntry("idpDisplayName", "")
                .containsEntry("supportContact", "")
                .containsEntry("organizationName", "Socle");
    }

    @Test
    void neverExposesAccessPolicyGroupsOrDomains() throws Exception {
        Map<String, Object> body = controller.authConfig();
        String json = new ObjectMapper().writeValueAsString(body);

        assertThat(body.keySet()).noneMatch(k ->
                k.toLowerCase().contains("group")
                        || k.toLowerCase().contains("domain")
                        || k.toLowerCase().contains("accesspolicy")
                        || k.toLowerCase().contains("mode"));
        assertThat(json)
                .doesNotContain("top-secret-group")
                .doesNotContain("confidential-domain")
                .doesNotContain("allowedGroups")
                .doesNotContain("allowedEmailDomains")
                .doesNotContain("groupsClaim");
    }
}
