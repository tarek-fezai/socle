// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.document;

import eu.socle.authz.AuthorizationService;
import eu.socle.user.UserEntity;
import eu.socle.user.UserSyncService;
import eu.socle.workflowdef.ApprovalWorkflowDefinitionService;
import io.temporal.client.WorkflowClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Zones {@code placeholder} (modèles de pages) non complétées : 409 à la soumission,
 * avant tout démarrage Temporal.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DocumentApprovalPlaceholderTest {

    static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID DOC = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static final UUID SPACE = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @Mock WorkflowClient workflowClient;
    @Mock DocumentRepository documentRepository;
    @Mock UserSyncService userSyncService;
    @Mock AuthorizationService authorizationService;
    @Mock JdbcTemplate jdbcTemplate;
    @Mock ApprovalActivitiesImpl activities;
    @Mock ApprovalWorkflowDefinitionService workflowDefinitions;
    @Mock ApprovalRoleResolver approvalRoleResolver;

    DocumentApprovalService service;

    @BeforeEach
    void setUp() {
        service = new DocumentApprovalService(
                workflowClient, documentRepository, userSyncService, authorizationService,
                jdbcTemplate, activities, workflowDefinitions, approvalRoleResolver,
                DecideExpectedStepOrderTest.passthroughTx(), false);
        UserEntity u = new UserEntity();
        u.setId(USER);
        u.setEmail("u@ex.com");
        u.setDisplayName("U");
        when(userSyncService.syncFromJwt(any())).thenReturn(u);
        when(jdbcTemplate.queryForObject(contains("approval_requests WHERE document_id"),
                eq(Integer.class), eq(DOC))).thenReturn(0);
    }

    @Test
    void startApproval_withRemainingPlaceholders_returns409ListingHints_andNeverStartsTemporal() {
        Map<String, Object> body = doc(
                paragraph("Intro"),
                placeholder("Décrire l'objectif"),
                // placeholder imbriqué (liste → item → placeholder)
                nested("bulletList", nested("listItem", placeholder("Périmètre d'application"))));
        when(documentRepository.findActiveById(DOC)).thenReturn(Optional.of(document(body)));

        assertThatThrownBy(() -> service.startApproval(jwt(), DOC))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getReason())
                            .contains("Décrire l'objectif")
                            .contains("Périmètre d'application");
                });

        // Authz exécutée avant (ordre auth → authz → workflow conservé) ; aucun workflow démarré.
        verify(authorizationService).requireDocumentRelation(USER, DOC, "editor");
        verify(authorizationService).requireSpaceRelation(USER, SPACE, "viewer");
        verifyNoInteractions(workflowClient);
        verify(workflowDefinitions, never()).resolveId(any(), any());
    }

    @Test
    void startApproval_placeholderWithoutHint_stillBlocks() {
        Map<String, Object> bare = new LinkedHashMap<>();
        bare.put("type", "placeholder");
        when(documentRepository.findActiveById(DOC)).thenReturn(Optional.of(document(doc(bare))));

        assertThatThrownBy(() -> service.startApproval(jwt(), DOC))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        verifyNoInteractions(workflowClient);
    }

    @Test
    void placeholderGate_detectsOnlyRealPlaceholderNodes() {
        // Texte contenant le mot « placeholder » ≠ nœud placeholder
        Map<String, Object> clean = doc(paragraph("Un placeholder dans le texte"));
        assertThat(eu.socle.template.TemplateBodySupport.collectPlaceholderHints(clean)).isEmpty();
        assertThat(eu.socle.template.TemplateBodySupport.collectPlaceholderHints(null)).isEmpty();
        assertThat(eu.socle.template.TemplateBodySupport.collectPlaceholderHints(
                doc(placeholder("A"), placeholder("B")))).containsExactly("A", "B");
    }

    // ------------------------------------------------------------------

    private static DocumentEntity document(Map<String, Object> body) {
        DocumentEntity d = new DocumentEntity();
        d.setId(DOC);
        d.setSpaceId(SPACE);
        d.setTitle("Doc");
        d.setBody(body);
        d.setStatus("brouillon");
        return d;
    }

    @SafeVarargs
    private static Map<String, Object> doc(Map<String, Object>... blocks) {
        return nested("doc", blocks);
    }

    @SafeVarargs
    private static Map<String, Object> nested(String type, Map<String, Object>... children) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("type", type);
        n.put("content", new ArrayList<>(List.of(children)));
        return n;
    }

    private static Map<String, Object> paragraph(String text) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("type", "text");
        t.put("text", text);
        return nested("paragraph", t);
    }

    private static Map<String, Object> placeholder(String hint) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "placeholder");
        p.put("attrs", new LinkedHashMap<>(Map.of("hint", hint)));
        return p;
    }

    private static Jwt jwt() {
        return Jwt.withTokenValue("t").header("alg", "none").subject(USER.toString())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
    }
}
