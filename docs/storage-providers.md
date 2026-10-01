# Storage providers — choix d'instance exclusif

## Principe

`socle.storage.provider` = **`relational`** | **`git`**, lu **une fois au démarrage**.

- Pas de mode hybride.
- Pas de choix par espace.
- Pas de bascule à chaud.
- Valeur **absente ou invalide** → l'instance **refuse de démarrer**
  (`StorageProperties.validate`, pas de défaut silencieux côté Java).

## Provider `relational` (défaut documenté / prod actuelle)

- Contenu canonique : `documents.body` (JSONB TipTap).
- Historique : `document_versions.body_snapshot`.
- Diff : `BodyDiff` structurel JSON.
- Search : trigger FTS sur les colonnes Postgres (inchangé).

Comportement observable API **identique** à l'implémentation antérieure à
l'abstraction `DocumentStore`.

## Décision : un dépôt Git par instance

La spec initiale faisait de **l'espace** le périmètre physique de stockage
(un dépôt — ou un sous-arbre isolé — par espace). **Décision V1 : un seul
dépôt Git par instance**, avec des chemins plats `documents/{id}.md`.

Raisons retenues :

- **ids globaux** — l'identifiant document est unique à l'instance ; le chemin
  fichier n'a pas besoin d'encoder l'espace ;
- **déplacement entre espaces** — changer `space_id` ne nécessite aucune
  migration de dépôt ni de réécriture d'historique Git ;
- **concurrence simple** — un seul HEAD à coordonner (voir verrou / `git_head_sha`) ;
- **sauvegarde unique** — un artefact à sauvegarder / restaurer pour toute
  l'instance.

**Conséquence :** pas d'export Git natif « un dépôt = un espace ». Un export
filtré par espace reste possible plus tard via `git filter-repo` (ou équivalent)
ou via l'API d'export documentaire existante.

**Réouverture :** si un client exige une séparation physique stricte par espace
(compliance, multi-équipe hors confiance, etc.), la décision pourra être
revue — ce n'est pas un verrou produit définitif, seulement le défaut V1.

## Provider `git` (JGit)

Le dépôt Git **est** le moteur de persistance du contenu (un dépôt par
instance — voir section précédente) :

| Événement API | Git | Métadonnée API |
|---------------|-----|----------------|
| create | commit initial `documents/{id}.md` | `current_version_no=1`, pas de row versions |
| update / restore | commit du nouveau Markdown | archive row `document_versions` + `git_commit_sha` = SHA **avant** le nouveau commit |

**Correspondance commit ↔ version :**

- `document_versions.version_no` reste le numéro exposé par
  `GET /api/v1/documents/{id}/versions`.
- `document_versions.git_commit_sha` pointe vers le commit Git qui contenait
  ce contenu au moment de l'archivage.
- Diff entre deux versions : **JGit** (`DiffFormatter` / lecture des blobs aux
  SHA), pas un second algorithme parallèle à `BodyDiff`.
- Restore : lecture du blob au SHA de la version cible, puis nouveau commit
  append-only (l'historique Git n'est pas réécrit).

### Syntaxe contenu

En mode Git, le fichier versionné est un **overlay Markdown** dérivé du JSON
TipTap de l'éditeur (`TipTapMarkdown`). L'API continue d'échanger du JSON
TipTap avec le frontend — le frontend **ne connaît pas** le provider actif
(contrat API inchangé).

#### Aller-retour sans perte (garantie par construction)

Pour **chaque bloc de premier niveau**, `toMarkdown` :

1. produit un candidat Markdown (StarterKit + directives) ;
2. le reparse avec `fromMarkdown` ;
3. si le résultat n'est **pas strictement égal** au bloc d'origine
   (attrs, marks, nœuds inline inclus) → émet `:::socle-json` avec le nœud
   TipTap complet (Jackson).

Ainsi aucun chemin ne peut supprimer du contenu : au pire le bloc part en JSON
réservé, toujours relisible.

| Cas lisible en Markdown | Sérialisation |
|-------------------------|---------------|
| paragraph / heading / listes simples / blockquote de paragraphes | MD classique |
| Marks `bold` / `italic` / `code` / `strike` (+ bold+italic) | `**` `*` `` ` `` `~~` `***` |
| `transclusion` (seul attr `documentId` UUID valide) | `::transclusion{documentId="<uuid>"}` |
| `codeBlock` + `attrs.language` | ```` ```lang ```` (fence allongé si `` ` `` dans le corps) |
| Paragraphes vides | marqueur `\` |
| `hardBreak` | `\` en fin de ligne |
| Débuts `- ` / `1. ` / `# ` / `> ` / `::` et littéraux `*` `` ` `` `~` | échappés `\` |

**Partent en `:::socle-json`** (volontairement) :

- listes imbriquées, items multi-paragraphes, transclusion dans une liste ;
- blockquote contenant autre chose que des paragraphes (ex. transclusion) ;
- `transclusion` avec attrs supplémentaires (`collapsed`, …) ou UUID invalide ;
- marks inconnus (`underline`, …) ou marks avec attrs ;
- nœuds / inlines inconnus (`callout`, `mention`, …) ;
- tout bloc dont l'aller-retour MD échoue pour une autre raison.

#### Diagnostic dérive projection ↔ Git (lecture seule)

`GET /api/v1/admin/storage/git-projection-drift` (rôle realm
`administrateur-systeme`) compare **tout le contenu** : projection
`fromMarkdown(toMarkdown(body))` vs blob Git HEAD.

- **Ne corrige rien** automatiquement.
- Réparation manuelle : commit append-only depuis `documents.body`
  (`writeCurrentContent` + `expectedHeadSha = git_head_sha`).

#### Limites restantes

- Les diffs Git portent sur le Markdown overlay (dont blocs `socle-json`), pas
  sur le JSON TipTap brut.
- Données déjà abîmées avant ce correctif : à réparer via l'endpoint drift.

### Chemin de lecture (GET / Edit)

| Rôle | Mode Git |
|------|----------|
| **Canonique** | blob au commit HEAD (`DocumentStore.readCurrentContent` → JGit) |
| **Projection** | `documents.body` (JSON) — **Search uniquement**, pas la source de GET |

`DocumentService.get` et le détail d'une version archivée passent par le store
(JGit pour le courant et `loadVersionBody` pour l'historique). Si la projection
Postgres diverge du dépôt, l'utilisateur voit le contenu Git.

## Search en mode Git — stratégie retenue

**Projection légère** : `DocumentService` continue d'écrire titre + body JSON
(+ tags) dans les colonnes Postgres indexées. Le trigger FTS existant s'applique
à l'identique.

- Canonique contenu/historique = Git.
- Index Search = projection Postgres synchronisée à chaque create/update/restore.
- Search ne se dégrade pas et ne renvoie pas de résultats obsolètes : la
  projection est mise à jour dans la même opération métier que le commit.

Aucune autre stratégie (index Git grep, moteur externe) n'est utilisée dans
cette itération.

### Concurrence (V1)

Deux écritures concurrentes sur le même document peuvent produire des commits
non fast-forward. Mitigation V1 :

- verrou **par document** dans `GitDocumentStore` (single JVM) ;
- colonne `documents.git_head_sha` : avant chaque commit, comparaison avec HEAD
  réel → **409 Conflict** si mismatch (même idée que `expectedStepOrder` côté
  workflows, sans exposer le SHA au client pour l'instant).

**Limite connue V1 :** plusieurs instances JVM partageant le même dépôt Git
sans coordination distribuée peuvent encore diverger — à traiter en V2 (ref
externe, lock distribué ou dépôt par instance). Documenté explicitement, pas un
angle mort silencieux.

### Bascule de provider (les deux sens)

| Transition | Risque | Garde |
|------------|--------|-------|
| **relational → git** | lecture retombe sur projection Postgres sans blob | `GitStorageConsistencyValidator` : refuse si docs actifs sans `git_head_sha` |
| **git → relational** | dépôt Git ignoré, body JSONB vide/inutilisable | `RelationalStorageConsistencyValidator` : refuse si `git_head_sha` présent **et** `body` vide/`{}` |

En fonctionnement nominal Git, `DocumentService` écrit **toujours** le TipTap dans
`documents.body` à chaque create/update/restore (même colonne que Search). La bascule
git→relational est donc sûre si tout le contenu a passé par l'API : le body JSONB
reste la source relational. Le validateur inverse attrape le cas pathologique
(body vide alors qu'un SHA Git existe).

Il n'y a **pas** de troisième option silencieuse dans les deux sens.

Les transitions de workflow qui ne touchent que le **statut** (`en_revue` /
`valide` / `brouillon`) restent en JDBC direct (hors contenu). La **soumission**
qui archive une version passe par `DocumentStore` (commit Git cohérent).

## Configuration

```yaml
socle:
  storage:
    provider: relational   # ou git
    git-repository-path: ./data/git-content
```

Interface : `eu.socle.storage.DocumentStore`.
