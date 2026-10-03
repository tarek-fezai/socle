// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.storage;

import eu.socle.document.DocumentVersionEntity;
import eu.socle.document.DocumentVersionRepository;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Provider Git (JGit) : un commit = une version de contenu.
 * Projection JSON dans {@code document_versions.body_snapshot} + {@code documents.body}
 * (via DocumentService) pour Search FTS inchangé.
 */
public class GitDocumentStore implements DocumentStore, AutoCloseable {

    private final DocumentVersionRepository versionRepository;
    private final Path repoPath;
    private final Git git;
    private final GitRepositoryLock instanceLock;
    private final ConcurrentHashMap<UUID, Object> documentLocks = new ConcurrentHashMap<>();

    public GitDocumentStore(DocumentVersionRepository versionRepository, Path repoPath) {
        this.versionRepository = versionRepository;
        this.repoPath = repoPath;
        GitRepositoryLock acquired = null;
        try {
            Files.createDirectories(repoPath);
            Path gitDir = repoPath.resolve(".git");
            Repository repository;
            if (Files.exists(gitDir)) {
                repository = new FileRepositoryBuilder()
                        .setGitDir(gitDir.toFile())
                        .setWorkTree(repoPath.toFile())
                        .readEnvironment()
                        .build();
            } else {
                repository = FileRepositoryBuilder.create(gitDir.toFile());
                repository.create();
            }
            acquired = GitRepositoryLock.acquire(gitDir);
            this.git = new Git(repository);
            if (repository.resolve("HEAD") == null) {
                // Commit vide initial pour avoir une branche
                git.commit()
                        .setMessage("socle: init storage")
                        .setAllowEmpty(true)
                        .setAuthor(ident(null))
                        .setCommitter(ident(null))
                        .call();
            }
        } catch (IOException | GitAPIException e) {
            if (acquired != null) {
                acquired.close();
            }
            throw new IllegalStateException("Impossible d'initialiser le dépôt Git: " + repoPath, e);
        }
        this.instanceLock = acquired;
    }

    @Override
    public String createContent(UUID documentId, Map<String, Object> body, UUID authorId) {
        return createContent(documentId, body, authorId, null);
    }

    @Override
    public String createContent(UUID documentId, Map<String, Object> body, UUID authorId, String changeSummary) {
        String msg = changeSummary == null || changeSummary.isBlank()
                ? "create " + documentId
                : changeSummary;
        commitFile(documentId, body, authorId, authorId, msg, null);
        return resolveHeadSha();
    }

    @Override
    public Map<String, Object> readCurrentContent(UUID documentId, Map<String, Object> dbProjection) {
        try {
            String sha = resolveHeadSha();
            if (sha == null) {
                return fallbackProjection(dbProjection);
            }
            String md = readBlobAtCommit(sha, relativePath(documentId));
            if (md.isBlank()) {
                return fallbackProjection(dbProjection);
            }
            return TipTapMarkdown.fromMarkdown(md);
        } catch (IOException e) {
            return fallbackProjection(dbProjection);
        }
    }

    @Override
    public void archiveVersion(
            UUID documentId,
            int archivedVersionNo,
            Map<String, Object> previousBody,
            UUID contentAuthorId,
            UUID archivedBy,
            String changeSummary
    ) {
        // Le commit précédent (HEAD avant writeCurrent) est l'archive.
        // On enregistre la métadonnée API + SHA du HEAD actuel (encore l'ancien contenu).
        String sha = resolveHeadSha();
        DocumentVersionEntity version = new DocumentVersionEntity();
        version.setDocumentId(documentId);
        version.setVersionNo(archivedVersionNo);
        version.setBodySnapshot(previousBody == null ? Map.of() : new HashMap<>(previousBody));
        version.setAuthorId(contentAuthorId);
        version.setArchivedBy(archivedBy);
        version.setChangeSummary(changeSummary);
        version.setGitCommitSha(sha);
        versionRepository.save(version);
    }

    @Override
    public String writeCurrentContent(
            UUID documentId,
            Map<String, Object> body,
            UUID contentAuthorId,
            UUID committerId,
            String changeSummary,
            String expectedGitHeadSha
    ) {
        String msg = changeSummary == null || changeSummary.isBlank()
                ? "update " + documentId
                : changeSummary;
        commitFile(documentId, body, contentAuthorId, committerId, msg, expectedGitHeadSha);
        return resolveHeadSha();
    }

    @Override
    public Optional<StoredVersion> findVersion(UUID documentId, int versionNo) {
        return versionRepository.findByDocumentIdAndVersionNo(documentId, versionNo)
                .map(this::toStored);
    }

    @Override
    public Page<StoredVersion> listVersions(UUID documentId, int page, int size) {
        return listVersionsFromOffset(documentId, Math.multiplyExact(page, size), size);
    }

    @Override
    public Page<StoredVersion> listVersionsFromOffset(UUID documentId, int offset, int size) {
        OffsetPageRequest pageable = new OffsetPageRequest(Math.max(offset, 0), Math.max(size, 1));
        Page<DocumentVersionEntity> result =
                versionRepository.findByDocumentIdOrderByVersionNoDesc(documentId, pageable);
        if (result == null) {
            return Page.empty(pageable);
        }
        return result.map(this::toStored);
    }

    @Override
    public long countVersions(UUID documentId) {
        return versionRepository.countByDocumentId(documentId);
    }

    @Override
    public Optional<String> currentContentChangeSummary(UUID documentId) {
        try {
            var commits = git.log()
                    .addPath(relativePath(documentId))
                    .setMaxCount(1)
                    .call();
            for (RevCommit c : commits) {
                String msg = c.getFullMessage();
                if (msg != null && !msg.isBlank()) {
                    return Optional.of(msg.trim());
                }
            }
        } catch (GitAPIException e) {
            // ignore — repli null
        }
        return Optional.empty();
    }

    @Override
    public Instant lastContentModifiedAt(UUID documentId, Instant documentCreatedAt) {
        try {
            var commits = git.log()
                    .addPath(relativePath(documentId))
                    .setMaxCount(1)
                    .call();
            for (RevCommit c : commits) {
                return Instant.ofEpochSecond(c.getCommitTime());
            }
        } catch (GitAPIException e) {
            // fallback below
        }
        Page<StoredVersion> latest = listVersions(documentId, 0, 1);
        if (!latest.isEmpty()) {
            Instant at = latest.getContent().getFirst().createdAt();
            if (at != null) {
                return at;
            }
        }
        return documentCreatedAt != null ? documentCreatedAt : Instant.EPOCH;
    }

    @Override
    public VersionDiffResult diff(UUID documentId, int versionA, int versionB) {
        DocumentVersionEntity from = requireEntity(documentId, versionA);
        DocumentVersionEntity to = requireEntity(documentId, versionB);
        if (from.getGitCommitSha() == null || to.getGitCommitSha() == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Versions sans commit Git — diff Git impossible");
        }
        try {
            String path = relativePath(documentId);
            String beforeMd = readBlobAtCommit(from.getGitCommitSha(), path);
            String afterMd = readBlobAtCommit(to.getGitCommitSha(), path);
            List<DiffChange> changes = new ArrayList<>();
            if (!beforeMd.equals(afterMd)) {
                // Diff natif JGit (présence de DiffEntry) + contenu pour l'API
                boolean hasGitDiff = hasPathDiff(from.getGitCommitSha(), to.getGitCommitSha(), path);
                changes.add(new DiffChange(
                        path,
                        hasGitDiff || !beforeMd.equals(afterMd) ? "modified" : "modified",
                        beforeMd,
                        afterMd
                ));
            }
            return new VersionDiffResult(versionA, versionB, List.copyOf(changes));
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Diff Git impossible", e);
        }
    }

    @Override
    public Map<String, Object> loadVersionBody(UUID documentId, int versionNo) {
        DocumentVersionEntity v = requireEntity(documentId, versionNo);
        if (v.getGitCommitSha() != null) {
            try {
                String md = readBlobAtCommit(v.getGitCommitSha(), relativePath(documentId));
                if (!md.isBlank() || v.getBodySnapshot() == null) {
                    return TipTapMarkdown.fromMarkdown(md);
                }
            } catch (IOException e) {
                // fallback projection
            }
        }
        return v.getBodySnapshot() == null ? Map.of() : new HashMap<>(v.getBodySnapshot());
    }

    @Override
    public void close() {
        try {
            git.close();
        } finally {
            instanceLock.close();
        }
    }

    private void commitFile(
            UUID documentId,
            Map<String, Object> body,
            UUID contentAuthorId,
            UUID committerId,
            String message,
            String expectedGitHeadSha
    ) {
        Object lock = documentLocks.computeIfAbsent(documentId, id -> new Object());
        synchronized (lock) {
            String head = resolveHeadSha();
            if (expectedGitHeadSha != null && head != null && !expectedGitHeadSha.equals(head)) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Conflit d'écriture Git : le document a été modifié entre-temps (HEAD attendu "
                                + expectedGitHeadSha + ", actuel " + head + ")");
            }
            try {
                Path file = repoPath.resolve(relativePath(documentId));
                Files.createDirectories(file.getParent());
                String markdown = TipTapMarkdown.toMarkdown(body);
                Files.writeString(file, markdown, StandardCharsets.UTF_8);
                git.add().addFilepattern(relativePath(documentId)).call();
                UUID committer = committerId != null ? committerId : contentAuthorId;
                git.commit()
                        .setMessage(message)
                        .setAuthor(ident(contentAuthorId))
                        .setCommitter(ident(committer))
                        .call();
            } catch (IOException | GitAPIException e) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Commit Git impossible", e);
            }
        }
    }

    private static Map<String, Object> fallbackProjection(Map<String, Object> dbProjection) {
        if (dbProjection == null) {
            return Map.of();
        }
        return new HashMap<>(dbProjection);
    }

    private String resolveHeadSha() {
        try {
            ObjectId head = git.getRepository().resolve("HEAD");
            return head == null ? null : head.getName();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "HEAD Git introuvable", e);
        }
    }

    private boolean hasPathDiff(String shaA, String shaB, String path) throws IOException {
        try (RevWalk walk = new RevWalk(git.getRepository());
             DiffFormatter formatter = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
            formatter.setRepository(git.getRepository());
            RevCommit a = walk.parseCommit(ObjectId.fromString(shaA));
            RevCommit b = walk.parseCommit(ObjectId.fromString(shaB));
            CanonicalTreeParser oldTree = new CanonicalTreeParser();
            CanonicalTreeParser newTree = new CanonicalTreeParser();
            try (var reader = git.getRepository().newObjectReader()) {
                oldTree.reset(reader, a.getTree());
                newTree.reset(reader, b.getTree());
            }
            formatter.setPathFilter(PathFilter.create(path));
            List<DiffEntry> entries = formatter.scan(oldTree, newTree);
            return !entries.isEmpty();
        }
    }

    private String readBlobAtCommit(String sha, String path) throws IOException {
        try (RevWalk walk = new RevWalk(git.getRepository())) {
            RevCommit commit = walk.parseCommit(ObjectId.fromString(sha));
            try (TreeWalk tw = TreeWalk.forPath(git.getRepository(), path, commit.getTree())) {
                if (tw == null) {
                    return "";
                }
                ObjectLoader loader = git.getRepository().open(tw.getObjectId(0));
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                loader.copyTo(out);
                return out.toString(StandardCharsets.UTF_8);
            }
        }
    }

    private DocumentVersionEntity requireEntity(UUID documentId, int versionNo) {
        return versionRepository.findByDocumentIdAndVersionNo(documentId, versionNo)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Version " + versionNo + " introuvable pour ce document"));
    }

    private StoredVersion toStored(DocumentVersionEntity v) {
        return new StoredVersion(
                v.getDocumentId(),
                v.getVersionNo(),
                v.getBodySnapshot(),
                v.getAuthorId(),
                v.getArchivedBy(),
                v.getChangeSummary(),
                v.getCreatedAt() == null ? Instant.now() : v.getCreatedAt(),
                v.getGitCommitSha()
        );
    }

    private static String relativePath(UUID documentId) {
        return "documents/" + documentId + ".md";
    }

    private static PersonIdent ident(UUID authorId) {
        String name = authorId == null ? "socle" : authorId.toString();
        return new PersonIdent(name, name + "@socle.local");
    }
}
