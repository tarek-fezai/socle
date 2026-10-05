# Purge réelle de l'historique Git (suppression définitive d'un document)

Quand `socle.storage.provider=git`, chaque document est un fichier `documents/{uuid}.md` dans un dépôt Git
unique : *chaque version* y est un commit. Supprimer la ligne SQL ne suffit donc pas — le contenu resterait
lisible dans l'historique (`git log -p`, `git grep`, objets pendouillants). La suppression **définitive**
(corbeille purgée, purge manuelle, rétention des archives) réécrit donc l'historique.

> Provider `relational` : rien à faire — les lignes `document_versions` partent avec le document (cascade SQL).

## Quand la purge s'exécute

| Déclencheur | Motif file (`git_purge_queue`) | Acteur audité |
|---|---|---|
| Purge manuelle depuis la corbeille (`TrashService.purgeNow`) | `corbeille` | l'utilisateur |
| Purge automatique de la corbeille (`TrashService.purgeExpired`, 30 j) | `corbeille` | système |
| Rétention des documents / espaces archivés (`RetentionPurgeScheduler`, quotidien) | `retention` | système |
| Effacement RGPD (si suppression document) | `rgpd` | selon flux |

Elle est **bloquée** (`409 legal_hold_active`) tant qu'un *legal hold* couvre le document (gel du document
ou de son espace) ; voir `docs/privacy.md`. Les pièces jointes du document sont purgées dans la même
transaction (`BlobStore` local ou S3) et sont donc aussi protégées par le gel.

## File durable (`git_purge_queue`, V44)

1. **Inscription** dans la **même transaction SQL** que la suppression du document (table
   `git_purge_queue` : identifiants, demandeur, motif, état, tentatives, dernière erreur, horodatages).
2. **Traitement après commit** : réécriture Git + remappage SHA ; les documents d'une même transaction sont
   regroupés en un seul passage (une réécriture + un GC).
3. **Échec** : entrée `failed`, audit à chaque tentative (`document.git_purge_attempt`), nouvelles tentatives
   avec backoff via le scheduler jusqu'au succès (`done`).
4. **Reprise au démarrage** : entrées `running` repassent en `pending` puis sont traitées — **plus de reprise
   manuelle opérateur** en fonctionnement normal.
5. **Admin rétention** : compteurs purges Git en attente / en échec et dernière erreur.

## Procédure (`GitHistoryPurger`, JGit — aucun binaire `git` requis)

Équivalent de
`git filter-branch --index-filter 'git rm --cached --ignore-unmatch documents/<uuid>.md' --prune-empty -- --all`
suivi de `git reflog expire --expire=now --all` et `git gc --aggressive --prune=now`.

1. **Verrouillage** : verrou d'écriture du dépôt (les lectures et commits attendent) puis verrous par document
   dans l'ordre des UUID (pas d'inter-blocage). Le verrou de processus `socle-instance.lock` interdit déjà une
   seconde instance sur le même dépôt.
2. **Sauvegarde** : réf. `refs/backup/pre-purge-{horodatage}` sur l'ancien HEAD.
3. **Réécriture** de toutes les réfs (branches, tags) : chaque commit est recréé avec un arbre sans le(s)
   fichier(s) ; les commits devenus vides sont supprimés (`--prune-empty`). Auteur, committer, date et message
   sont conservés.
4. **Déplacement des réfs**, retrait du fichier de l'index et de l'arbre de travail.
5. **Vérification** : le chemin n'existe plus dans *aucune* révision de *aucune* réf. Sinon, **rollback**
   automatique depuis la sauvegarde et la purge échoue (`git_purge_failed`).
6. **Suppression de la réf. de sauvegarde** (sinon elle garderait les anciens objets joignables), **expiration
   des reflogs** (`.git/logs`, `ORIG_HEAD`, …).
7. **GC agressif avec prune immédiat** (`expire` et `packExpire` = maintenant), puis suppression explicite des
   objets *loose* inatteignables restants (JGit peut en conserver). Un contrôle d'intégrité (test) vérifie
   qu'aucun objet, joignable ou non, ne contient plus le contenu.
8. **Resynchronisation SQL** (toujours **avant** libération du verrou d'écriture Git) : les SHA changent ;
   colonnes remappées (ancien → nouveau ; commit supprimé → ancêtre conservé le plus proche) :
   - `documents.git_head_sha`
   - `document_versions.git_commit_sha`
   - `approval_requests.submitted_git_head_sha` (V38 — empreinte d'approbation)
   Aucun autre stockage SQL de SHA Git (exports, attestations, webhooks, caches).
9. **Audit** `document.git_history_purged` (un événement par document) : `documentId`, `commitsRewritten`,
   `commitsDropped`, `oldHead`, `newHead`, `batchSize`.

## Conséquences opérationnelles

- **Les SHA de tous les commits postérieurs au plus ancien commit du document changent.** Les identifiants
  de commits mémorisés hors de Socle (tickets, notes) ne sont plus valides.
- **Tout clone ou miroir du dépôt doit être re-cloné** (`git clone`), pas simplement `git pull` : un `pull`
  réintroduirait l'ancien historique — et le contenu supprimé — depuis le clone.
- Les sauvegardes du volume Git antérieures à la purge **contiennent encore le contenu** : leur durée de
  conservation doit être cohérente avec la politique de rétention (voir `docs/operations/`).
- Les tags annotés deviennent des tags légers (le message du tag n'est pas conservé).
- La purge est coûteuse (réécriture + GC) : le job de rétention traite les documents par lots de 50.

## Limites connues

- **Rétention des versions** (`version_retention_mode` = `months` / `count`) : supprime les lignes
  `document_versions` mais **pas** les révisions Git correspondantes (celles-ci ne disparaissent qu'à la
  suppression définitive du document). L'historique Git d'un document vivant n'est pas réécrit.
- Un archivage de version concurrent pendant la purge d'un *autre* document peut laisser un SHA obsolète dans
  `document_versions` ; la lecture d'une version retombe alors sur `body_snapshot`.
- Mode Git mono-instance (dépôt local verrouillé).

## Dépannage (cas exceptionnels)

Si la file durable reste bloquée après plusieurs tentatives, consulter l'écran **Rétention** (dernière erreur)
et les audits `document.git_purge_attempt`. Une réf. `refs/backup/pre-purge-*` résiduelle peut subsister
après crash en cours de réécriture — la supprimer seulement après diagnostic du dépôt (voir procédure JGit
ci-dessus). En dernier recours, relancer le traitement après correction : au redémarrage Socle reprend les
entrées `pending` / `failed` éligibles.
