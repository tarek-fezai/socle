// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.storage;

import eu.socle.document.DocumentVersionRepository;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Purge réelle de l'historique Git : après {@link GitDocumentStore#purgeDocumentsHistory} le contenu
 * du document n'est retrouvable ni par {@code git log -p}, ni {@code git grep}, ni dans aucun objet
 * (y compris pendouillant), alors que les autres documents restent intacts.
 */
class GitHistoryPurgeTest {

    static final UUID AUTHOR = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID DOC_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static final UUID DOC_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    static final String SECRET = "SECRET-ALPHA-9f3c1e";
    static final String OTHER = "KEEP-BRAVO-77d2aa";

    @TempDir Path tmp;

    Path repo;
    GitDocumentStore store;

    @BeforeEach
    void setUp() {
        DocumentVersionRepository versions = mock(DocumentVersionRepository.class);
        when(versions.save(any())).thenAnswer(inv -> inv.getArgument(0));
        repo = tmp.resolve("repo");
        store = new GitDocumentStore(versions, repo);
        // A : 3 révisions contenant le secret ; B : 2 révisions intercalées.
        store.createContent(DOC_A, body(SECRET + " v1"), AUTHOR);
        store.createContent(DOC_B, body(OTHER + " v1"), AUTHOR);
        store.archiveVersion(DOC_A, 1, body(SECRET + " v1"), AUTHOR, AUTHOR, null);
        store.writeCurrentContent(DOC_A, body(SECRET + " v2"), AUTHOR, AUTHOR, "v2", null);
        store.archiveVersion(DOC_B, 1, body(OTHER + " v1"), AUTHOR, AUTHOR, null);
        store.writeCurrentContent(DOC_B, body(OTHER + " v2"), AUTHOR, AUTHOR, "b2", null);
        store.archiveVersion(DOC_A, 2, body(SECRET + " v2"), AUTHOR, AUTHOR, "v2");
        store.writeCurrentContent(DOC_A, body(SECRET + " v3"), AUTHOR, AUTHOR, "v3", null);
    }

    @AfterEach
    void tearDown() {
        store.close(); // libère le verrou d'instance (nettoyage @TempDir sous Windows)
        new org.eclipse.jgit.storage.file.WindowCacheConfig().install(); // libère les packs mmap
    }

    @Test
    void secretIsPresentBeforePurge() throws Exception {
        assertThat(allObjectsContaining(SECRET)).isNotEmpty();
        assertThat(pathExistsInAnyRevision("documents/" + DOC_A + ".md")).isTrue();
    }

    @Test
    void purgeRemovesDocumentFromEveryRevisionAndObjectStore() throws Exception {
        String oldHead = head();

        var result = store.purgeDocumentsHistory(Set.of(DOC_A)).orElseThrow();

        assertThat(result.documentIds()).containsExactly(DOC_A);
        assertThat(result.oldHead()).isEqualTo(oldHead);
        assertThat(result.newHead()).isEqualTo(head()).isNotEqualTo(oldHead);
        assertThat(result.commitsRewritten()).isPositive();
        assertThat(result.commitMapping()).containsKey(oldHead);

        // Chemin absent de toutes les révisions, de tous les refs.
        assertThat(pathExistsInAnyRevision("documents/" + DOC_A + ".md")).isFalse();
        // Aucun objet (loose ou pack, joignable ou non) ne contient le secret.
        assertThat(allObjectsContaining(SECRET)).isEmpty();
        // Fichier de travail supprimé.
        assertThat(repo.resolve("documents").resolve(DOC_A + ".md")).doesNotExist();
        // Sauvegarde supprimée.
        assertThat(backupRefs()).isEmpty();
        // L'autre document reste intact (courant + version archivée).
        assertThat(store.readCurrentContent(DOC_B, null).toString()).contains(OTHER + " v2");
        assertThat(allObjectsContaining(OTHER)).isNotEmpty();
        // SHA remappé : l'ancien HEAD n'existe plus.
        assertThat(objectExists(oldHead)).isFalse();
    }

    @Test
    void purgeIsInvisibleToGitCli() throws Exception {
        assumeThat(gitCliAvailable()).as("binaire git").isTrue();
        store.purgeDocumentsHistory(Set.of(DOC_A));

        assertThat(git("log", "-p", "--all", "--reflog")).doesNotContain(SECRET).doesNotContain(DOC_A.toString());
        assertThat(git("rev-list", "--all", "--objects")).doesNotContain(DOC_A.toString());
        String revs = git("rev-list", "--all").replace('\n', ' ').trim();
        if (!revs.isEmpty()) {
            assertThat(git(concat("grep", "-I", SECRET, revs.split(" ")), true)).isEmpty();
        }
        // Objets pendouillants : rien ne doit rester.
        String unreachable = git("fsck", "--unreachable", "--no-reflogs", "--no-progress");
        assertThat(unreachable).doesNotContain("blob").doesNotContain("commit");
        assertThat(git("fsck", "--full", "--no-progress")).doesNotContain("error").doesNotContain("missing");
    }

    @Test
    void purgeWithNoOtherDocumentEmptiesRepoWithoutBreakingStore() throws Exception {
        store.purgeDocumentsHistory(Set.of(DOC_A, DOC_B));

        assertThat(pathExistsInAnyRevision("documents/" + DOC_A + ".md")).isFalse();
        assertThat(pathExistsInAnyRevision("documents/" + DOC_B + ".md")).isFalse();
        assertThat(allObjectsContaining(SECRET)).isEmpty();
        assertThat(allObjectsContaining(OTHER)).isEmpty();
        // Le dépôt reste utilisable.
        UUID next = UUID.randomUUID();
        assertThat(store.createContent(next, body("after purge"), AUTHOR)).isNotBlank();
        assertThat(store.readCurrentContent(next, null).toString()).contains("after purge");
    }

    @Test
    void purgeIsIdempotentForUnknownDocument() {
        String before = head();
        var result = store.purgeDocumentsHistory(Set.of(UUID.randomUUID())).orElseThrow();
        assertThat(result.commitsRewritten()).isZero();
        assertThat(head()).isEqualTo(before);
    }

    @Test
    void commitsAfterPurgeWork() {
        store.purgeDocumentsHistory(Set.of(DOC_A));
        String sha = store.writeCurrentContent(DOC_B, body(OTHER + " v3"), AUTHOR, AUTHOR, "b3", null);
        assertThat(sha).isEqualTo(head());
        assertThat(store.readCurrentContent(DOC_B, null).toString()).contains(OTHER + " v3");
    }

    // ── Aides ───────────────────────────────────────────────────────────────

    static Map<String, Object> body(String text) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("type", "text");
        t.put("text", text);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "paragraph");
        p.put("content", List.of(t));
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("type", "doc");
        d.put("content", List.of(p));
        return d;
    }

    String head() {
        try (Git git = Git.open(repo.toFile())) {
            ObjectId id = git.getRepository().resolve("HEAD");
            return id == null ? null : id.name();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    boolean objectExists(String sha) throws IOException {
        try (Git git = Git.open(repo.toFile())) {
            return git.getRepository().getObjectDatabase().has(ObjectId.fromString(sha));
        }
    }

    List<String> backupRefs() throws IOException {
        try (Git git = Git.open(repo.toFile())) {
            return git.getRepository().getRefDatabase().getRefsByPrefix("refs/backup/").stream()
                    .map(Ref::getName).toList();
        }
    }

    boolean pathExistsInAnyRevision(String path) throws Exception {
        try (Git git = Git.open(repo.toFile())) {
            Repository r = git.getRepository();
            try (RevWalk walk = new RevWalk(r)) {
                for (Ref ref : r.getRefDatabase().getRefs()) {
                    if (ref.getObjectId() == null) {
                        continue;
                    }
                    walk.markStart(walk.parseCommit(ref.getObjectId()));
                }
                for (RevCommit c : walk) {
                    try (TreeWalk tw = TreeWalk.forPath(r, path, c.getTree())) {
                        if (tw != null) {
                            return true;
                        }
                    }
                }
            } catch (org.eclipse.jgit.errors.IncorrectObjectTypeException ignore) {
                // refs non-commit
            }
        }
        return false;
    }

    /** Parcourt TOUS les objets du dépôt (loose + packs, joignables ou non) à la recherche d'un motif. */
    List<String> allObjectsContaining(String needle) throws IOException {
        List<String> hits = new ArrayList<>();
        byte[] pattern = needle.getBytes(StandardCharsets.UTF_8);
        try (Git git = Git.open(repo.toFile())) {
            Repository r = git.getRepository();
            var db = (org.eclipse.jgit.internal.storage.file.ObjectDirectory) r.getObjectDatabase();
            List<ObjectId> ids = new ArrayList<>();
            Path objects = r.getDirectory().toPath().resolve("objects");
            try (Stream<Path> dirs = Files.list(objects)) {
                for (Path d : (Iterable<Path>) dirs.filter(p -> p.getFileName().toString().length() == 2
                        && Files.isDirectory(p))::iterator) {
                    try (Stream<Path> files = Files.list(d)) {
                        for (Path f : (Iterable<Path>) files::iterator) {
                            ids.add(ObjectId.fromString(d.getFileName() + f.getFileName().toString()));
                        }
                    }
                }
            }
            for (var pack : db.getPacks()) {
                for (var e : pack) {
                    ids.add(e.toObjectId());
                }
            }
            for (ObjectId id : ids) {
                byte[] data = r.open(id).getBytes();
                if (indexOf(data, pattern) >= 0) {
                    hits.add(id.name());
                }
            }
        }
        return hits;
    }

    static int indexOf(byte[] data, byte[] pat) {
        outer:
        for (int i = 0; i <= data.length - pat.length; i++) {
            for (int j = 0; j < pat.length; j++) {
                if (data[i + j] != pat[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    boolean gitCliAvailable() {
        try {
            Process p = new ProcessBuilder("git", "--version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    static String[] concat(String a, String b, String c, String[] rest) {
        List<String> all = new ArrayList<>(List.of(a, b, c));
        all.addAll(List.of(rest));
        return all.toArray(String[]::new);
    }

    String git(String... args) throws Exception {
        return git(args, false);
    }

    String git(String[] args, boolean allowFailure) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("git", "-C", repo.toString()));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor(60, TimeUnit.SECONDS);
        if (!allowFailure && p.exitValue() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " → " + out);
        }
        return out;
    }
}
