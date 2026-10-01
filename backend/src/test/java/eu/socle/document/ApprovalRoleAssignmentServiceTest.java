package eu.socle.document;

import eu.socle.audit.AuditActions;
import eu.socle.audit.AuditService;
import eu.socle.identity.SocleRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApprovalRoleAssignmentServiceTest {

    static final UUID ACTOR = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID OWNER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    static final UUID ROLE = UUID.fromString("33333333-3333-3333-3333-333333333333");
    static final UUID SUBJECT = UUID.fromString("44444444-4444-4444-4444-444444444444");
    static final UUID SPACE_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static final UUID SPACE_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @Mock JdbcTemplate jdbc;
    @Mock AuditService auditService;
    ApprovalRoleAssignmentService service;

    @BeforeEach
    void setUp() {
        service = new ApprovalRoleAssignmentService(jdbc, auditService);
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void spaceOwner_canAssignOnOwnSpace() {
        asUser(OWNER);
        when(jdbc.query(contains("space_owners"), any(RowMapper.class), eq(OWNER)))
                .thenReturn(List.of(SPACE_A));
        when(jdbc.queryForObject(contains("FROM global_roles"), eq(Integer.class), eq(ROLE))).thenReturn(1);
        when(jdbc.queryForObject(contains("FROM users"), eq(Integer.class), eq(SUBJECT))).thenReturn(1);
        when(jdbc.update(contains("INSERT INTO approval_role_assignments"), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);
        stubGetAfterInsert();

        var view = service.assign(OWNER, new ApprovalRoleAssignmentService.AssignRequest(
                ROLE, "user", SUBJECT, "space", SPACE_A.toString()));

        assertThat(view.scopeType()).isEqualTo("space");
        assertThat(view.scopeRef()).isEqualTo(SPACE_A.toString());
        verify(auditService).recordSync(
                eq(OWNER), eq(true), eq(AuditActions.APPROVAL_ROLE_ASSIGNED),
                eq("approval_role_assignment"), any(), anyMap(), isNull());
    }

    @Test
    void spaceOwner_forbiddenOnOtherSpace() {
        asUser(OWNER);
        when(jdbc.query(contains("space_owners"), any(RowMapper.class), eq(OWNER)))
                .thenReturn(List.of(SPACE_A));
        when(jdbc.queryForObject(contains("FROM global_roles"), eq(Integer.class), eq(ROLE))).thenReturn(1);
        when(jdbc.queryForObject(contains("FROM users"), eq(Integer.class), eq(SUBJECT))).thenReturn(1);

        assertThatThrownBy(() -> service.assign(OWNER, new ApprovalRoleAssignmentService.AssignRequest(
                ROLE, "user", SUBJECT, "space", SPACE_B.toString())))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void spaceOwner_forbiddenOnAllScope() {
        asUser(OWNER);
        when(jdbc.queryForObject(contains("FROM global_roles"), eq(Integer.class), eq(ROLE))).thenReturn(1);
        when(jdbc.queryForObject(contains("FROM users"), eq(Integer.class), eq(SUBJECT))).thenReturn(1);

        assertThatThrownBy(() -> service.assign(OWNER, new ApprovalRoleAssignmentService.AssignRequest(
                ROLE, "user", SUBJECT, "all", null)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void admin_canAssignAll() {
        asAdmin(ACTOR);
        when(jdbc.queryForObject(contains("FROM global_roles"), eq(Integer.class), eq(ROLE))).thenReturn(1);
        when(jdbc.queryForObject(contains("FROM users"), eq(Integer.class), eq(SUBJECT))).thenReturn(1);
        when(jdbc.update(contains("INSERT INTO approval_role_assignments"), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);
        stubGetAfterInsertAll();

        var view = service.assign(ACTOR, new ApprovalRoleAssignmentService.AssignRequest(
                ROLE, "user", SUBJECT, "all", null));

        assertThat(view.scopeType()).isEqualTo("all");
    }

    @Test
    void selfAssign_auditIncludesSelfAssignedTrue() {
        asUser(OWNER);
        when(jdbc.query(contains("space_owners"), any(RowMapper.class), eq(OWNER)))
                .thenReturn(List.of(SPACE_A));
        when(jdbc.queryForObject(contains("FROM global_roles"), eq(Integer.class), eq(ROLE))).thenReturn(1);
        when(jdbc.queryForObject(contains("FROM users"), eq(Integer.class), eq(OWNER))).thenReturn(1);
        when(jdbc.update(contains("INSERT INTO approval_role_assignments"), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);
        when(jdbc.query(contains("WHERE ara.id = ?"), any(RowMapper.class), any()))
                .thenAnswer(inv -> {
                    RowMapper<?> mapper = inv.getArgument(1);
                    ResultSet rs = org.mockito.Mockito.mock(ResultSet.class);
                    when(rs.getObject("id")).thenReturn(UUID.randomUUID());
                    when(rs.getObject("role_id")).thenReturn(ROLE);
                    when(rs.getString("role_name")).thenReturn("Reviewer");
                    when(rs.getString("subject_type")).thenReturn("user");
                    when(rs.getObject("subject_id")).thenReturn(OWNER);
                    when(rs.getString("scope_type")).thenReturn("space");
                    when(rs.getString("scope_ref")).thenReturn(SPACE_A.toString());
                    when(rs.getObject("granted_by")).thenReturn(OWNER);
                    when(rs.getTimestamp("granted_at")).thenReturn(Timestamp.from(Instant.now()));
                    return List.of(mapper.mapRow(rs, 0));
                });

        service.assign(OWNER, new ApprovalRoleAssignmentService.AssignRequest(
                ROLE, "user", OWNER, "space", SPACE_A.toString()));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> meta = ArgumentCaptor.forClass(Map.class);
        verify(auditService).recordSync(
                eq(OWNER), eq(true), eq(AuditActions.APPROVAL_ROLE_ASSIGNED),
                eq("approval_role_assignment"), any(), meta.capture(), isNull());
        assertThat(meta.getValue()).containsEntry("selfAssigned", true);
    }

    @Test
    void assign_tagByName_resolvesToUuid() {
        UUID tagId = UUID.fromString("99999999-9999-9999-9999-999999999999");
        asAdmin(ACTOR);
        when(jdbc.queryForObject(contains("FROM global_roles"), eq(Integer.class), eq(ROLE))).thenReturn(1);
        when(jdbc.queryForObject(contains("FROM users"), eq(Integer.class), eq(SUBJECT))).thenReturn(1);
        when(jdbc.query(contains("FROM tags WHERE lower(name)"), any(RowMapper.class), eq("Confidential")))
                .thenReturn(List.of(tagId));
        when(jdbc.update(contains("INSERT INTO approval_role_assignments"), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);
        when(jdbc.query(contains("WHERE ara.id = ?"), any(RowMapper.class), any()))
                .thenAnswer(inv -> {
                    RowMapper<?> mapper = inv.getArgument(1);
                    ResultSet rs = org.mockito.Mockito.mock(ResultSet.class);
                    when(rs.getObject("id")).thenReturn(UUID.randomUUID());
                    when(rs.getObject("role_id")).thenReturn(ROLE);
                    when(rs.getString("role_name")).thenReturn("Reviewer");
                    when(rs.getString("subject_type")).thenReturn("user");
                    when(rs.getObject("subject_id")).thenReturn(SUBJECT);
                    when(rs.getString("scope_type")).thenReturn("tag");
                    when(rs.getString("scope_ref")).thenReturn(tagId.toString());
                    when(rs.getObject("granted_by")).thenReturn(ACTOR);
                    when(rs.getTimestamp("granted_at")).thenReturn(Timestamp.from(Instant.now()));
                    return List.of(mapper.mapRow(rs, 0));
                });

        var view = service.assign(ACTOR, new ApprovalRoleAssignmentService.AssignRequest(
                ROLE, "user", SUBJECT, "tag", "Confidential"));

        assertThat(view.scopeRef()).isEqualTo(tagId.toString());
        verify(jdbc).update(
                contains("INSERT INTO approval_role_assignments"),
                any(), eq(ROLE), eq("user"), eq(SUBJECT), eq("tag"), eq(tagId.toString()), eq(ACTOR));
    }

    @Test
    void assign_tagUnknownName_badRequest() {
        asAdmin(ACTOR);
        when(jdbc.query(contains("FROM tags WHERE lower(name)"), any(RowMapper.class), eq("Inconnu")))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.assign(ACTOR, new ApprovalRoleAssignmentService.AssignRequest(
                ROLE, "user", SUBJECT, "tag", "Inconnu")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    private void stubGetAfterInsert() {
        when(jdbc.query(contains("WHERE ara.id = ?"), any(RowMapper.class), any()))
                .thenAnswer(inv -> {
                    RowMapper<?> mapper = inv.getArgument(1);
                    ResultSet rs = org.mockito.Mockito.mock(ResultSet.class);
                    when(rs.getObject("id")).thenReturn(UUID.randomUUID());
                    when(rs.getObject("role_id")).thenReturn(ROLE);
                    when(rs.getString("role_name")).thenReturn("Reviewer");
                    when(rs.getString("subject_type")).thenReturn("user");
                    when(rs.getObject("subject_id")).thenReturn(SUBJECT);
                    when(rs.getString("scope_type")).thenReturn("space");
                    when(rs.getString("scope_ref")).thenReturn(SPACE_A.toString());
                    when(rs.getObject("granted_by")).thenReturn(OWNER);
                    when(rs.getTimestamp("granted_at")).thenReturn(Timestamp.from(Instant.now()));
                    return List.of(mapper.mapRow(rs, 0));
                });
    }

    private void stubGetAfterInsertAll() {
        when(jdbc.query(contains("WHERE ara.id = ?"), any(RowMapper.class), any()))
                .thenAnswer(inv -> {
                    RowMapper<?> mapper = inv.getArgument(1);
                    ResultSet rs = org.mockito.Mockito.mock(ResultSet.class);
                    when(rs.getObject("id")).thenReturn(UUID.randomUUID());
                    when(rs.getObject("role_id")).thenReturn(ROLE);
                    when(rs.getString("role_name")).thenReturn("Reviewer");
                    when(rs.getString("subject_type")).thenReturn("user");
                    when(rs.getObject("subject_id")).thenReturn(SUBJECT);
                    when(rs.getString("scope_type")).thenReturn("all");
                    when(rs.getString("scope_ref")).thenReturn(null);
                    when(rs.getObject("granted_by")).thenReturn(ACTOR);
                    when(rs.getTimestamp("granted_at")).thenReturn(Timestamp.from(Instant.now()));
                    return List.of(mapper.mapRow(rs, 0));
                });
    }

    private static void asAdmin(UUID userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        userId.toString(), "n/a",
                        List.of(new SimpleGrantedAuthority(SocleRole.ADMINISTRATEUR_SYSTEME.authority()))));
    }

    private static void asUser(UUID userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        userId.toString(), "n/a",
                        List.of(new SimpleGrantedAuthority(SocleRole.CONTRIBUTEUR.authority()))));
    }
}
