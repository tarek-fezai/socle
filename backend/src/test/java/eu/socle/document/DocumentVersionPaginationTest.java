// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.document;

import eu.socle.authz.AuthorizationService;
import eu.socle.document.DocumentDtos.VersionPage;
import eu.socle.document.VersionTestSupport.InMemoryVersions;
import eu.socle.storage.DocumentStore;
import eu.socle.user.UserSyncService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static eu.socle.document.VersionTestSupport.DOC;
import static eu.socle.document.VersionTestSupport.USER;
import static eu.socle.document.VersionTestSupport.docOf;
import static eu.socle.document.VersionTestSupport.jwt;
import static eu.socle.document.VersionTestSupport.p;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pagination GET /versions : page 0 commence par la courante ; offset décalé de 1 ;
 * pas de doublon ni de trou. Cas 1, 20, 21 et 45 versions (limit=20).
 */
class DocumentVersionPaginationTest {

    static final UUID ALICE = UUID.fromString("aaaaaaaa-1111-1111-1111-111111111111");
    static final int LIMIT = 20;

    @TempDir Path tempDir;

    DocumentStore store;

    @AfterEach
    void closeStore() throws Exception {
        if (store instanceof AutoCloseable c) {
            c.close();
        }
    }

    @ParameterizedTest
    @CsvSource({
            "1, 1",
            "20, 1",
            "21, 2",
            "45, 3"
    })
    void pagination_noDuplicateNoGap(int totalVersions, int expectedPages) throws Exception {
        DocumentService service = setUp("relational", totalVersions);

        List<Integer> seen = new ArrayList<>();
        int offset = 0;
        int pages = 0;
        Long total = null;
        while (true) {
            VersionPage page = service.listVersions(jwt(), DOC, offset, LIMIT);
            if (total == null) {
                total = page.total();
            }
            assertThat(page.total()).isEqualTo(totalVersions);
            if (page.items().isEmpty()) {
                break;
            }
            pages++;
            for (var item : page.items()) {
                seen.add(item.versionNo());
            }
            if (offset == 0) {
                assertThat(page.items().getFirst().current()).isTrue();
                assertThat(page.items().getFirst().versionNo()).isEqualTo(totalVersions);
            } else {
                assertThat(page.items().stream().noneMatch(v -> v.current())).isTrue();
            }
            offset += page.items().size();
            if (seen.size() >= totalVersions) {
                break;
            }
        }

        assertThat(pages).isEqualTo(expectedPages);
        assertThat(seen).hasSize(totalVersions);
        assertThat(new HashSet<>(seen)).hasSize(totalVersions);
        assertThat(seen.getFirst()).isEqualTo(totalVersions);
        assertThat(seen.getLast()).isEqualTo(1);
        // Suite décroissante stricte, sans trou.
        for (int i = 0; i < seen.size(); i++) {
            assertThat(seen.get(i)).isEqualTo(totalVersions - i);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"relational", "git"})
    void page0_currentFirst_withSummaryAndContentAuthor(String provider) throws Exception {
        DocumentService service = setUp(provider, 3);
        VersionPage page = service.listVersions(jwt(), DOC, 0, LIMIT);
        assertThat(page.items().getFirst().current()).isTrue();
        assertThat(page.items().getFirst().versionNo()).isEqualTo(3);
        assertThat(page.items().getFirst().changeSummary()).isEqualTo("v3");
        assertThat(page.items().getFirst().authorId()).isEqualTo(ALICE);
        assertThat(page.total()).isEqualTo(3);
    }

    @SuppressWarnings("unchecked")
    private DocumentService setUp(String provider, int totalVersions) throws Exception {
        InMemoryVersions versions = new InMemoryVersions();
        store = VersionTestSupport.store(provider, versions, tempDir);
        List<Map<String, Object>> bodies = new ArrayList<>();
        for (int i = 1; i <= totalVersions; i++) {
            bodies.add(docOf(List.of(p("corps " + i))));
        }
        DocumentEntity entity = VersionTestSupport.replay(store, bodies, ALICE);

        DocumentRepository documents = mock(DocumentRepository.class);
        when(documents.findActiveById(DOC)).thenReturn(Optional.of(entity));
        UserSyncService userSync = mock(UserSyncService.class);
        when(userSync.syncFromJwt(any())).thenReturn(VersionTestSupport.user(USER, "Viewer"));
        AuthorizationService authz = mock(AuthorizationService.class);

        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(contains("FROM users WHERE id IN"), any(ResultSetExtractor.class), any(Object[].class)))
                .thenAnswer(inv -> {
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.next()).thenReturn(true, false);
                    when(rs.getObject("id")).thenReturn(ALICE);
                    when(rs.getString("display_name")).thenReturn("Alice Martin");
                    when(rs.getString("avatar_initials")).thenReturn(null);
                    when(rs.getString("status")).thenReturn("active");
                    return ((ResultSetExtractor<Object>) inv.getArgument(1)).extractData(rs);
                });
        return VersionTestSupport.service(store, documents, userSync, authz, jdbc);
    }
}
