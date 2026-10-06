// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.document;

import eu.socle.authz.AuthorizationService;
import eu.socle.authz.DocumentScope;
import eu.socle.document.DocumentLinkService.LinkRow;
import eu.socle.document.TransclusionGraphDtos.GraphEdge;
import eu.socle.document.TransclusionGraphDtos.GraphResponse;
import eu.socle.space.ExternalReferenceNotify;
import eu.socle.space.ExternalReferencePolicy;
import eu.socle.storage.DocumentStore;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TransclusionGraphServiceTest {

    static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID SPACE_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static final UUID SPACE_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    static final UUID DOC_A1 = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    static final UUID DOC_B1 = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
    static final UUID DOC_SECRET = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");

    @Mock DocumentRepository documentRepository;
    @Mock DocumentLinkService documentLinkService;
    @Mock AuthorizationService authorizationService;
    @Mock UserSyncService userSyncService;

    AtomicBoolean targetOpen = new AtomicBoolean(true);
    TransclusionResolver resolver;
    TransclusionGraphService graphService;

    @BeforeEach
    void setUp() {
        DocumentStore documentStore = new RelationalDocumentStore(mock(DocumentVersionRepository.class));
        ExternalReferencePolicy policy = new ExternalReferencePolicy(null) {
            @Override
            public boolean allowsInterWorkspaceEdge(UUID sourceSpaceId, UUID targetSpaceId) {
                if (sourceSpaceId.equals(targetSpaceId)) {
                    return true;
                }
                return targetOpen.get();
            }

            @Override
            public String readMode(UUID spaceId) {
                return targetOpen.get() ? ExternalReferencePolicy.OPEN : ExternalReferencePolicy.RESTRICTED;
            }
        };
        ExternalReferenceNotify notify = (s, t) -> { };
        resolver = new TransclusionResolver(
                documentRepository, documentStore, authorizationService, policy, notify);
        graphService = new TransclusionGraphService(
                documentRepository, documentLinkService, resolver, authorizationService, userSyncService);

        UserEntity user = new UserEntity();
        user.setId(USER);
        when(userSyncService.syncFromJwt(any())).thenReturn(user);
        doNothing().when(authorizationService).requireSpaceRelation(USER, SPACE_A, "viewer");
    }

    @Test
    void incomingInterEdge_fromOtherSpace_shownWhenBothReadable() {
        DocumentEntity a1 = doc(DOC_A1, SPACE_A, "Page A", tipTap("a"));
        DocumentEntity b1 = doc(DOC_B1, SPACE_B, "Page B", tipTap("b"));
        when(documentRepository.findActiveBySpaceId(SPACE_A)).thenReturn(List.of(a1));
        when(documentRepository.findActiveById(DOC_B1)).thenReturn(Optional.of(b1));
        when(documentRepository.findAllActiveByIdIn(any())).thenReturn(List.of(b1));

        when(authorizationService.listViewableDocumentIds(eq(USER), any())).thenAnswer(inv -> {
            DocumentScope scope = inv.getArgument(1);
            if (scope.spaceId() != null) {
                return List.of(DOC_A1);
            }
            if (scope.ids() != null) {
                return scope.ids().stream().filter(DOC_B1::equals).toList();
            }
            return List.of();
        });
        when(documentLinkService.outgoingFromSpace(SPACE_A)).thenReturn(List.of());
        when(documentLinkService.incomingToSpace(SPACE_A)).thenReturn(List.of(
                new LinkRow(DOC_B1, DOC_A1, SPACE_B)));

        GraphResponse g = graphService.graphForSpace(jwt(), SPACE_A);

        assertThat(g.edges()).hasSize(1);
        GraphEdge e = g.edges().getFirst();
        assertThat(e.sourceId()).isEqualTo(DOC_B1);
        assertThat(e.targetId()).isEqualTo(DOC_A1);
        assertThat(e.kind()).isEqualTo(GraphEdge.INTER);
        assertThat(g.nodes()).extracting(n -> n.id()).contains(DOC_A1, DOC_B1);
    }

    @Test
    void incomingInterEdge_hiddenWhenSourceUnreadable() {
        DocumentEntity a1 = doc(DOC_A1, SPACE_A, "Page A", tipTap("a"));
        when(documentRepository.findActiveBySpaceId(SPACE_A)).thenReturn(List.of(a1));
        when(authorizationService.listViewableDocumentIds(eq(USER), any())).thenAnswer(inv -> {
            DocumentScope scope = inv.getArgument(1);
            if (scope.spaceId() != null) {
                return List.of(DOC_A1);
            }
            return List.of(); // b1 non lisible
        });
        when(documentLinkService.outgoingFromSpace(SPACE_A)).thenReturn(List.of());
        when(documentLinkService.incomingToSpace(SPACE_A)).thenReturn(List.of(
                new LinkRow(DOC_SECRET, DOC_A1, SPACE_B)));

        GraphResponse g = graphService.graphForSpace(jwt(), SPACE_A);

        assertThat(g.edges()).isEmpty();
        assertThat(String.valueOf(g)).doesNotContain(DOC_SECRET.toString());
    }

    @Test
    void incomingInterEdge_hiddenWhenTargetSpaceRestricted() {
        DocumentEntity a1 = doc(DOC_A1, SPACE_A, "Page A", tipTap("a"));
        DocumentEntity b1 = doc(DOC_B1, SPACE_B, "Page B", tipTap("b"));
        when(documentRepository.findActiveBySpaceId(SPACE_A)).thenReturn(List.of(a1));
        when(documentRepository.findActiveById(DOC_B1)).thenReturn(Optional.of(b1));
        when(documentRepository.findAllActiveByIdIn(any())).thenReturn(List.of(b1));
        when(authorizationService.listViewableDocumentIds(eq(USER), any())).thenAnswer(inv -> {
            DocumentScope scope = inv.getArgument(1);
            if (scope.spaceId() != null) {
                return List.of(DOC_A1);
            }
            return List.of(DOC_B1);
        });
        when(documentLinkService.outgoingFromSpace(SPACE_A)).thenReturn(List.of());
        when(documentLinkService.incomingToSpace(SPACE_A)).thenReturn(List.of(
                new LinkRow(DOC_B1, DOC_A1, SPACE_B)));

        targetOpen.set(false);
        GraphResponse g = graphService.graphForSpace(jwt(), SPACE_A);

        assertThat(g.edges()).isEmpty();
    }

    @Test
    void outgoingIntra_stillPresent() {
        UUID a2 = UUID.fromString("aaaaaaaa-1111-1111-1111-111111111111");
        DocumentEntity a1 = doc(DOC_A1, SPACE_A, "Src", tipTap("x"));
        DocumentEntity a2e = doc(a2, SPACE_A, "Tgt", tipTap("y"));
        when(documentRepository.findActiveBySpaceId(SPACE_A)).thenReturn(List.of(a1, a2e));
        when(authorizationService.listViewableDocumentIds(eq(USER), any()))
                .thenReturn(List.of(DOC_A1, a2));
        when(documentLinkService.outgoingFromSpace(SPACE_A)).thenReturn(List.of(
                new LinkRow(DOC_A1, a2, SPACE_A)));
        when(documentLinkService.incomingToSpace(SPACE_A)).thenReturn(List.of());

        GraphResponse g = graphService.graphForSpace(jwt(), SPACE_A);

        assertThat(g.edges()).hasSize(1);
        assertThat(g.edges().getFirst().kind()).isEqualTo(GraphEdge.INTRA);
    }

    private static DocumentEntity doc(UUID id, UUID spaceId, String title, Map<String, Object> body) {
        DocumentEntity d = new DocumentEntity();
        d.setId(id);
        d.setSpaceId(spaceId);
        d.setTitle(title);
        d.setBody(body);
        d.setStatus("valide");
        return d;
    }

    private static Map<String, Object> tipTap(String text) {
        Map<String, Object> p = new HashMap<>();
        p.put("type", "paragraph");
        p.put("content", List.of(Map.of("type", "text", "text", text)));
        return Map.of("type", "doc", "content", List.of(p));
    }

    private static Jwt jwt() {
        return Jwt.withTokenValue("t").header("alg", "none").subject(USER.toString())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
    }
}
