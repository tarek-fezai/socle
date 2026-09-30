package eu.socle.document;

import eu.socle.authz.AuthorizationService;
import eu.socle.space.ExternalReferenceNotify;
import eu.socle.space.ExternalReferencePolicy;
import eu.socle.storage.DocumentStore;
import eu.socle.storage.GitDocumentStore;
import eu.socle.storage.RelationalDocumentStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TransclusionResolverTest {

    static final UUID READER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID PAGE = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static final UUID TARGET = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    static final UUID TARGET_B = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    @Mock DocumentRepository documentRepository;
    @Mock AuthorizationService authorizationService;
    @Mock ExternalReferencePolicy externalReferencePolicy;
    @Mock ExternalReferenceNotify externalReferenceNotify;

    DocumentStore documentStore;
    TransclusionResolver resolver;

    @BeforeEach
    void setUp() {
        documentStore = new RelationalDocumentStore(mock(DocumentVersionRepository.class));
        when(externalReferencePolicy.allowsInterWorkspaceEdge(any(), any())).thenReturn(true);
        resolver = new TransclusionResolver(
                documentRepository, documentStore, authorizationService,
                externalReferencePolicy, externalReferenceNotify);
        DocumentEntity page = entity(PAGE, "Page composite", tipTap("racine"));
        when(documentRepository.findActiveById(PAGE)).thenReturn(Optional.of(page));
    }

    @Test
    void accessibleTarget_resolvesTitleAndContent() {
        when(authorizationService.hasRelation(READER, "document", TARGET, "viewer")).thenReturn(true);
        DocumentEntity target = entity(TARGET, "Procédure cible", tipTap("Contenu secret autorisé"));
        when(documentRepository.findActiveById(TARGET)).thenReturn(Optional.of(target));

        Map<String, Object> resolved = resolver.resolve(READER, PAGE, compositeBody(TARGET));

        Map<String, Object> block = firstTransclusion(resolved);
        @SuppressWarnings("unchecked")
        Map<String, Object> attrs = (Map<String, Object>) block.get("attrs");
        assertThat(attrs.get("accessible")).isEqualTo(true);
        assertThat(attrs.get("title")).isEqualTo("Procédure cible");
        assertThat(attrs.get("documentId")).isEqualTo(TARGET.toString());
        assertThat(String.valueOf(block)).contains("Contenu secret autorisé");
    }

    @Test
    void forbiddenTarget_showsIndicatorWithoutTitleOrContentLeak() {
        when(authorizationService.hasRelation(READER, "document", TARGET, "viewer")).thenReturn(false);
        DocumentEntity target = entity(TARGET, "Titre confidentiel", tipTap("Corps interdit"));
        when(documentRepository.findActiveById(TARGET)).thenReturn(Optional.of(target));

        Map<String, Object> resolved = resolver.resolve(READER, PAGE, compositeBody(TARGET));

        String dump = String.valueOf(resolved);
        assertThat(dump).doesNotContain("Titre confidentiel");
        assertThat(dump).doesNotContain("Corps interdit");
        assertThat(dump).doesNotContain(TARGET.toString());

        Map<String, Object> block = firstTransclusion(resolved);
        @SuppressWarnings("unchecked")
        Map<String, Object> attrs = (Map<String, Object>) block.get("attrs");
        assertThat(attrs.get("accessible")).isEqualTo(false);
        assertThat(attrs.get("deniedReason")).isEqualTo(TransclusionResolver.DENIED_FORBIDDEN);
        assertThat(attrs).doesNotContainKey("title");
        assertThat(attrs).doesNotContainKey("documentId");
        assertThat(block).doesNotContainKey("content");
    }

    @Test
    void revokeAfterSuccessfulRead_secondResolveHidesContent_noCache() {
        AtomicBoolean allowed = new AtomicBoolean(true);
        when(authorizationService.hasRelation(READER, "document", TARGET, "viewer"))
                .thenAnswer(inv -> allowed.get());
        DocumentEntity target = entity(TARGET, "Cible", tipTap("Texte visible puis révoqué"));
        when(documentRepository.findActiveById(TARGET)).thenReturn(Optional.of(target));

        Map<String, Object> first = resolver.resolve(READER, PAGE, compositeBody(TARGET));
        assertThat(String.valueOf(first)).contains("Texte visible puis révoqué");

        allowed.set(false);
        Map<String, Object> second = resolver.resolve(READER, PAGE, compositeBody(TARGET));
        String dump = String.valueOf(second);
        assertThat(dump).doesNotContain("Texte visible puis révoqué");
        assertThat(dump).doesNotContain("Cible");
        @SuppressWarnings("unchecked")
        Map<String, Object> attrs = (Map<String, Object>) firstTransclusion(second).get("attrs");
        assertThat(attrs.get("deniedReason")).isEqualTo(TransclusionResolver.DENIED_FORBIDDEN);
    }

    @Test
    void cycleA_transcludesB_transcludesA_detectedWithoutInfiniteLoop() {
        when(authorizationService.hasRelation(eq(READER), eq("document"), any(), eq("viewer")))
                .thenReturn(true);

        DocumentEntity pageA = entity(PAGE, "A", compositeBody(TARGET_B));
        DocumentEntity pageB = entity(TARGET_B, "B", compositeBody(PAGE));
        when(documentRepository.findActiveById(PAGE)).thenReturn(Optional.of(pageA));
        when(documentRepository.findActiveById(TARGET_B)).thenReturn(Optional.of(pageB));

        Map<String, Object> resolved = resolver.resolve(READER, PAGE, pageA.getBody());

        String dump = String.valueOf(resolved);
        assertThat(dump).contains(TransclusionResolver.DENIED_CYCLE);
        // Pas de récursion infinie : la réponse est bornée
        assertThat(dump.length()).isLessThan(50_000);
    }

    @Test
    void gitBackedTarget_resolvedViaSameDocumentStoreAbstraction(@TempDir Path tempDir) {
        GitDocumentStore gitStore = new GitDocumentStore(
                mock(DocumentVersionRepository.class),
                tempDir.resolve("repo"));
        TransclusionResolver gitResolver =
                new TransclusionResolver(
                        documentRepository, gitStore, authorizationService,
                        externalReferencePolicy, externalReferenceNotify);

        when(authorizationService.hasRelation(READER, "document", TARGET, "viewer")).thenReturn(true);

        Map<String, Object> gitBody = tipTap("Contenu depuis Git");
        String head = gitStore.createContent(TARGET, gitBody, READER);
        DocumentEntity target = entity(TARGET, "Doc Git", Map.of("git_head_sha", head));
        when(documentRepository.findActiveById(TARGET)).thenReturn(Optional.of(target));

        Map<String, Object> resolved = gitResolver.resolve(READER, PAGE, compositeBody(TARGET));

        assertThat(String.valueOf(resolved)).contains("Contenu depuis Git");
        @SuppressWarnings("unchecked")
        Map<String, Object> attrs = (Map<String, Object>) firstTransclusion(resolved).get("attrs");
        assertThat(attrs.get("accessible")).isEqualTo(true);
        assertThat(attrs.get("title")).isEqualTo("Doc Git");
    }

    @Test
    void depthExceeded_stopsRecursion() {
        when(authorizationService.hasRelation(eq(READER), eq("document"), any(), eq("viewer")))
                .thenReturn(true);

        // Chaîne PAGE → d1 → d2 → … → d6 (profondeur > MAX_DEPTH)
        UUID[] chain = new UUID[TransclusionResolver.MAX_DEPTH + 2];
        chain[0] = PAGE;
        for (int i = 1; i < chain.length; i++) {
            chain[i] = UUID.randomUUID();
        }
        for (int i = 0; i < chain.length - 1; i++) {
            DocumentEntity e = entity(chain[i], "D" + i, compositeBody(chain[i + 1]));
            when(documentRepository.findActiveById(chain[i])).thenReturn(Optional.of(e));
        }
        DocumentEntity leaf = entity(chain[chain.length - 1], "Leaf", tipTap("trop profond"));
        when(documentRepository.findActiveById(chain[chain.length - 1])).thenReturn(Optional.of(leaf));

        Map<String, Object> resolved = resolver.resolve(READER, PAGE, compositeBody(chain[1]));
        assertThat(String.valueOf(resolved)).contains(TransclusionResolver.DENIED_DEPTH);
        assertThat(String.valueOf(resolved)).doesNotContain("trop profond");
    }

    private static DocumentEntity entity(UUID id, String title, Map<String, Object> body) {
        DocumentEntity e = new DocumentEntity();
        e.setId(id);
        e.setSpaceId(UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd"));
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

    private static Map<String, Object> compositeBody(UUID targetId) {
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstTransclusion(Map<String, Object> body) {
        List<Object> content = (List<Object>) body.get("content");
        return (Map<String, Object>) content.get(0);
    }
}
