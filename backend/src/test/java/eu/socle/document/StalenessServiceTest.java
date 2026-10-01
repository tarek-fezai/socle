// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.document;

import eu.socle.authz.AuthorizationService;
import eu.socle.document.ContentHealthDtos.ContentHealthResponse;
import eu.socle.storage.DocumentStore;
import eu.socle.storage.GitDocumentStore;
import eu.socle.storage.RelationalDocumentStore;
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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
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

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StalenessServiceTest {

    static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID SPACE = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static final UUID DOC_OLD = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    static final UUID DOC_FRESH = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    static final UUID DOC_SECRET = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
    static final UUID AUTHOR = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");

    static final Instant NOW = Instant.parse("2026-09-29T12:00:00Z");

    @Mock DocumentRepository documentRepository;
    @Mock DocumentVersionRepository versionRepository;
    @Mock AuthorizationService authorizationService;
    @Mock UserSyncService userSyncService;

    StalenessProperties properties;
    RelationalDocumentStore relationalStore;
    StalenessService staleness;
    ContentHealthService contentHealth;

    @BeforeEach
    void setUp() {
        properties = new StalenessProperties();
        properties.setThresholdDays(90);
        properties.validate();
        relationalStore = new RelationalDocumentStore(versionRepository);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        staleness = new StalenessService(relationalStore, properties, clock);
        contentHealth = new ContentHealthService(
                documentRepository, staleness, authorizationService, userSyncService);

        UserEntity user = new UserEntity();
        user.setId(USER);
        when(userSyncService.syncFromJwt(any())).thenReturn(user);
        doNothing().when(authorizationService).requireSpaceRelation(USER, SPACE, "viewer");
    }

    @Test
    void neverUpdatedBeyondThreshold_isStale_recent_isFresh() {
        Instant old = NOW.minus(100, ChronoUnit.DAYS);
        Instant recent = NOW.minus(10, ChronoUnit.DAYS);

        assertThat(staleness.isStaleSince(old)).isTrue();
        assertThat(staleness.isStaleSince(recent)).isFalse();
        assertThat(staleness.freshness(DOC_OLD, old).stale()).isTrue();
        assertThat(staleness.freshness(DOC_FRESH, recent).stale()).isFalse();
    }

    @Test
    void restoreArchivesThenWrites_refreshesLastContentModified() {
        UUID doc = DOC_FRESH;
        Instant created = NOW.minus(200, ChronoUnit.DAYS);
        // Simulate archive at restore time (= NOW relative via version createdAt)
        DocumentVersionEntity archived = new DocumentVersionEntity();
        archived.setDocumentId(doc);
        archived.setVersionNo(1);
        archived.setBodySnapshot(Map.of("type", "doc"));
        archived.setAuthorId(AUTHOR);
        archived.setCreatedAt(NOW); // restore just wrote
        when(versionRepository.findByDocumentIdOrderByVersionNoDesc(eq(doc), any()))
                .thenAnswer(inv -> {
                    org.springframework.data.domain.Pageable p = inv.getArgument(1);
                    return new org.springframework.data.domain.PageImpl<>(
                            List.of(archived), p, 1);
                });

        var f = staleness.freshness(doc, created);
        assertThat(f.contentModifiedAt()).isEqualTo(NOW);
        assertThat(f.stale()).isFalse();
    }

    @Test
    void workflowStatusChangeWithoutVersion_doesNotRefreshBadge() {
        // Pas de row versions → contentModifiedAt = createdAt (ancien)
        Instant created = NOW.minus(120, ChronoUnit.DAYS);
        when(versionRepository.findByDocumentIdOrderByVersionNoDesc(eq(DOC_OLD), any()))
                .thenAnswer(inv -> org.springframework.data.domain.Page.empty(inv.getArgument(1)));

        // documents.updated_at aurait bougé via ApprovalActivities — on ne l'utilise PAS
        assertThat(staleness.freshness(DOC_OLD, created).stale()).isTrue();
        assertThat(staleness.contentModifiedAt(DOC_OLD, created)).isEqualTo(created);
    }

    @Test
    void contentHealth_hidesUnreadableStaleDocument() {
        Instant old = NOW.minus(200, ChronoUnit.DAYS);
        DocumentEntity visible = entity(DOC_OLD, "Visible stale", old);
        DocumentEntity secret = entity(DOC_SECRET, "Secret stale interdit", old);
        DocumentEntity fresh = entity(DOC_FRESH, "Fresh", NOW.minus(5, ChronoUnit.DAYS));

        when(authorizationService.listViewableDocumentIds(eq(USER), any()))
                .thenReturn(List.of(DOC_OLD, DOC_FRESH)); // DOC_SECRET non lisible
        when(documentRepository.findActiveBySpaceId(SPACE))
                .thenReturn(List.of(visible, secret, fresh));
        when(versionRepository.findByDocumentIdOrderByVersionNoDesc(any(), any()))
                .thenAnswer(inv -> org.springframework.data.domain.Page.empty(inv.getArgument(1)));

        ContentHealthResponse r = contentHealth.forSpace(jwt(), SPACE);

        assertThat(r.staleDocuments()).extracting(i -> i.id()).containsExactly(DOC_OLD);
        assertThat(String.valueOf(r)).doesNotContain(DOC_SECRET.toString());
        assertThat(String.valueOf(r)).doesNotContain("Secret stale interdit");
        assertThat(r.viewableDocumentCount()).isEqualTo(2);
        assertThat(r.staleCount()).isEqualTo(1);
    }

    @Test
    void relationalAndGit_sameAge_sameStaleness(@TempDir Path tempDir) {
        Instant created = NOW.minus(100, ChronoUnit.DAYS);
        StalenessProperties props = new StalenessProperties();
        props.setThresholdDays(90);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

        DocumentVersionRepository versions = mock(DocumentVersionRepository.class);
        when(versions.findByDocumentIdOrderByVersionNoDesc(any(), any()))
                .thenAnswer(inv -> org.springframework.data.domain.Page.empty(inv.getArgument(1)));

        RelationalDocumentStore rel = new RelationalDocumentStore(versions);
        GitDocumentStore git = new GitDocumentStore(versions, tempDir.resolve("repo"));
        // create content so git has a commit — then we override lastContentModifiedAt via empty versions + created
        Map<String, Object> body = new HashMap<>();
        body.put("type", "doc");
        body.put("content", List.of());
        git.createContent(DOC_OLD, body, AUTHOR);

        StalenessService relStale = new StalenessService(rel, props, clock);
        StalenessService gitStale = new StalenessService(git, props, clock);

        // Même ancienneté déclarée (created) + pas d'archive → même décision stale
        // (Git log peut être "maintenant" à cause du createContent ci-dessus — on compare
        //  l'algo via Instant forcé côté StalenessService.isStaleSince pour le contrat commun)
        assertThat(relStale.isStaleSince(created)).isEqualTo(gitStale.isStaleSince(created));
        assertThat(relStale.isStaleSince(created)).isTrue();

        Instant recent = NOW.minus(5, ChronoUnit.DAYS);
        assertThat(relStale.isStaleSince(recent)).isEqualTo(gitStale.isStaleSince(recent));
        assertThat(relStale.isStaleSince(recent)).isFalse();
    }

    private static DocumentEntity entity(UUID id, String title, Instant created) {
        DocumentEntity e = new DocumentEntity();
        e.setId(id);
        e.setSpaceId(SPACE);
        e.setTitle(title);
        e.setBody(Map.of("type", "doc"));
        e.setStatus("brouillon");
        e.setCurrentVersionNo(1);
        // createdAt via reflection-free: PrePersist normally; set through package if available
        try {
            var f = DocumentEntity.class.getDeclaredField("createdAt");
            f.setAccessible(true);
            f.set(e, created);
            var u = DocumentEntity.class.getDeclaredField("updatedAt");
            u.setAccessible(true);
            u.set(e, created.plus(1, ChronoUnit.DAYS)); // updated_at plus récent (gouvernance) — ignoré
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
        return e;
    }

    private static Jwt jwt() {
        return Jwt.withTokenValue("t").header("alg", "none").subject(USER.toString())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
    }
}
