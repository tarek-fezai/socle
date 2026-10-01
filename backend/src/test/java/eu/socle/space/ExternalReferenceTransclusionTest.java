// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.space;

import eu.socle.authz.AuthorizationService;
import eu.socle.authz.DocumentScope;
import eu.socle.document.DocumentEntity;
import eu.socle.document.DocumentLinkService;
import eu.socle.document.DocumentLinkService.LinkRow;
import eu.socle.document.DocumentRepository;
import eu.socle.document.DocumentVersionRepository;
import eu.socle.document.TransclusionGraphDtos.GraphResponse;
import eu.socle.document.TransclusionGraphService;
import eu.socle.document.TransclusionResolver;
import eu.socle.storage.RelationalDocumentStore;
import eu.socle.user.UserEntity;
import eu.socle.user.UserSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * external_reference : open/restricted, rupture rétroactive, notif par paire, graphe aligné.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ExternalReferenceTransclusionTest {

    static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID SPACE_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static final UUID SPACE_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    static final UUID DOC_A = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    static final UUID DOC_A2 = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
    static final UUID DOC_B = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");
    static final UUID DOC_INTRA = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");

    @Mock DocumentRepository documentRepository;
    @Mock AuthorizationService authorizationService;
    @Mock UserSyncService userSyncService;
    @Mock DocumentLinkService documentLinkService;

    AtomicBoolean targetOpen = new AtomicBoolean(true);
    Set<String> notifiedPairs = new HashSet<>();
    List<String> notifyCalls = new ArrayList<>();

    ExternalReferencePolicy policy;
    TransclusionResolver resolver;
    TransclusionGraphService graphService;

    @BeforeEach
    void setUp() {
        notifiedPairs.clear();
        notifyCalls.clear();
        targetOpen.set(true);

        policy = new ExternalReferencePolicy(null) {
            @Override
            public String readMode(UUID spaceId) {
                if (SPACE_A.equals(spaceId)) {
                    return targetOpen.get() ? OPEN : RESTRICTED;
                }
                return OPEN;
            }
        };

        ExternalReferenceNotify pairOnce = (source, target) -> {
            String key = source + "->" + target;
            if (notifiedPairs.add(key)) {
                notifyCalls.add(key);
            }
        };

        resolver = new TransclusionResolver(
                documentRepository,
                new RelationalDocumentStore(mock(DocumentVersionRepository.class)),
                authorizationService,
                policy,
                pairOnce);
        graphService = new TransclusionGraphService(
                documentRepository,
                documentLinkService,
                resolver,
                authorizationService,
                userSyncService);

        UserEntity user = new UserEntity();
        user.setId(USER);
        when(userSyncService.syncFromJwt(any())).thenReturn(user);
        doNothing().when(authorizationService).requireSpaceRelation(any(), any(), any());
        when(authorizationService.hasRelation(eq(USER), eq("document"), any(), eq("viewer")))
                .thenReturn(true);
    }

    @Test
    void interOpen_resolvesContent_notificationDeferredToDocumentLinks() {
        DocumentEntity srcB = doc(DOC_B, SPACE_B, "Composite B", composite(DOC_A));
        DocumentEntity tgtA = doc(DOC_A, SPACE_A, "Cible A", tipTap("contenu A"));
        when(documentRepository.findActiveById(DOC_B)).thenReturn(Optional.of(srcB));
        when(documentRepository.findActiveById(DOC_A)).thenReturn(Optional.of(tgtA));

        Map<String, Object> first = resolver.resolve(USER, DOC_B, srcB.getBody());
        assertThat(String.valueOf(first)).contains("contenu A");
        // Notification 1ʳᵉ paire : DocumentLinkService à l'écriture, plus à la lecture
        assertThat(notifyCalls).isEmpty();
    }

    @Test
    void restrictAfterSuccessfulInter_retroactiveBreak_noLeak() {
        DocumentEntity srcB = doc(DOC_B, SPACE_B, "Composite B", composite(DOC_A));
        DocumentEntity tgtA = doc(DOC_A, SPACE_A, "Titre secret A", tipTap("corps encore visible"));
        when(documentRepository.findActiveById(DOC_B)).thenReturn(Optional.of(srcB));
        when(documentRepository.findActiveById(DOC_A)).thenReturn(Optional.of(tgtA));

        assertThat(String.valueOf(resolver.resolve(USER, DOC_B, srcB.getBody())))
                .contains("corps encore visible");

        targetOpen.set(false);

        Map<String, Object> after = resolver.resolve(USER, DOC_B, srcB.getBody());
        String dump = String.valueOf(after);
        assertThat(dump).doesNotContain("corps encore visible");
        assertThat(dump).doesNotContain("Titre secret A");
        assertThat(dump).doesNotContain(DOC_A.toString());
        @SuppressWarnings("unchecked")
        Map<String, Object> node = (Map<String, Object>) ((List<?>) after.get("content")).get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> a = (Map<String, Object>) node.get("attrs");
        assertThat(a.get("accessible")).isEqualTo(false);
        assertThat(a.get("deniedReason")).isEqualTo(TransclusionResolver.DENIED_FORBIDDEN);
    }

    @Test
    void restrictHasNoEffectOnIntraWorkspace() {
        targetOpen.set(false);
        DocumentEntity src = doc(DOC_A, SPACE_A, "Composite A", composite(DOC_INTRA));
        DocumentEntity intra = doc(DOC_INTRA, SPACE_A, "Intra", tipTap("toujours OK"));
        when(documentRepository.findActiveById(DOC_A)).thenReturn(Optional.of(src));
        when(documentRepository.findActiveById(DOC_INTRA)).thenReturn(Optional.of(intra));

        Map<String, Object> resolved = resolver.resolve(USER, DOC_A, src.getBody());
        assertThat(String.valueOf(resolved)).contains("toujours OK");
        assertThat(notifyCalls).isEmpty();
    }

    @Test
    void graphDropsInterEdgeWhenTargetRestricted() {
        DocumentEntity srcB = doc(DOC_B, SPACE_B, "Composite B", composite(DOC_A));
        DocumentEntity tgtA = doc(DOC_A, SPACE_A, "Cible", tipTap("x"));
        when(authorizationService.listViewableDocumentIds(eq(USER), any())).thenAnswer(inv -> {
            DocumentScope scope = inv.getArgument(1);
            if (scope.spaceId() != null) {
                return List.of(DOC_B);
            }
            return List.of(DOC_A);
        });
        when(documentRepository.findActiveBySpaceId(SPACE_B)).thenReturn(List.of(srcB));
        when(documentRepository.findActiveById(DOC_A)).thenReturn(Optional.of(tgtA));
        when(documentRepository.findAllActiveByIdIn(any())).thenReturn(List.of(tgtA));
        when(documentLinkService.outgoingFromSpace(SPACE_B)).thenReturn(List.of(
                new LinkRow(DOC_B, DOC_A, SPACE_B)));
        when(documentLinkService.incomingToSpace(SPACE_B)).thenReturn(List.of());

        targetOpen.set(true);
        GraphResponse openGraph = graphService.graphForSpace(jwt(), SPACE_B);
        assertThat(openGraph.edges()).hasSize(1);

        targetOpen.set(false);
        GraphResponse closed = graphService.graphForSpace(jwt(), SPACE_B);
        assertThat(closed.edges()).isEmpty();
        assertThat(String.valueOf(closed)).doesNotContain(DOC_A.toString());
    }

    private static Jwt jwt() {
        return Jwt.withTokenValue("t").header("alg", "none").subject(USER.toString())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
    }

    private static DocumentEntity doc(UUID id, UUID spaceId, String title, Map<String, Object> body) {
        DocumentEntity e = new DocumentEntity();
        e.setId(id);
        e.setSpaceId(spaceId);
        e.setTitle(title);
        e.setBody(body);
        e.setStatus("brouillon");
        e.setCurrentVersionNo(1);
        return e;
    }

    private static Map<String, Object> tipTap(String text) {
        Map<String, Object> textNode = new HashMap<>();
        textNode.put("type", "text");
        textNode.put("text", text);
        Map<String, Object> para = new HashMap<>();
        para.put("type", "paragraph");
        para.put("content", List.of(textNode));
        Map<String, Object> doc = new HashMap<>();
        doc.put("type", "doc");
        doc.put("content", List.of(para));
        return doc;
    }

    private static Map<String, Object> composite(UUID targetId) {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("documentId", targetId.toString());
        Map<String, Object> node = new HashMap<>();
        node.put("type", "transclusion");
        node.put("attrs", attrs);
        Map<String, Object> doc = new HashMap<>();
        doc.put("type", "doc");
        doc.put("content", List.of(node));
        return doc;
    }
}
