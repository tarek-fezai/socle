// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.document;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApprovalRoleResolverTest {

    static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID ROLE = UUID.fromString("22222222-2222-2222-2222-222222222222");
    static final UUID DOC_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static final UUID DOC_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @Mock JdbcTemplate jdbc;
    ApprovalRoleResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new ApprovalRoleResolver(jdbc);
    }

    @Test
    void canDecide_trueWhenAssignmentCoversDocument() {
        when(jdbc.queryForObject(contains("approval_role_assignments"), eq(Boolean.class),
                eq(DOC_A), eq(ROLE), eq(USER), eq(USER)))
                .thenReturn(true);

        assertThat(resolver.canDecide(USER, ROLE, DOC_A)).isTrue();
    }

    @Test
    void canDecide_falseWhenOutOfScope() {
        when(jdbc.queryForObject(contains("approval_role_assignments"), eq(Boolean.class),
                eq(DOC_B), eq(ROLE), eq(USER), eq(USER)))
                .thenReturn(false);

        assertThat(resolver.canDecide(USER, ROLE, DOC_B)).isFalse();
    }

    @Test
    void canDecideCurrentStep_loadsRoleAndDocument() {
        UUID requestId = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
        when(jdbc.query(contains("approval_workflow_steps"), any(org.springframework.jdbc.core.RowMapper.class), eq(requestId)))
                .thenAnswer(inv -> {
                    org.springframework.jdbc.core.RowMapper<?> mapper = inv.getArgument(1);
                    java.sql.ResultSet rs = org.mockito.Mockito.mock(java.sql.ResultSet.class);
                    when(rs.getObject("role_id")).thenReturn(ROLE);
                    when(rs.getObject("document_id")).thenReturn(DOC_A);
                    return List.of(mapper.mapRow(rs, 0));
                });
        when(jdbc.queryForObject(contains("approval_role_assignments"), eq(Boolean.class),
                eq(DOC_A), eq(ROLE), eq(USER), eq(USER)))
                .thenReturn(true);

        assertThat(resolver.canDecideCurrentStep(USER, requestId)).isTrue();
    }

    @Test
    void resolveInScopeAssignees_returnsDistinctUsers() {
        when(jdbc.query(contains("covered"), any(org.springframework.jdbc.core.RowMapper.class),
                eq(DOC_A), eq(ROLE), eq(DOC_A), eq(ROLE)))
                .thenReturn(List.of(USER));

        assertThat(resolver.resolveInScopeAssignees(ROLE, DOC_A)).containsExactly(USER);
        verify(jdbc).query(contains("covered"), any(org.springframework.jdbc.core.RowMapper.class),
                eq(DOC_A), eq(ROLE), eq(DOC_A), eq(ROLE));
    }
}
