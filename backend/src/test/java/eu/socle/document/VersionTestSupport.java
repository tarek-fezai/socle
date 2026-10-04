// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.document;

import eu.socle.audit.AuditService;
import eu.socle.authz.AuthorizationService;
import eu.socle.storage.DocumentStore;
import eu.socle.storage.GitDocumentStore;
import eu.socle.storage.RelationalDocumentStore;
import eu.socle.trash.TrashService;
import eu.socle.user.UserEntity;
import eu.socle.user.UserSyncService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Aides de tests Historique : repository de versions en mémoire, stores relational / git, corps TipTap. */
final class VersionTestSupport {

    static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID SPACE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID DOC = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    private VersionTestSupport() {}

    /** {@link DocumentVersionRepository} adossé à une liste (aucune base). */
    static final class InMemoryVersions {
        final List<DocumentVersionEntity> rows = new ArrayList<>();
        final DocumentVersionRepository repository = mock(DocumentVersionRepository.class);

        InMemoryVersions() {
            when(repository.save(any())).thenAnswer(inv -> {
                DocumentVersionEntity e = inv.getArgument(0);
                if (e.getCreatedAt() == null) {
                    e.setCreatedAt(Instant.now());
                }
                rows.add(e);
                return e;
            });
            when(repository.findByDocumentIdAndVersionNo(any(), anyInt())).thenAnswer(inv -> {
                UUID doc = inv.getArgument(0);
                int no = inv.getArgument(1);
                return rows.stream()
                        .filter(r -> r.getDocumentId().equals(doc) && r.getVersionNo() == no)
                        .findFirst();
            });
            when(repository.findByDocumentIdOrderByVersionNoDesc(any(), any())).thenAnswer(inv -> {
                UUID doc = inv.getArgument(0);
                Pageable pageable = inv.getArgument(1);
                List<DocumentVersionEntity> all = rows.stream()
                        .filter(r -> r.getDocumentId().equals(doc))
                        .sorted((a, b) -> Integer.compare(b.getVersionNo(), a.getVersionNo()))
                        .toList();
                int from = (int) Math.min(pageable.getOffset(), all.size());
                int to = Math.min(from + pageable.getPageSize(), all.size());
                return (Page<DocumentVersionEntity>) new PageImpl<>(all.subList(from, to), pageable, all.size());
            });
            when(repository.countByDocumentId(any())).thenAnswer(inv -> {
                UUID doc = inv.getArgument(0);
                return rows.stream().filter(r -> r.getDocumentId().equals(doc)).count();
            });
        }
    }

    static DocumentStore store(String provider, InMemoryVersions versions, Path tempDir) {
        return "git".equals(provider)
                ? new GitDocumentStore(versions.repository, tempDir.resolve("repo"))
                : new RelationalDocumentStore(versions.repository);
    }

    /**
     * Rejoue un historique : {@code bodies.get(0)} = v1, … ; le dernier est le contenu courant.
     * Sémantique correcte : archive(N) reçoit le résumé de N ; courant = résumé de N+1.
     */
    static DocumentEntity replay(DocumentStore store, List<Map<String, Object>> bodies, UUID author) {
        store.createContent(DOC, bodies.getFirst(), author);
        String currentSummary = null;
        for (int v = 1; v < bodies.size(); v++) {
            store.archiveVersion(DOC, v, bodies.get(v - 1), author, author, currentSummary);
            currentSummary = "v" + (v + 1);
            store.writeCurrentContent(DOC, bodies.get(v), author, author, currentSummary, null);
        }
        DocumentEntity d = new DocumentEntity();
        d.setId(DOC);
        d.setSpaceId(SPACE);
        d.setTitle("Doc");
        d.setBody(new HashMap<>(bodies.getLast()));
        d.setStatus("brouillon");
        d.setCurrentVersionNo(bodies.size());
        d.setCurrentChangeSummary(currentSummary);
        d.setCreatedBy(author);
        d.setUpdatedBy(author);
        return d;
    }

    static Map<String, Object> docOf(List<Map<String, Object>> blocks) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("type", "doc");
        d.put("content", new ArrayList<>(blocks));
        return d;
    }

    /** Corps TipTap minimal valide (paragraphe texte) — pour create/update/draft/restore. */
    static Map<String, Object> doc(String text) {
        return docOf(List.of(p(text == null ? "" : text)));
    }

    static Map<String, Object> p(String text) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("type", "paragraph");
        n.put("content", List.of(text(text)));
        return n;
    }

    static Map<String, Object> h(int level, String text) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("type", "heading");
        n.put("attrs", Map.of("level", level));
        n.put("content", List.of(text(text)));
        return n;
    }

    static Map<String, Object> transclusion(UUID target) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("type", "transclusion");
        n.put("attrs", Map.of("documentId", target.toString()));
        return n;
    }

    private static Map<String, Object> text(String t) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("type", "text");
        n.put("text", t);
        return n;
    }

    static UserEntity user(UUID id, String name) {
        UserEntity u = new UserEntity();
        u.setId(id);
        u.setEmail(name.toLowerCase() + "@example.com");
        u.setDisplayName(name);
        u.setStatus("active");
        return u;
    }

    static Jwt jwt() {
        return Jwt.withTokenValue("t")
                .header("alg", "none")
                .subject(USER.toString())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
    }

    /** Service avec store fourni, repository de documents simulé ({@code entity} actif). */
    static DocumentService service(
            DocumentStore store,
            DocumentRepository documents,
            UserSyncService userSync,
            AuthorizationService authz,
            JdbcTemplate jdbc
    ) {
        return new DocumentService(
                documents, userSync, authz, mock(AuditService.class),
                mock(ReliabilityScoreService.class), mock(TrashService.class),
                store, null, null, jdbc, null);
    }
}
