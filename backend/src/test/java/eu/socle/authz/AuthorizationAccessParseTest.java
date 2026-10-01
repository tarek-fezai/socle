// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.authz;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorizationAccessParseTest {

    @Test
    void parseSubject_user() {
        UUID id = UUID.fromString("11111111-1111-1111-1111-111111111111");
        var parsed = AuthorizationService.parseSubject("user:" + id);
        assertThat(parsed.subjectType()).isEqualTo("user");
        assertThat(parsed.subjectId()).isEqualTo(id);
    }

    @Test
    void parseSubject_groupMember() {
        UUID id = UUID.fromString("22222222-2222-2222-2222-222222222222");
        var parsed = AuthorizationService.parseSubject("group:" + id + "#member");
        assertThat(parsed.subjectType()).isEqualTo("group");
        assertThat(parsed.subjectId()).isEqualTo(id);
    }
}
