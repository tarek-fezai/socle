// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.template;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.socle.audit.AuditActions;
import eu.socle.audit.AuditService;
import eu.socle.authz.AuthorizationService;
import eu.socle.document.DocumentEntity;
import eu.socle.document.DocumentRepository;
import eu.socle.document.DocumentVisibility;
import eu.socle.document.TransclusionResolver;
import eu.socle.identity.IdentityFacade;
import eu.socle.storage.DocumentStore;
import eu.socle.template.TemplateDtos.CreateTemplateRequest;
import eu.socle.template.TemplateDtos.SaveAsTemplateRequest;
import eu.socle.template.TemplateDtos.TemplateSummary;
import eu.socle.template.TemplateDtos.UpdateTemplateRequest;
import eu.socle.template.TemplateService.TemplateInstantiation;
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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Modèles de pages sur PostgreSQL réel : schéma V1 minimal + migration V27 réelle
 * (ALTER + seed). Autorisation, identité, audit, store et résolveur de transclusion sont mockés.
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("dockerAvailable")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TemplateServiceTest {

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("socle_core")
            .withUsername("socle")
            .withPassword("socle");

    static JdbcTemplate jdbc;
    /** Fragment {@code INSERT INTO templates ... WHERE NOT EXISTS} de V27 (seed idempotent). */
    static String seedSql;

    static final UUID ADMIN = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static final UUID OWNER = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    static final UUID MEMBER = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    static final UUID OUTSIDER = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
    static final UUID SPACE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID OTHER_SPACE = UUID.fromString("33333333-3333-3333-3333-333333333333");
    static final UUID DOC = UUID.fromString("22222222-2222-2222-2222-222222222222");
    static final UUID TAG_A = UUID.fromString("44444444-4444-4444-4444-444444444444");
    static final UUID TAG_B = UUID.fromString("55555555-5555-5555-5555-555555555555");
    static final UUID TAG_MISSING = UUID.fromString("66666666-6666-6666-6666-666666666666");
    static final UUID TARGET_DOC = UUID.fromString("77777777-7777-7777-7777-777777777777");

    /** 22:30 UTC = lendemain 00:30 à Paris → {{date}} suit Europe/Paris. */
    static final Instant NOW = Instant.parse("2026-10-01T22:30:00Z");

    @Mock AuthorizationService authorizationService;
    @Mock UserSyncService userSyncService;
    @Mock IdentityFacade identityFacade;
    @Mock AuditService auditService;
    @Mock DocumentRepository documentRepository;
    @Mock DocumentStore documentStore;
    @Mock TransclusionResolver transclusionResolver;

    TemplateService service;

    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @BeforeAll
    static void schema() throws IOException {
        var ds = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS pgcrypto");
        jdbc.execute("""
                CREATE TABLE users (
                  id UUID PRIMARY KEY, email TEXT NOT NULL, display_name TEXT NOT NULL, status TEXT NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE spaces (
                  id UUID PRIMARY KEY, name TEXT NOT NULL, deleted_at TIMESTAMPTZ
                )
                """);
        jdbc.execute("""
                CREATE TABLE documents (
                  id UUID PRIMARY KEY, space_id UUID NOT NULL REFERENCES spaces(id), title TEXT NOT NULL,
                  body JSONB NOT NULL DEFAULT '{}',
                  doc_type TEXT,
                  deleted_at TIMESTAMPTZ
                )
                """);
        jdbc.execute("""
                CREATE TABLE tags (
                  id UUID PRIMARY KEY DEFAULT gen_random_uuid(), name TEXT NOT NULL UNIQUE, color TEXT
                )
                """);
        jdbc.execute("""
                CREATE TABLE document_tags (
                  document_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
                  tag_id UUID NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
                  PRIMARY KEY (document_id, tag_id)
                )
                """);
        jdbc.execute("""
                CREATE TABLE document_comments (
                  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                  document_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
                  body TEXT NOT NULL
                )
                """);
        // Table templates telle que livrée par V1 — V27 la fait évoluer.
        jdbc.execute("""
                CREATE TABLE templates (
                  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                  name TEXT NOT NULL,
                  body_template JSONB NOT NULL,
                  created_by UUID REFERENCES users(id),
                  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """);

        jdbc.update("INSERT INTO users VALUES (?,?,?,?), (?,?,?,?), (?,?,?,?), (?,?,?,?)",
                ADMIN, "admin@ex.com", "Admin", "active",
                OWNER, "owner@ex.com", "Olivia Owner", "active",
                MEMBER, "member@ex.com", "Mehdi Member", "active",
                OUTSIDER, "out@ex.com", "Oscar Outsider", "active");
        jdbc.update("INSERT INTO spaces (id, name) VALUES (?, ?), (?, ?)",
                SPACE, "Ingénierie", OTHER_SPACE, "Finance");
        jdbc.update("INSERT INTO tags (id, name) VALUES (?, 'iam'), (?, 'rgpd')", TAG_A, TAG_B);
        jdbc.update("""
                INSERT INTO documents (id, space_id, title, body, doc_type)
                VALUES (?, ?, 'Source', '{"type":"doc","content":[]}'::jsonb, 'note')
                """, DOC, SPACE);

        // Migration réelle V27 (ALTER templates/documents + seed).
        String v27 = new String(
                new ClassPathResource("db/migration/V27__templates.sql").getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        jdbc.execute(v27);
        seedSql = v27.substring(v27.indexOf("INSERT INTO templates"));
    }

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM document_tags");
        jdbc.update("DELETE FROM document_comments");
        jdbc.update("DELETE FROM documents WHERE id <> ?", DOC);
        jdbc.update("UPDATE documents SET template_id = NULL, template_version = NULL, "
                + "deleted_at = NULL WHERE id = ?", DOC);
        jdbc.update("DELETE FROM templates WHERE seed_key IS NULL");
        jdbc.update("UPDATE templates SET deleted_at = NULL WHERE seed_key IS NOT NULL");
        jdbc.update("UPDATE spaces SET deleted_at = NULL");

        service = new TemplateService(
                jdbc, userSyncService, identityFacade, authorizationService, auditService,
                documentRepository, documentStore, transclusionResolver,
                new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));

        when(userSyncService.syncFromJwt(any())).thenAnswer(inv -> {
            Jwt jwt = inv.getArgument(0);
            return user(UUID.fromString(jwt.getSubject()));
        });
        when(identityFacade.isSystemAdmin(any())).thenAnswer(inv -> {
            Jwt jwt = inv.getArgument(0);
            return ADMIN.toString().equals(jwt.getSubject());
        });
        // OWNER : owner+viewer de SPACE ; MEMBER : viewer de SPACE ; ADMIN : viewer ; OUTSIDER : rien.
        when(authorizationService.hasRelation(OWNER, "space", SPACE, "owner")).thenReturn(true);
        when(authorizationService.hasRelation(OWNER, "space", SPACE, "viewer")).thenReturn(true);
        when(authorizationService.hasRelation(MEMBER, "space", SPACE, "viewer")).thenReturn(true);
        when(authorizationService.hasRelation(ADMIN, "space", SPACE, "viewer")).thenReturn(true);
        for (UUID denied : List.of(MEMBER, OUTSIDER)) {
            doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN))
                    .when(authorizationService).requireDocumentRelation(denied, DOC, "editor");
        }
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN))
                .when(authorizationService).requireSpaceRelation(OUTSIDER, SPACE, "viewer");

        // normalizeForStorage : identité (la logique réelle est couverte par TransclusionResolverTest)
        when(transclusionResolver.normalizeForStorage(any())).thenAnswer(inv -> inv.getArgument(0));
        when(transclusionResolver.extractDirectTargets(any())).thenReturn(List.of());
        when(transclusionResolver.allowsInterWorkspaceEdge(any(), any())).thenReturn(true);
    }

    // ------------------------------------------------------------------
    // Droits d'écriture
    // ------------------------------------------------------------------

    @Test
    void nonOwner_cannotCreateSpaceTemplate_403() {
        var request = new CreateTemplateRequest("Fiche", null, null, null, simpleBody("x"), SPACE);

        assertStatus(() -> service.create(jwt(MEMBER), request), HttpStatus.FORBIDDEN);
        assertStatus(() -> service.create(jwt(OUTSIDER), request), HttpStatus.FORBIDDEN);
        assertThat(count("SELECT count(*) FROM templates WHERE name = 'Fiche'")).isZero();
        verify(auditService, never()).record(any(), any(Boolean.class),
                eq(AuditActions.TEMPLATE_CREATED), any(), any(), any(), any());
    }

    @Test
    void owner_createsSpaceTemplate_andNonAdminCannotCreateGlobal() {
        var created = service.create(jwt(OWNER), new CreateTemplateRequest(
                "  Fiche  ", " desc ", "procedure", List.of(TAG_A, TAG_A, TAG_B), simpleBody("x"), SPACE));

        assertThat(created.name()).isEqualTo("Fiche");
        assertThat(created.scope()).isEqualTo("space");
        assertThat(created.spaceId()).isEqualTo(SPACE);
        assertThat(created.version()).isEqualTo(1);
        assertThat(created.canManage()).isTrue();
        assertThat(created.defaultTagIds()).containsExactly(TAG_A, TAG_B);
        assertThat(created.body()).isNotEmpty();

        var global = new CreateTemplateRequest("Global", null, null, null, simpleBody("g"), null);
        assertStatus(() -> service.create(jwt(OWNER), global), HttpStatus.FORBIDDEN);
        assertThat(service.create(jwt(ADMIN), global).scope()).isEqualTo("global");
    }

    // ------------------------------------------------------------------
    // Lecture
    // ------------------------------------------------------------------

    @Test
    void nonMember_cannotGetSpaceTemplate_404_andItIsNotListed() {
        UUID id = service.create(jwt(OWNER), new CreateTemplateRequest(
                "Secret", null, null, null, simpleBody("s"), SPACE)).id();

        assertStatus(() -> service.get(jwt(OUTSIDER), id), HttpStatus.NOT_FOUND);

        // Même en demandant explicitement l'espace : pas listé, aucun signal d'existence.
        assertThat(names(service.list(jwt(OUTSIDER), SPACE))).doesNotContain("Secret");
        assertThat(names(service.list(jwt(OUTSIDER), null))).doesNotContain("Secret");
        // Un modèle inexistant répond de la même façon.
        assertStatus(() -> service.get(jwt(OUTSIDER), UUID.randomUUID()), HttpStatus.NOT_FOUND);

        // Membre : visible, sans droit de gestion.
        assertThat(service.get(jwt(MEMBER), id).canManage()).isFalse();
        List<TemplateSummary> memberList = service.list(jwt(MEMBER), SPACE);
        assertThat(names(memberList)).contains("Secret");
        assertThat(memberList).filteredOn(t -> t.id().equals(id))
                .singleElement().satisfies(t -> {
                    assertThat(t.scope()).isEqualTo("space");
                    assertThat(t.canManage()).isFalse();
                });
        // Sans spaceId : globaux uniquement
        assertThat(names(service.list(jwt(MEMBER), null))).doesNotContain("Secret");
    }

    @Test
    void globals_areVisibleToAuthenticatedUsers_includingSeeds() {
        UUID id = service.create(jwt(ADMIN), new CreateTemplateRequest(
                "Charte", "d", "politique", List.of(), simpleBody("c"), null)).id();

        for (UUID u : List.of(OWNER, MEMBER, OUTSIDER)) {
            List<TemplateSummary> list = service.list(jwt(u), null);
            assertThat(names(list)).contains("Charte", "Procédure");
            assertThat(list).allMatch(t -> t.spaceId() == null && "global".equals(t.scope()));
            // non-admins : lecture seule
            assertThat(list).filteredOn(t -> t.id().equals(id)).singleElement()
                    .satisfies(t -> assertThat(t.canManage()).isFalse());
            assertThat(service.get(jwt(u), id).body()).isNotEmpty();
        }
        // L'admin système peut gérer les globaux
        assertThat(service.list(jwt(ADMIN), null)).filteredOn(t -> t.id().equals(id))
                .singleElement().satisfies(t -> assertThat(t.canManage()).isTrue());
    }

    // ------------------------------------------------------------------
    // Instanciation
    // ------------------------------------------------------------------

    @Test
    void prepareInstantiation_replacesVariables_andCarriesDocTypeTagsAndProvenance() {
        Map<String, Object> body = doc(
                para("Par {{auteur}} le {{date}} dans {{espace}} : {{titre}}"),
                placeholder("{{titre}} reste dans le hint"));
        UUID tpl = service.create(jwt(OWNER), new CreateTemplateRequest(
                "ADR", null, "adr", List.of(TAG_A, TAG_MISSING), body, SPACE)).id();
        // une mise à jour porte la version à 2
        service.update(jwt(OWNER), tpl, new UpdateTemplateRequest("ADR v2", null, null, null, null));

        TemplateInstantiation inst = service.prepareInstantiation(
                user(MEMBER), tpl, SPACE, "  Choix du bus  ");

        assertThat(inst.templateId()).isEqualTo(tpl);
        assertThat(inst.version()).isEqualTo(2);
        assertThat(inst.name()).isEqualTo("ADR v2");
        assertThat(inst.docType()).isEqualTo("adr");
        assertThat(inst.defaultTagIds()).containsExactly(TAG_A, TAG_MISSING);
        String rendered = inst.body().toString();
        // {{date}} : 2026-10-02 (Europe/Paris), pas 2026-10-01 (UTC)
        assertThat(rendered)
                .contains("Par Mehdi Member le 2026-10-02 dans Ingénierie : Choix du bus")
                .doesNotContain("{{auteur}}", "{{date}}", "{{espace}}");
        assertThat(rendered).contains("{{titre}} reste dans le hint"); // attrs non substitués

        // Le modèle stocké n'est jamais muté par l'instanciation.
        assertThat(service.get(jwt(OWNER), tpl).body().toString()).contains("{{auteur}}");

        // Insertion du document comme le fait DocumentService, puis effets de bord du service.
        UUID docId = UUID.randomUUID();
        insertDocumentFrom(inst, docId, "Choix du bus");
        service.afterDocumentCreated(MEMBER, inst, docId, SPACE);

        Map<String, Object> row = documentRow(docId);
        assertThat(row.get("template_id")).isEqualTo(tpl);
        assertThat(row.get("template_version")).isEqualTo(2);
        assertThat(row.get("doc_type")).isEqualTo("adr");
        // tags : l'id inexistant est ignoré
        assertThat(jdbc.queryForList(
                "SELECT tag_id FROM document_tags WHERE document_id = ?", UUID.class, docId))
                .containsExactly(TAG_A);
        verify(auditService).record(eq(MEMBER), eq(false), eq(AuditActions.TEMPLATE_USED),
                eq("template"), eq(tpl), anyMap(), any());
    }

    @Test
    void prepareInstantiation_unreadableOrWrongSpace() {
        UUID tpl = service.create(jwt(OWNER), new CreateTemplateRequest(
                "Esp", null, null, null, simpleBody("x"), SPACE)).id();

        assertStatus(() -> service.prepareInstantiation(user(OUTSIDER), tpl, SPACE, "t"), HttpStatus.NOT_FOUND);
        assertStatus(() -> service.prepareInstantiation(user(MEMBER), tpl, OTHER_SPACE, "t"), HttpStatus.FORBIDDEN);
        assertStatus(() -> service.prepareInstantiation(user(MEMBER), UUID.randomUUID(), SPACE, "t"),
                HttpStatus.NOT_FOUND);
    }

    // ------------------------------------------------------------------
    // Snapshot : update / delete n'altèrent pas les documents existants
    // ------------------------------------------------------------------

    @Test
    void updateAndDelete_doNotChangeExistingDocuments() {
        UUID tpl = service.create(jwt(OWNER), new CreateTemplateRequest(
                "Compte rendu", null, "cr", List.of(), simpleBody("v1 {{titre}}"), SPACE)).id();
        TemplateInstantiation inst = service.prepareInstantiation(user(MEMBER), tpl, SPACE, "Réunion");
        UUID docId = UUID.randomUUID();
        insertDocumentFrom(inst, docId, "Réunion");

        Map<String, Object> before = documentRow(docId);
        assertThat(before.get("template_id")).isEqualTo(tpl);
        assertThat(before.get("template_version")).isEqualTo(1);

        var updated = service.update(jwt(OWNER), tpl, new UpdateTemplateRequest(
                "Compte rendu v2", "nouvelle desc", "autre", List.of(TAG_B), simpleBody("v2 modifié")));
        assertThat(updated.version()).isEqualTo(2);
        assertThat(documentRow(docId)).isEqualTo(before);

        service.delete(jwt(OWNER), tpl);
        // supprimé : illisible (404), mais ligne conservée (soft-delete) et document inchangé
        assertStatus(() -> service.get(jwt(OWNER), tpl), HttpStatus.NOT_FOUND);
        assertThat(names(service.list(jwt(OWNER), SPACE))).doesNotContain("Compte rendu v2");
        assertThat(count("SELECT count(*) FROM templates WHERE id = '" + tpl + "' AND deleted_at IS NOT NULL"))
                .isEqualTo(1);
        assertThat(documentRow(docId)).isEqualTo(before);
        assertStatus(() -> service.prepareInstantiation(user(MEMBER), tpl, SPACE, "x"), HttpStatus.NOT_FOUND);
    }

    @Test
    void update_andDelete_requireManageRight() {
        UUID tpl = service.create(jwt(OWNER), new CreateTemplateRequest(
                "Géré", null, null, null, simpleBody("x"), SPACE)).id();

        assertStatus(() -> service.update(jwt(MEMBER), tpl, new UpdateTemplateRequest("Hack", null, null, null, null)),
                HttpStatus.FORBIDDEN);
        assertStatus(() -> service.delete(jwt(MEMBER), tpl), HttpStatus.FORBIDDEN);
        // non-membre : 404 avant tout (pas de fuite d'existence)
        assertStatus(() -> service.delete(jwt(OUTSIDER), tpl), HttpStatus.NOT_FOUND);
        assertThat(service.get(jwt(OWNER), tpl).name()).isEqualTo("Géré");
    }

    // ------------------------------------------------------------------
    // Enregistrer comme modèle
    // ------------------------------------------------------------------

    @Test
    void saveAsTemplate_copiesBodyOnly_noCommentsNoMetadata() {
        Map<String, Object> source = doc(para("Contenu du document"));
        DocumentEntity entity = new DocumentEntity();
        entity.setId(DOC);
        entity.setSpaceId(SPACE);
        entity.setBody(Map.of("type", "doc", "content", List.of()));
        entity.setVisibility(DocumentVisibility.ORGANISATION);
        when(documentRepository.findActiveById(DOC)).thenReturn(Optional.of(entity));
        when(documentStore.readCurrentContent(eq(DOC), any())).thenReturn(source);
        jdbc.update("INSERT INTO document_comments (document_id, body) VALUES (?, 'COMMENTAIRE-SECRET')", DOC);
        jdbc.update("INSERT INTO document_tags (document_id, tag_id) VALUES (?, ?)", DOC, TAG_A);

        var response = service.saveFromDocument(jwt(OWNER), DOC, new SaveAsTemplateRequest(
                "space", SPACE, "Depuis doc", "d"));

        var t = response.template();
        assertThat(t.scope()).isEqualTo("space");
        assertThat(t.spaceId()).isEqualTo(SPACE);
        assertThat(t.body().toString()).contains("Contenu du document").doesNotContain("COMMENTAIRE-SECRET");
        assertThat(t.docType()).isNull();            // doc_type du document non copié
        assertThat(t.defaultTagIds()).isEmpty();     // tags non copiés
        assertThat(response.warnings()).isEmpty();   // visibilité organisation
        assertThat(count("SELECT count(*) FROM templates WHERE id = '" + t.id()
                + "' AND body::text LIKE '%COMMENTAIRE-SECRET%'")).isZero();
        // Le document source est inchangé (pas de lien de provenance côté document)
        assertThat(documentRow(DOC).get("template_id")).isNull();
        verify(auditService).record(eq(OWNER), eq(false), eq(AuditActions.TEMPLATE_CREATED_FROM_DOCUMENT),
                eq("template"), eq(t.id()), anyMap(), any());
    }

    @Test
    void saveAsTemplate_warnsWhenNotOrganisationVisible_andChecksRights() {
        DocumentEntity entity = new DocumentEntity();
        entity.setId(DOC);
        entity.setSpaceId(SPACE);
        entity.setBody(Map.of());
        entity.setVisibility(DocumentVisibility.SPACE);
        when(documentRepository.findActiveById(DOC)).thenReturn(Optional.of(entity));
        when(documentStore.readCurrentContent(eq(DOC), any())).thenReturn(doc(para("x")));

        var response = service.saveFromDocument(jwt(OWNER), DOC, new SaveAsTemplateRequest(
                "space", SPACE, "Visible ?", null));
        assertThat(response.warnings()).hasSize(1);

        // editor requis sur le document
        assertStatus(() -> service.saveFromDocument(jwt(MEMBER), DOC,
                new SaveAsTemplateRequest("space", SPACE, "Non", null)), HttpStatus.FORBIDDEN);
        // global : réservé à l'admin système (OWNER a editor sur le doc mais n'est pas admin)
        assertStatus(() -> service.saveFromDocument(jwt(OWNER), DOC,
                new SaveAsTemplateRequest("global", null, "Glob", null)), HttpStatus.FORBIDDEN);
        // portée invalide / espace manquant
        assertStatus(() -> service.saveFromDocument(jwt(OWNER), DOC,
                new SaveAsTemplateRequest("space", null, "Sans espace", null)), HttpStatus.BAD_REQUEST);
        assertStatus(() -> service.saveFromDocument(jwt(OWNER), DOC,
                new SaveAsTemplateRequest("autre", null, "Mauvais", null)), HttpStatus.BAD_REQUEST);
    }

    // ------------------------------------------------------------------
    // Seed
    // ------------------------------------------------------------------

    @Test
    void seed_isIdempotent_andSoftDeletedSeedIsNeverRecreated() {
        int seeds = count("SELECT count(*) FROM templates WHERE seed_key IS NOT NULL");
        assertThat(seeds).isEqualTo(5);

        // Re-run : rien de dupliqué
        jdbc.execute(seedSql);
        assertThat(count("SELECT count(*) FROM templates WHERE seed_key IS NOT NULL")).isEqualTo(seeds);

        // L'admin supprime le modèle de démarrage « ADR »
        UUID adr = jdbc.queryForObject(
                "SELECT id FROM templates WHERE seed_key = 'seed.adr'", UUID.class);
        service.delete(jwt(ADMIN), adr);
        assertStatus(() -> service.get(jwt(MEMBER), adr), HttpStatus.NOT_FOUND);

        // Nouveau passage du seed (redémarrage / nouvelle migration idempotente)
        jdbc.execute(seedSql);

        assertThat(count("SELECT count(*) FROM templates WHERE seed_key = 'seed.adr'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM templates WHERE seed_key = 'seed.adr' AND deleted_at IS NOT NULL"))
                .isEqualTo(1);
        assertThat(names(service.list(jwt(MEMBER), null))).doesNotContain("Décision d'architecture (ADR)");
        assertThat(names(service.list(jwt(MEMBER), null))).contains("Procédure", "Politique");
    }

    // ------------------------------------------------------------------
    // Avertissements de création
    // ------------------------------------------------------------------

    @Test
    void creationWarnings_transclusionToRestrictedSpace() {
        jdbc.update("INSERT INTO documents (id, space_id, title) VALUES (?, ?, 'Cible')", TARGET_DOC, OTHER_SPACE);
        Map<String, Object> body = doc(transclusion(TARGET_DOC));
        when(transclusionResolver.extractDirectTargets(any())).thenReturn(List.of(TARGET_DOC));
        UUID tpl = service.create(jwt(ADMIN), new CreateTemplateRequest(
                "Avec transclusion", null, null, null, body, null)).id();

        // Espace cible autorisé : aucun avertissement
        assertThat(service.creationWarnings(jwt(MEMBER), tpl, SPACE).warnings()).isEmpty();

        // external_reference=restricted entre SPACE et l'espace de la cible
        when(transclusionResolver.allowsInterWorkspaceEdge(SPACE, OTHER_SPACE)).thenReturn(false);
        var response = service.creationWarnings(jwt(MEMBER), tpl, SPACE);

        assertThat(response.templateId()).isEqualTo(tpl);
        assertThat(response.spaceId()).isEqualTo(SPACE);
        assertThat(response.warnings()).singleElement().satisfies(w -> {
            assertThat(w.code()).isEqualTo(TemplateService.WARNING_TRANSCLUSION_RESTRICTED);
            assertThat(w.message()).isNotBlank();
            assertThat(w.targetDocumentIds()).containsExactly(TARGET_DOC);
        });

        // Cible supprimée : ignorée
        jdbc.update("UPDATE documents SET deleted_at = now() WHERE id = ?", TARGET_DOC);
        assertThat(service.creationWarnings(jwt(MEMBER), tpl, SPACE).warnings()).isEmpty();
    }

    @Test
    void creationWarnings_requireSpaceViewer_andSpaceId() {
        UUID tpl = service.create(jwt(ADMIN), new CreateTemplateRequest(
                "G", null, null, null, simpleBody("g"), null)).id();

        assertStatus(() -> service.creationWarnings(jwt(OUTSIDER), tpl, SPACE), HttpStatus.FORBIDDEN);
        assertStatus(() -> service.creationWarnings(jwt(MEMBER), tpl, null), HttpStatus.BAD_REQUEST);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void insertDocumentFrom(TemplateInstantiation inst, UUID docId, String title) {
        jdbc.update("""
                INSERT INTO documents (id, space_id, title, body, doc_type, template_id, template_version)
                VALUES (?, ?, ?, ?::jsonb, ?, ?, ?)
                """, docId, SPACE, title, toJson(inst.body()), inst.docType(),
                inst.templateId(), inst.version());
    }

    private Map<String, Object> documentRow(UUID docId) {
        return new HashMap<>(jdbc.queryForMap(
                "SELECT id, title, body::text AS body, doc_type, template_id, template_version "
                        + "FROM documents WHERE id = ?", docId));
    }

    private static String toJson(Map<String, Object> body) {
        try {
            return new ObjectMapper().writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static int count(String sql) {
        Integer n = jdbc.queryForObject(sql, Integer.class);
        return n == null ? 0 : n;
    }

    private static List<String> names(List<TemplateSummary> list) {
        return list.stream().map(TemplateSummary::name).toList();
    }

    private static void assertStatus(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, HttpStatus status) {
        assertThatThrownBy(call)
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(status));
    }

    private static Map<String, Object> simpleBody(String text) {
        return doc(para(text));
    }

    private static Map<String, Object> para(String text) {
        Map<String, Object> p = new HashMap<>();
        p.put("type", "paragraph");
        p.put("content", new ArrayList<>(List.of(Map.of("type", "text", "text", text))));
        return p;
    }

    private static Map<String, Object> placeholder(String hint) {
        Map<String, Object> p = new HashMap<>();
        p.put("type", "placeholder");
        p.put("attrs", new HashMap<>(Map.of("hint", hint)));
        return p;
    }

    private static Map<String, Object> transclusion(UUID target) {
        Map<String, Object> p = new HashMap<>();
        p.put("type", "transclusion");
        p.put("attrs", new HashMap<>(Map.of("documentId", target.toString())));
        return p;
    }

    @SafeVarargs
    private static Map<String, Object> doc(Map<String, Object>... nodes) {
        Map<String, Object> d = new HashMap<>();
        d.put("type", "doc");
        d.put("content", new ArrayList<>(List.of(nodes)));
        return d;
    }

    private static UserEntity user(UUID id) {
        UserEntity u = new UserEntity();
        u.setId(id);
        u.setEmail(id + "@ex.com");
        u.setDisplayName(jdbc.queryForObject("SELECT display_name FROM users WHERE id = ?", String.class, id));
        return u;
    }

    private static Jwt jwt(UUID sub) {
        return Jwt.withTokenValue("t").header("alg", "none").subject(sub.toString())
                .issuedAt(NOW).expiresAt(NOW.plusSeconds(60)).build();
    }
}
