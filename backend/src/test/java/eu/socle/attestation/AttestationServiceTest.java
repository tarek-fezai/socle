// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.attestation;

import eu.socle.attestation.AttestationDtos.AcknowledgmentView;
import eu.socle.attestation.AttestationDtos.ActiveAttestation;
import eu.socle.attestation.AttestationDtos.CampaignView;
import eu.socle.attestation.AttestationDtos.CreateCampaignRequest;
import eu.socle.audit.AuditActions;
import eu.socle.audit.AuditService;
import eu.socle.authz.AuthorizationService;
import eu.socle.document.DocumentEntity;
import eu.socle.document.DocumentRepository;
import eu.socle.document.ReliabilityScoreService;
import eu.socle.user.UserEntity;
import eu.socle.user.UserSyncService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("dockerAvailable")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AttestationServiceTest {

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("socle_core")
            .withUsername("socle")
            .withPassword("socle");

    static JdbcTemplate jdbc;
    static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");
    static final UUID OWNER = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static final UUID READER = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    static final UUID OUTSIDER = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    static final UUID DOC = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID SPACE = UUID.fromString("33333333-3333-3333-3333-333333333333");
    static final UUID GROUP = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @Mock AuthorizationService authorizationService;
    @Mock UserSyncService userSyncService;
    @Mock DocumentRepository documentRepository;
    @Mock AuditService auditService;
    @Mock ReliabilityScoreService reliabilityScoreService;

    AttestationService service;
    Jwt jwt;
    DocumentEntity document;
    UUID currentUser;

    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @BeforeAll
    static void schema() throws Exception {
        var ds = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS pgcrypto");
        jdbc.execute("""
                CREATE TABLE users (
                  id UUID PRIMARY KEY, email TEXT NOT NULL, display_name TEXT NOT NULL, status TEXT NOT NULL
                )
                """);
        jdbc.execute("CREATE TABLE groups (id UUID PRIMARY KEY, name TEXT NOT NULL)");
        jdbc.execute("""
                CREATE TABLE group_members (
                  group_id UUID NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
                  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                  PRIMARY KEY (group_id, user_id)
                )
                """);
        jdbc.execute("CREATE TABLE spaces (id UUID PRIMARY KEY, name TEXT NOT NULL)");
        jdbc.execute("""
                CREATE TABLE documents (
                  id UUID PRIMARY KEY, space_id UUID NOT NULL REFERENCES spaces(id),
                  title TEXT NOT NULL, current_version_no INT NOT NULL DEFAULT 1,
                  is_mandatory_ack BOOLEAN NOT NULL DEFAULT false, ack_due_date DATE,
                  deleted_at TIMESTAMPTZ
                )
                """);
        // Schéma V1 (tables héritées) puis migration V33.
        jdbc.execute("""
                CREATE TABLE attestation_acknowledgments (
                  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                  document_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
                  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                  acknowledged_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                  UNIQUE (document_id, user_id)
                )
                """);
        jdbc.execute("""
                CREATE TABLE attestation_campaigns (
                  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                  document_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
                  target_group_id UUID REFERENCES groups(id),
                  due_date DATE NOT NULL,
                  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """);
        jdbc.execute(new ClassPathResource("db/migration/V33__attestation_campaigns.sql")
                .getContentAsString(StandardCharsets.UTF_8));

        insertUser(OWNER, "owner");
        insertUser(READER, "reader");
        insertUser(OUTSIDER, "outsider");
        jdbc.update("INSERT INTO spaces (id, name) VALUES (?, 'S')", SPACE);
        jdbc.update("INSERT INTO documents (id, space_id, title, current_version_no) VALUES (?, ?, 'Doc', 3)",
                DOC, SPACE);
        jdbc.update("INSERT INTO groups (id, name) VALUES (?, 'G')", GROUP);
        jdbc.update("INSERT INTO group_members (group_id, user_id) VALUES (?, ?), (?, ?)",
                GROUP, READER, GROUP, OWNER);
    }

    static void insertUser(UUID id, String name) {
        jdbc.update("INSERT INTO users (id, email, display_name, status) VALUES (?, ?, ?, 'active')",
                id, name + "@x", name);
    }

    @BeforeEach
    void setUp() {
        service = new AttestationService(
                jdbc, userSyncService, authorizationService, documentRepository,
                auditService, reliabilityScoreService, Clock.fixed(NOW, ZoneOffset.UTC));
        jwt = Jwt.withTokenValue("t").header("alg", "none").subject("sub").build();

        document = new DocumentEntity();
        document.setId(DOC);
        document.setSpaceId(SPACE);
        document.setTitle("Doc");
        document.setCurrentVersionNo(3);
        when(documentRepository.findActiveById(DOC)).thenReturn(Optional.of(document));

        // Par défaut : tout le monde voit le document, seul OWNER est owner d'espace.
        when(authorizationService.hasRelation(any(), eq("document"), eq(DOC), eq("viewer"))).thenReturn(true);
        when(authorizationService.hasRelation(OWNER, "space", SPACE, "owner")).thenReturn(true);
        when(authorizationService.hasRelation(any(), eq("space"), eq(SPACE), eq("viewer")))
                .thenAnswer(inv -> !OUTSIDER.equals(inv.getArgument(0)));
        when(authorizationService.listSpaceMemberIds(SPACE)).thenReturn(Set.of(OWNER, READER, UUID.randomUUID()));

        actAs(OWNER);
        jdbc.update("DELETE FROM attestation_acknowledgments");
        jdbc.update("DELETE FROM attestation_campaigns");
        jdbc.update("UPDATE documents SET is_mandatory_ack = false, ack_due_date = NULL");
    }

    void actAs(UUID userId) {
        currentUser = userId;
        UserEntity user = new UserEntity();
        user.setId(userId);
        when(userSyncService.syncFromJwt(any())).thenReturn(user);
    }

    static CreateCampaignRequest spaceMembers(LocalDate due) {
        return new CreateCampaignRequest("space_members", null, due);
    }

    @Test
    void create_spaceMembers_freezesAudienceSize_marksMandatory_andAudits() {
        LocalDate due = LocalDate.of(2026, 11, 1);

        CampaignView c = service.create(jwt, DOC, spaceMembers(due));

        assertThat(c.audienceType()).isEqualTo("space_members");
        assertThat(c.audienceRef()).isNull();
        assertThat(c.audienceSize()).isEqualTo(3);
        assertThat(c.versionNo()).isEqualTo(3);
        assertThat(c.dueDate()).isEqualTo(due);
        assertThat(c.createdBy()).isEqualTo(OWNER);
        assertThat(jdbc.queryForObject(
                "SELECT is_mandatory_ack FROM documents WHERE id = ?", Boolean.class, DOC)).isTrue();
        verify(auditService).recordSync(eq(OWNER), eq(false),
                eq(AuditActions.ATTESTATION_CAMPAIGN_CREATED), eq("document"), eq(DOC), anyMap(), isNull());
        verify(reliabilityScoreService).recalculateIfValide(DOC);
    }

    @Test
    void create_group_usesGroupMemberCount() {
        CampaignView c = service.create(jwt, DOC, new CreateCampaignRequest("group", GROUP, null));

        assertThat(c.audienceSize()).isEqualTo(2);
        assertThat(c.audienceRef()).isEqualTo(GROUP);
        assertThat(c.dueDate()).isNull();
    }

    @Test
    void create_group_requiresKnownGroup() {
        assertStatus(() -> service.create(jwt, DOC, new CreateCampaignRequest("group", null, null)),
                HttpStatus.BAD_REQUEST);
        assertStatus(() -> service.create(jwt, DOC,
                        new CreateCampaignRequest("group", UUID.randomUUID(), null)),
                HttpStatus.BAD_REQUEST);
    }

    @Test
    void create_rejectsUnknownAudienceType_andPastDueDate() {
        assertStatus(() -> service.create(jwt, DOC, new CreateCampaignRequest("everyone", null, null)),
                HttpStatus.BAD_REQUEST);
        assertStatus(() -> service.create(jwt, DOC, spaceMembers(LocalDate.of(2026, 10, 14))),
                HttpStatus.BAD_REQUEST);
    }

    @Test
    void create_requiresSpaceOwner() {
        actAs(READER);

        assertStatus(() -> service.create(jwt, DOC, spaceMembers(null)), HttpStatus.FORBIDDEN);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM attestation_campaigns", Integer.class)).isZero();
        verify(auditService, never()).recordSync(any(), any(Boolean.class), any(), any(), any(), any(), any());
    }

    @Test
    void create_hidesDocumentFromNonViewer() {
        when(authorizationService.hasRelation(OUTSIDER, "document", DOC, "viewer")).thenReturn(false);
        actAs(OUTSIDER);

        assertStatus(() -> service.create(jwt, DOC, spaceMembers(null)), HttpStatus.NOT_FOUND);
    }

    @Test
    void create_secondOpenCampaign_conflicts_untilFirstClosed() {
        CampaignView first = service.create(jwt, DOC, spaceMembers(null));

        assertStatus(() -> service.create(jwt, DOC, spaceMembers(null)), HttpStatus.CONFLICT);

        service.close(jwt, DOC, first.id());
        CampaignView second = service.create(jwt, DOC, spaceMembers(null));
        assertThat(second.id()).isNotEqualTo(first.id());
    }

    @Test
    void close_setsClosedAt_clearsMandatory_andAudits() {
        CampaignView c = service.create(jwt, DOC, spaceMembers(null));

        service.close(jwt, DOC, c.id());

        assertThat(jdbc.queryForObject(
                "SELECT closed_at IS NOT NULL FROM attestation_campaigns WHERE id = ?", Boolean.class, c.id()))
                .isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT is_mandatory_ack FROM documents WHERE id = ?", Boolean.class, DOC)).isFalse();
        verify(auditService).recordSync(eq(OWNER), eq(false),
                eq(AuditActions.ATTESTATION_CAMPAIGN_CLOSED), eq("document"), eq(DOC), anyMap(), isNull());
        assertStatus(() -> service.close(jwt, DOC, c.id()), HttpStatus.CONFLICT);
    }

    @Test
    void close_requiresSpaceOwner_andKnownCampaign() {
        CampaignView c = service.create(jwt, DOC, spaceMembers(null));

        assertStatus(() -> service.close(jwt, DOC, UUID.randomUUID()), HttpStatus.NOT_FOUND);
        actAs(READER);
        assertStatus(() -> service.close(jwt, DOC, c.id()), HttpStatus.FORBIDDEN);
    }

    @Test
    void active_isEmptyWithoutCampaign() {
        assertThat(service.active(jwt, DOC)).isEmpty();
    }

    @Test
    void active_reportsCountsDueDateAndAckStatus() {
        CampaignView c = service.create(jwt, DOC, spaceMembers(LocalDate.of(2026, 11, 1)));
        actAs(READER);

        ActiveAttestation before = service.active(jwt, DOC).orElseThrow();
        assertThat(before.campaignId()).isEqualTo(c.id());
        assertThat(before.acknowledged()).isFalse();
        assertThat(before.ackCount()).isZero();
        assertThat(before.audienceSize()).isEqualTo(3);
        assertThat(before.dueDate()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(before.overdue()).isFalse();

        service.acknowledge(jwt, DOC, c.id());

        ActiveAttestation after = service.active(jwt, DOC).orElseThrow();
        assertThat(after.acknowledged()).isTrue();
        assertThat(after.acknowledgedAt()).isEqualTo(NOW);
        assertThat(after.ackCount()).isEqualTo(1);
        assertThat(after.audienceSize()).isEqualTo(3);
    }

    @Test
    void active_isEmptyForUserOutsideAudience() {
        service.create(jwt, DOC, new CreateCampaignRequest("group", GROUP, null));
        actAs(OUTSIDER);

        assertThat(service.active(jwt, DOC)).isEmpty();
    }

    @Test
    void active_ignoresClosedCampaign() {
        CampaignView c = service.create(jwt, DOC, spaceMembers(null));
        service.close(jwt, DOC, c.id());

        assertThat(service.active(jwt, DOC)).isEmpty();
    }

    @Test
    void active_flagsOverdueCampaign() {
        CampaignView c = service.create(jwt, DOC, spaceMembers(LocalDate.of(2026, 10, 15)));
        jdbc.update("UPDATE attestation_campaigns SET due_date = '2026-10-01' WHERE id = ?", c.id());

        assertThat(service.active(jwt, DOC).orElseThrow().overdue()).isTrue();
    }

    @Test
    void acknowledge_storesCurrentVersion_isIdempotent_andAuditsOnce() {
        CampaignView c = service.create(jwt, DOC, spaceMembers(null));
        actAs(READER);

        service.acknowledge(jwt, DOC, c.id());
        document.setCurrentVersionNo(4);
        ActiveAttestation again = service.acknowledge(jwt, DOC, c.id());

        assertThat(again.ackCount()).isEqualTo(1);
        assertThat(again.acknowledgedVersionNo()).isEqualTo(3);
        assertThat(again.currentVersionNo()).isEqualTo(4);
        Integer version = jdbc.queryForObject(
                "SELECT version_no FROM attestation_acknowledgments WHERE campaign_id = ? AND user_id = ?",
                Integer.class, c.id(), READER);
        assertThat(version).isEqualTo(3);
        verify(auditService).recordSync(eq(READER), eq(false),
                eq(AuditActions.ATTESTATION_ACKNOWLEDGED), eq("document"), eq(DOC), anyMap(), isNull());
        verify(reliabilityScoreService).onAttestationAcknowledged(DOC);
    }

    @Test
    void acknowledge_rejectsUserOutsideAudience_closedCampaign_andForeignCampaign() {
        CampaignView group = service.create(jwt, DOC, new CreateCampaignRequest("group", GROUP, null));
        actAs(OUTSIDER);
        assertStatus(() -> service.acknowledge(jwt, DOC, group.id()), HttpStatus.FORBIDDEN);

        actAs(OWNER);
        service.close(jwt, DOC, group.id());
        actAs(READER);
        assertStatus(() -> service.acknowledge(jwt, DOC, group.id()), HttpStatus.CONFLICT);
        assertStatus(() -> service.acknowledge(jwt, DOC, UUID.randomUUID()), HttpStatus.NOT_FOUND);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM attestation_acknowledgments", Integer.class))
                .isZero();
    }

    @Test
    void acknowledgments_nominativeList_isOwnerOnly() {
        CampaignView c = service.create(jwt, DOC, spaceMembers(null));
        actAs(READER);
        service.acknowledge(jwt, DOC, c.id());

        assertStatus(() -> service.acknowledgments(jwt, DOC, c.id()), HttpStatus.FORBIDDEN);

        actAs(OWNER);
        List<AcknowledgmentView> list = service.acknowledgments(jwt, DOC, c.id());
        assertThat(list).hasSize(1);
        assertThat(list.getFirst().userId()).isEqualTo(READER);
        assertThat(list.getFirst().displayName()).isEqualTo("reader");
        assertThat(list.getFirst().versionNo()).isEqualTo(3);
        assertThat(currentUser).isEqualTo(OWNER);
    }

    @Test
    void schema_enforcesOneAcknowledgmentPerUserAndCampaign_andOneOpenCampaign() {
        CampaignView c = service.create(jwt, DOC, spaceMembers(null));
        jdbc.update("INSERT INTO attestation_acknowledgments (campaign_id, user_id, version_no) VALUES (?, ?, 1)",
                c.id(), READER);

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO attestation_acknowledgments (campaign_id, user_id, version_no) VALUES (?, ?, 1)",
                c.id(), READER)).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO attestation_campaigns (document_id, audience_type, audience_size)
                VALUES (?, 'space_members', 1)
                """, DOC)).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO attestation_campaigns (document_id, audience_type, audience_size, closed_at)
                VALUES (?, 'nobody', 1, now())
                """, DOC)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    private static void assertStatus(Runnable call, HttpStatus expected) {
        assertThatThrownBy(call::run)
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(expected);
    }
}
