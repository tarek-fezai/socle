// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.storage;

import org.eclipse.jgit.api.GarbageCollectCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.internal.storage.file.GC;
import org.eclipse.jgit.storage.pack.PackConfig;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.ObjectWalk;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Purge <strong>réelle</strong> d'un ou plusieurs fichiers {@code documents/{uuid}.md} de
 * <em>toutes</em> les révisions (équivalent JGit de
 * {@code git filter-branch --index-filter 'git rm --cached --ignore-unmatch …' --prune-empty -- --all},
 * suivi de {@code git reflog expire --expire=now --all} et {@code git gc --aggressive --prune=now}).
 *
 * <p>JGit pur (aucun binaire {@code git} requis dans l'image). Séquence :
 * <ol>
 *   <li>réf. de sauvegarde {@code refs/backup/pre-purge-{ts}} sur l'ancien HEAD ;</li>
 *   <li>réécriture de tous les commits (arbres sans le fichier ; commits devenus vides supprimés) ;</li>
 *   <li>déplacement des réfs, retrait du fichier de l'index / de l'arbre de travail ;</li>
 *   <li>vérification : le chemin n'existe plus dans aucune révision (sinon rollback depuis la sauvegarde) ;</li>
 *   <li>suppression de la réf. de sauvegarde (sinon elle garderait les anciens objets vivants) ;</li>
 *   <li>expiration des reflogs, puis GC agressif avec prune immédiat.</li>
 * </ol>
 * L'appelant détient le verrou d'écriture du dépôt.
 */
final class GitHistoryPurger {

    private static final Logger log = LoggerFactory.getLogger(GitHistoryPurger.class);

    static final String DOCUMENTS_DIR = "documents";
    static final String BACKUP_PREFIX = "refs/backup/pre-purge-";

    private static final DateTimeFormatter TS = DateTimeFormatter
            .ofPattern("yyyyMMdd'T'HHmmssSSS'Z'").withZone(ZoneOffset.UTC);

    record Outcome(
            int commitsRewritten,
            int commitsDropped,
            String oldHead,
            String newHead,
            Map<String, String> commitMapping,
            String backupRef
    ) {}

    private final Git git;
    private final Path repoPath;

    GitHistoryPurger(Git git, Path repoPath) {
        this.git = git;
        this.repoPath = repoPath;
    }

    /** @param fileNames noms {@code {uuid}.md} sous {@code documents/}. */
    Outcome purge(Set<String> fileNames) throws IOException, GitAPIException {
        Repository repo = git.getRepository();
        ObjectId oldHead = repo.resolve("HEAD");
        if (oldHead == null || fileNames.isEmpty()) {
            return new Outcome(0, 0, oldHead == null ? null : oldHead.name(),
                    oldHead == null ? null : oldHead.name(), Map.of(), null);
        }

        String backupRef = BACKUP_PREFIX + TS.format(Instant.now());
        updateRef(backupRef, oldHead, "socle: backup before git purge");

        Map<String, ObjectId> originalRefs = new LinkedHashMap<>();
        boolean refsMoved = false;
        Rewrite rewrite;
        try {
            List<Ref> refs = rewritableRefs(repo);
            for (Ref r : refs) {
                originalRefs.put(r.getName(), r.getObjectId());
            }
            rewrite = rewrite(repo, refs, fileNames);
            if (rewrite.changedCommits() > 0) {
                refsMoved = true;
                moveRefs(repo, refs, rewrite.map());
            }
            removeFromIndexAndWorkTree(fileNames);
            verifyAbsent(repo, fileNames);
        } catch (IOException | GitAPIException | RuntimeException e) {
            rollback(repo, originalRefs, oldHead, refsMoved, backupRef, e);
            throw e;
        }

        // Plus de chemin de retour : retirer la sauvegarde AVANT le GC (sinon les objets restent joignables).
        deleteRef(backupRef);
        expireReflogs(repo);
        collectGarbage();

        ObjectId newHead = repo.resolve("HEAD");
        Map<String, String> mapping = new LinkedHashMap<>();
        for (Map.Entry<ObjectId, ObjectId> e : rewrite.map().entrySet()) {
            if (!e.getKey().equals(e.getValue())) {
                mapping.put(e.getKey().name(), e.getValue().name());
            }
        }
        log.info("Purge Git : {} fichier(s), {} commit(s) réécrit(s) dont {} supprimé(s), HEAD {} -> {}",
                fileNames.size(), rewrite.changedCommits(), rewrite.dropped(), oldHead.name(),
                newHead == null ? null : newHead.name());
        return new Outcome(
                rewrite.changedCommits(),
                rewrite.dropped(),
                oldHead.name(),
                newHead == null ? null : newHead.name(),
                Map.copyOf(mapping),
                backupRef);
    }

    // ── Réécriture ──────────────────────────────────────────────────────────

    private record Rewrite(Map<ObjectId, ObjectId> map, int changedCommits, int dropped) {}

    private static List<Ref> rewritableRefs(Repository repo) throws IOException {
        List<Ref> out = new ArrayList<>();
        for (Ref ref : repo.getRefDatabase().getRefsByPrefix("refs/")) {
            if (ref.getName().startsWith("refs/backup/") || ref.getObjectId() == null) {
                continue;
            }
            out.add(ref);
        }
        return out;
    }

    private Rewrite rewrite(Repository repo, List<Ref> refs, Set<String> fileNames) throws IOException {
        Map<ObjectId, ObjectId> map = new HashMap<>();
        Map<ObjectId, ObjectId> treeOf = new HashMap<>();
        int changed = 0;
        int dropped = 0;

        try (RevWalk walk = new RevWalk(repo);
             ObjectReader reader = repo.newObjectReader();
             ObjectInserter ins = repo.newObjectInserter()) {
            walk.sort(RevSort.TOPO);
            walk.sort(RevSort.REVERSE, true);
            for (Ref ref : refs) {
                RevObject o = walk.parseAny(ref.getObjectId());
                RevObject peeled = walk.peel(o);
                if (peeled instanceof RevCommit c) {
                    walk.markStart(c);
                }
            }
            for (RevCommit c : walk) {
                walk.parseHeaders(c);
                ObjectId oldTree = c.getTree().getId();
                ObjectId newTree = removeFiles(reader, ins, oldTree, fileNames);
                if (newTree == null) {
                    newTree = ins.insert(new TreeFormatter());
                }
                boolean treeChanged = !newTree.equals(oldTree);

                List<ObjectId> newParents = new ArrayList<>();
                boolean parentsChanged = false;
                for (RevCommit p : c.getParents()) {
                    ObjectId np = map.get(p.getId());
                    if (np == null) {
                        throw new IllegalStateException("parent non traité " + p.getId().name());
                    }
                    parentsChanged |= !np.equals(p.getId());
                    if (!newParents.contains(np)) {
                        newParents.add(np);
                    } else {
                        parentsChanged = true;
                    }
                }

                if (!treeChanged && !parentsChanged) {
                    map.put(c.getId(), c.getId());
                    treeOf.put(c.getId(), oldTree);
                    continue;
                }
                // Commit devenu vide (ne touchait que les fichiers purgés) : supprimé (--prune-empty).
                if (treeChanged && newParents.size() == 1 && newTree.equals(treeOf.get(newParents.getFirst()))) {
                    map.put(c.getId(), newParents.getFirst());
                    dropped++;
                    changed++;
                    continue;
                }
                CommitBuilder cb = new CommitBuilder();
                cb.setTreeId(newTree);
                cb.setParentIds(newParents);
                cb.setAuthor(c.getAuthorIdent());
                cb.setCommitter(c.getCommitterIdent());
                cb.setMessage(c.getFullMessage());
                if (c.getEncoding() != null) {
                    cb.setEncoding(c.getEncoding());
                }
                ObjectId nc = ins.insert(cb);
                map.put(c.getId(), nc);
                treeOf.put(nc, newTree);
                changed++;
            }
            ins.flush();
        }
        return new Rewrite(map, changed, dropped);
    }

    /**
     * Retire {@code documents/<fileName>} de l'arbre racine ; {@code null} si l'arbre résultant est vide.
     */
    private static ObjectId removeFiles(
            ObjectReader reader, ObjectInserter ins, ObjectId rootTree, Set<String> fileNames) throws IOException {
        TreeFormatter root = new TreeFormatter();
        int rootEntries = 0;
        boolean changed = false;
        CanonicalTreeParser rootParser = new CanonicalTreeParser(null, reader, rootTree);
        while (!rootParser.eof()) {
            String name = rootParser.getEntryPathString();
            if (DOCUMENTS_DIR.equals(name) && FileMode.TREE.equals(rootParser.getEntryRawMode())) {
                ObjectId docsTree = rootParser.getEntryObjectId();
                TreeFormatter docs = new TreeFormatter();
                boolean docsChanged = false;
                int kept = 0;
                CanonicalTreeParser docsParser = new CanonicalTreeParser(null, reader, docsTree);
                while (!docsParser.eof()) {
                    String fileName = docsParser.getEntryPathString();
                    if (fileNames.contains(fileName)) {
                        docsChanged = true;
                    } else {
                        docs.append(fileName, docsParser.getEntryFileMode(), docsParser.getEntryObjectId());
                        kept++;
                    }
                    docsParser.next();
                }
                if (docsChanged) {
                    changed = true;
                    if (kept > 0) {
                        root.append(name, FileMode.TREE, ins.insert(docs));
                        rootEntries++;
                    }
                } else {
                    root.append(name, FileMode.TREE, docsTree);
                    rootEntries++;
                }
            } else {
                root.append(name, rootParser.getEntryFileMode(), rootParser.getEntryObjectId());
                rootEntries++;
            }
            rootParser.next();
        }
        if (!changed) {
            return rootTree;
        }
        if (rootEntries == 0) {
            return null;
        }
        return ins.insert(root);
    }

    // ── Réfs / working tree ─────────────────────────────────────────────────

    private void moveRefs(Repository repo, List<Ref> refs, Map<ObjectId, ObjectId> map) throws IOException {
        try (RevWalk walk = new RevWalk(repo)) {
            for (Ref ref : refs) {
                RevObject peeled = walk.peel(walk.parseAny(ref.getObjectId()));
                if (!(peeled instanceof RevCommit commit)) {
                    continue;
                }
                ObjectId target = map.get(commit.getId());
                if (target != null && !target.equals(ref.getObjectId())) {
                    updateRef(ref.getName(), target, "socle: git purge");
                }
            }
        }
    }

    private void removeFromIndexAndWorkTree(Set<String> fileNames) throws GitAPIException, IOException {
        for (String fileName : fileNames) {
            String path = DOCUMENTS_DIR + "/" + fileName;
            git.rm().addFilepattern(path).call();
            Files.deleteIfExists(repoPath.resolve(path));
        }
    }

    private void verifyAbsent(Repository repo, Set<String> fileNames) throws IOException {
        try (RevWalk walk = new RevWalk(repo)) {
            for (Ref ref : rewritableRefs(repo)) {
                RevObject peeled = walk.peel(walk.parseAny(ref.getObjectId()));
                if (peeled instanceof RevCommit c) {
                    walk.markStart(c);
                }
            }
            for (RevCommit c : walk) {
                for (String fileName : fileNames) {
                    try (TreeWalk tw = TreeWalk.forPath(repo, DOCUMENTS_DIR + "/" + fileName, c.getTree())) {
                        if (tw != null) {
                            throw new IllegalStateException(
                                    "chemin encore présent après réécriture : " + fileName
                                            + " @ " + c.getId().name());
                        }
                    }
                }
            }
        }
    }

    private void rollback(
            Repository repo,
            Map<String, ObjectId> originalRefs,
            ObjectId oldHead,
            boolean refsMoved,
            String backupRef,
            Exception cause
    ) {
        log.error("Purge Git échouée — rollback depuis {}", backupRef, cause);
        try {
            if (refsMoved) {
                for (Map.Entry<String, ObjectId> e : originalRefs.entrySet()) {
                    updateRef(e.getKey(), e.getValue(), "socle: git purge rollback");
                }
            }
            git.reset().setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD).setRef(oldHead.name()).call();
            deleteRefQuietly("ORIG_HEAD");
            deleteRef(backupRef);
        } catch (IOException | GitAPIException | RuntimeException rollbackFailure) {
            log.error("Rollback purge Git impossible — la réf. {} est conservée pour restauration manuelle",
                    backupRef, rollbackFailure);
            cause.addSuppressed(rollbackFailure);
        }
    }

    private void updateRef(String name, ObjectId target, String message) throws IOException {
        Repository repo = git.getRepository();
        RefUpdate ru = repo.updateRef(name);
        ru.setNewObjectId(target);
        ru.setForceUpdate(true);
        ru.setRefLogMessage(message, false);
        RefUpdate.Result result = ru.update();
        switch (result) {
            case NEW, FORCED, FAST_FORWARD, NO_CHANGE, RENAMED -> { }
            default -> throw new IOException("mise à jour de " + name + " refusée : " + result);
        }
    }

    private void deleteRef(String name) throws IOException {
        Repository repo = git.getRepository();
        if (repo.exactRef(name) == null) {
            return;
        }
        RefUpdate ru = repo.updateRef(name);
        ru.setForceUpdate(true);
        RefUpdate.Result result = ru.delete();
        switch (result) {
            case FORCED, NO_CHANGE, NEW, FAST_FORWARD, RENAMED -> { }
            default -> throw new IOException("suppression de " + name + " refusée : " + result);
        }
    }

    private void deleteRefQuietly(String name) {
        try {
            deleteRef(name);
        } catch (IOException e) {
            log.debug("Suppression {} ignorée: {}", name, e.toString());
        }
    }

    // ── Reflog / GC ─────────────────────────────────────────────────────────

    /** Équivalent {@code git reflog expire --expire=now --all} : plus aucun reflog ne garde d'ancien SHA. */
    private void expireReflogs(Repository repo) throws IOException {
        Path logs = repo.getDirectory().toPath().resolve("logs");
        if (Files.isDirectory(logs)) {
            try (Stream<Path> files = Files.walk(logs)) {
                for (Path p : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                    Files.deleteIfExists(p);
                }
            }
        }
        for (String special : List.of("ORIG_HEAD", "FETCH_HEAD", "MERGE_HEAD", "CHERRY_PICK_HEAD")) {
            deleteRefQuietly(special);
            Files.deleteIfExists(repo.getDirectory().toPath().resolve(special));
        }
    }

    /** Équivalent {@code git gc --aggressive --prune=now}. */
    private void collectGarbage() throws GitAPIException, IOException {
        // Verrou d'écriture détenu : aucun objet « récent » à protéger — tout ce qui n'est plus joignable part.
        // expire (objets loose) ET packExpire (objets « garbage » d'anciens packs) : sans le second, JGit
        // conserve les objets inatteignables dans un pack d'ordures jusqu'à expiration (1 h par défaut).
        Date now = Date.from(Instant.now().plusSeconds(5));
        Repository repo = git.getRepository();
        if (repo instanceof FileRepository fileRepo) {
            PackConfig aggressive = new PackConfig(repo);
            aggressive.setDeltaSearchWindowSize(GarbageCollectCommand.DEFAULT_GC_AGGRESSIVE_WINDOW);
            aggressive.setMaxDeltaDepth(GarbageCollectCommand.DEFAULT_GC_AGGRESSIVE_DEPTH);
            GC gc = new GC(fileRepo);
            gc.setPackConfig(aggressive);
            gc.setExpire(now);
            gc.setPackExpire(now);
            try {
                gc.gc().get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("GC interrompu", e);
            } catch (java.util.concurrent.ExecutionException | java.text.ParseException e) {
                throw new IOException("GC en échec", e);
            }
        } else {
            git.gc().setAggressive(true).setExpire(now).call();
        }
        pruneUnreachableLooseObjects(repo);
    }

    /**
     * Garantie « prune=now » indépendante de l'heuristique de JGit (qui peut conserver des objets loose
     * inatteignables) : après le GC tous les objets joignables sont empaquetés ; tout objet loose qui n'est
     * pas joignable depuis une réf. (ni référencé par l'index) est supprimé.
     */
    private void pruneUnreachableLooseObjects(Repository repo) throws IOException {
        Path objects = repo.getDirectory().toPath().resolve("objects");
        if (!Files.isDirectory(objects)) {
            return;
        }
        Set<ObjectId> reachable = new HashSet<>();
        try (ObjectWalk walk = new ObjectWalk(repo)) {
            List<ObjectId> starts = new ArrayList<>();
            for (Ref ref : repo.getRefDatabase().getRefs()) {
                if (ref.getObjectId() != null) {
                    starts.add(ref.getObjectId());
                }
            }
            ObjectId head = repo.resolve("HEAD");
            if (head != null) {
                starts.add(head);
            }
            for (ObjectId id : starts) {
                walk.markStart(walk.parseAny(id));
            }
            RevCommit commit;
            while ((commit = walk.next()) != null) {
                reachable.add(commit.getId());
            }
            RevObject o;
            while ((o = walk.nextObject()) != null) {
                reachable.add(o.getId());
            }
        }
        var cache = repo.readDirCache();
        for (int i = 0; i < cache.getEntryCount(); i++) {
            reachable.add(cache.getEntry(i).getObjectId());
        }

        int deleted = 0;
        try (Stream<Path> dirs = Files.list(objects)) {
            for (Path dir : (Iterable<Path>) dirs.filter(p -> p.getFileName().toString().length() == 2
                    && Files.isDirectory(p))::iterator) {
                try (Stream<Path> files = Files.list(dir)) {
                    for (Path f : (Iterable<Path>) files::iterator) {
                        String name = dir.getFileName() + f.getFileName().toString();
                        if (name.length() != 40) {
                            continue;
                        }
                        if (!reachable.contains(ObjectId.fromString(name))) {
                            f.toFile().setWritable(true);
                            Files.deleteIfExists(f);
                            deleted++;
                        }
                    }
                }
                try (Stream<Path> left = Files.list(dir)) {
                    if (left.findAny().isEmpty()) {
                        Files.deleteIfExists(dir);
                    }
                }
            }
        }
        log.info("Git purge: {} objet(s) loose inatteignable(s) supprimé(s)", deleted);
    }
}
