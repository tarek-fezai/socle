package eu.socle.document;

import eu.socle.authz.AuthorizationService;
import eu.socle.document.DocumentLinkService.LinkRow;
import eu.socle.document.TransclusionGraphDtos.GraphResponse;
import eu.socle.space.ExternalReferenceNotify;
import eu.socle.space.ExternalReferencePolicy;
import eu.socle.storage.GitDocumentStore;
import eu.socle.storage.TipTapMarkdown;
import eu.socle.user.UserEntity;
import eu.socle.user.UserSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.oauth2.jwt.Jwt;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Mode Git : transclusions persistées dans le blob Markdown, visibles par
 * {@link TransclusionResolver} et {@link TransclusionGraphService}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GitTransclusionPersistenceTest {

    static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID SPACE = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static final UUID DOC_A = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    static final UUID DOC_B = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");

    @TempDir Path tempDir;

    @Mock DocumentRepository documentRepository;
    @Mock DocumentLinkService documentLinkService;
    @Mock AuthorizationService authorizationService;
    @Mock UserSyncService userSyncService;
    @Mock ExternalReferencePolicy externalReferencePolicy;
    @Mock ExternalReferenceNotify externalReferenceNotify;

    GitDocumentStore gitStore;
    TransclusionResolver resolver;
    TransclusionGraphService graphService;

    @BeforeEach
    void setUp() {
        gitStore = new GitDocumentStore(mock(DocumentVersionRepository.class), tempDir.resolve("repo"));
        when(externalReferencePolicy.allowsInterWorkspaceEdge(any(), any())).thenReturn(true);
        resolver = new TransclusionResolver(
                documentRepository, gitStore, authorizationService,
                externalReferencePolicy, externalReferenceNotify);
        graphService = new TransclusionGraphService(
                documentRepository, documentLinkService, resolver, authorizationService, userSyncService);

        UserEntity user = new UserEntity();
        user.setId(USER);
        when(userSyncService.syncFromJwt(any())).thenReturn(user);
        doNothing().when(authorizationService).requireSpaceRelation(USER, SPACE, "viewer");
        when(authorizationService.hasRelation(USER, "document", DOC_B, "viewer")).thenReturn(true);
        when(authorizationService.listViewableDocumentIds(eq(USER), any())).thenReturn(List.of(DOC_A, DOC_B));
        when(documentLinkService.incomingToSpace(SPACE)).thenReturn(List.of());
    }

    @Test
    void gitMode_createATranscludingB_resolverAndGraphSeeEdge() {
        Map<String, Object> bodyB = tipTap("Contenu de B");
        gitStore.createContent(DOC_B, bodyB, USER);

        Map<String, Object> bodyA = composite(DOC_B);
        gitStore.createContent(DOC_A, bodyA, USER);

        DocumentEntity entB = entity(DOC_B, "Document B", bodyB);
        DocumentEntity entA = entity(DOC_A, "Document A", tipTap("projection obsolete sans tx"));
        when(documentRepository.findActiveById(DOC_B)).thenReturn(Optional.of(entB));
        when(documentRepository.findActiveById(DOC_A)).thenReturn(Optional.of(entA));
        when(documentRepository.findActiveBySpaceId(SPACE)).thenReturn(List.of(entA, entB));

        Map<String, Object> fromGit = gitStore.readCurrentContent(DOC_A, entA.getBody());
        Map<String, Object> resolved = resolver.resolve(USER, DOC_A, fromGit);

        assertThat(String.valueOf(resolved)).contains("Contenu de B");
        @SuppressWarnings("unchecked")
        Map<String, Object> attrs = (Map<String, Object>) firstTransclusion(resolved).get("attrs");
        assertThat(attrs.get("accessible")).isEqualTo(true);
        assertThat(attrs.get("documentId")).isEqualTo(DOC_B.toString());

        // Index comme DocumentLinkService à l'écriture (JSON TipTap / blob Git lu)
        List<UUID> targets = resolver.extractDirectTargets(fromGit);
        when(documentLinkService.outgoingFromSpace(SPACE)).thenReturn(
                targets.stream().map(t -> new LinkRow(DOC_A, t, SPACE)).toList());

        GraphResponse g = graphService.graphForSpace(jwt(), SPACE);
        assertThat(g.edges()).hasSize(1);
        assertThat(g.edges().getFirst().sourceId()).isEqualTo(DOC_A);
        assertThat(g.edges().getFirst().targetId()).isEqualTo(DOC_B);
    }

    @Test
    void gitMode_transclusionInsideListAndBlockquote_visibleToResolverAndGraph() {
        Map<String, Object> bodyB = tipTap("Cible nested");
        gitStore.createContent(DOC_B, bodyB, USER);

        Map<String, Object> tx = new LinkedHashMap<>();
        tx.put("type", TransclusionResolver.NODE_TYPE);
        tx.put("attrs", Map.of(TransclusionResolver.ATTR_DOCUMENT_ID, DOC_B.toString()));

        Map<String, Object> listItem = new LinkedHashMap<>();
        listItem.put("type", "listItem");
        listItem.put("content", List.of(tx));

        Map<String, Object> list = new LinkedHashMap<>();
        list.put("type", "bulletList");
        list.put("content", List.of(listItem));

        Map<String, Object> quote = new LinkedHashMap<>();
        quote.put("type", "blockquote");
        quote.put("content", List.of(Map.of(
                "type", "transclusion",
                "attrs", Map.of("documentId", DOC_B.toString())
        )));

        Map<String, Object> bodyA = new LinkedHashMap<>();
        bodyA.put("type", "doc");
        bodyA.put("content", List.of(list, quote));
        gitStore.createContent(DOC_A, bodyA, USER);

        DocumentEntity entB = entity(DOC_B, "B", bodyB);
        DocumentEntity entA = entity(DOC_A, "A nested", tipTap("stale"));
        when(documentRepository.findActiveById(DOC_B)).thenReturn(Optional.of(entB));
        when(documentRepository.findActiveById(DOC_A)).thenReturn(Optional.of(entA));
        when(documentRepository.findActiveBySpaceId(SPACE)).thenReturn(List.of(entA, entB));

        Map<String, Object> fromGit = gitStore.readCurrentContent(DOC_A, entA.getBody());
        assertThat(TipTapMarkdown.normalize(fromGit)).isEqualTo(TipTapMarkdown.normalize(bodyA));

        Map<String, Object> resolved = resolver.resolve(USER, DOC_A, fromGit);
        assertThat(String.valueOf(resolved)).contains("Cible nested");
        // Deux occurrences (liste + citation) → extract déduplique ; index idem
        assertThat(resolver.extractDirectTargets(fromGit)).containsExactly(DOC_B);
        when(documentLinkService.outgoingFromSpace(SPACE)).thenReturn(List.of(
                new LinkRow(DOC_A, DOC_B, SPACE)));

        GraphResponse g = graphService.graphForSpace(jwt(), SPACE);
        assertThat(g.edges()).hasSize(1);
        assertThat(g.edges().getFirst().sourceId()).isEqualTo(DOC_A);
        assertThat(g.edges().getFirst().targetId()).isEqualTo(DOC_B);
    }

    private static DocumentEntity entity(UUID id, String title, Map<String, Object> body) {
        DocumentEntity e = new DocumentEntity();
        e.setId(id);
        e.setSpaceId(SPACE);
        e.setTitle(title);
        e.setBody(body);
        e.setStatus("brouillon");
        return e;
    }

    private static Map<String, Object> tipTap(String text) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("type", "doc");
        doc.put("content", List.of(Map.of(
                "type", "paragraph",
                "content", List.of(Map.of("type", "text", "text", text))
        )));
        return doc;
    }

    private static Map<String, Object> composite(UUID targetId) {
        Map<String, Object> tx = new LinkedHashMap<>();
        tx.put("type", TransclusionResolver.NODE_TYPE);
        tx.put("attrs", Map.of(TransclusionResolver.ATTR_DOCUMENT_ID, targetId.toString()));
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("type", "doc");
        doc.put("content", List.of(
                Map.of("type", "paragraph", "content", List.of(Map.of("type", "text", "text", "Avant"))),
                tx,
                Map.of("type", "paragraph", "content", List.of(Map.of("type", "text", "text", "Après")))
        ));
        return doc;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstTransclusion(Map<String, Object> doc) {
        List<Map<String, Object>> content = (List<Map<String, Object>>) doc.get("content");
        for (Map<String, Object> n : content) {
            if (TransclusionResolver.NODE_TYPE.equals(n.get("type"))) {
                return n;
            }
            if (n.get("content") instanceof List<?> children) {
                for (Object c : children) {
                    if (c instanceof Map<?, ?> m && TransclusionResolver.NODE_TYPE.equals(m.get("type"))) {
                        return (Map<String, Object>) m;
                    }
                }
            }
        }
        throw new AssertionError("transclusion absente: " + doc);
    }

    private static Jwt jwt() {
        return Jwt.withTokenValue("t")
                .header("alg", "none")
                .subject(USER.toString())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
    }
}
