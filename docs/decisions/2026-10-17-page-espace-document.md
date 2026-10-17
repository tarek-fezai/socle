# Décision — l'accueil d'un espace est un document rattaché

Date : 2026-10-17. Branche `feature/space-page-document`. Décision DP-2026-10-17.

## Décision

L'accueil d'un espace suit le modèle de la page document (Lire, Modifier, Historique, Commentaires, Accès). Son contenu est un **document ordinaire** rattaché à l'espace (`spaces.page_document_id`), exactement comme la page de dossier (`folders.page_document_id`, V50).

`spaces.description` reste le résumé court affiché sur les cartes de la page Espaces. Il n'est pas le corps de l'accueil.

Graphe pointe vers le graphe de l'espace. La seconde sidebar de la page d'espace sera supprimée dans le chantier E2. Ce chantier-ci ne change aucun écran.

Aucune page n'est créée tant qu'on n'enregistre pas `PUT /api/v1/spaces/{id}/page`. Le document créé a `space_id` égal à cet espace et `folder_id` NULL. Son titre est le nom de l'espace, synchronisé dans la même transaction que le renommage.

## Listes

La page n'est pas un document parmi les autres. L'exclusion est commune aux pages de dossier et aux pages d'espace.

| Surface | Choix |
|---|---|
| Arbre, compteurs, listes de documents | Exclue |
| Récents, Favoris, Accueil | Exclue |
| Santé du contenu, tags, lot isolé de la rétention | Exclue |
| Recherche | Trouvable, titre `Espace — <nom>`, lien vers l'espace |

## Opérations

Déplacer, supprimer ou restaurer la page seule répond `409` `space_page_locked`. Archiver, supprimer, restaurer ou purger l'espace emporte la page.

`PUT /documents/{pageId}` dont le titre diffère du nom de l'espace répond `409` `space_page_title_locked`.

La référence `spaces.page_document_id` est retirée dans la même transaction, avant le `DELETE` des documents, dans `RetentionPurgeService.purgeSpace` et dans la purge de corbeille.

Les événements `document.*` portent `spacePage: true` et `spaceId`, lus sur `spaces.page_document_id` (pas de fil d'exécution).

Lire la page exige de pouvoir lire l'espace. La modifier exige le droit d'édition de l'espace. Les tuples OpenFGA du document sont ceux d'un document à la racine de l'espace.

L'export ZIP de l'espace place la page dans `_espace.md` à la racine de l'archive.
