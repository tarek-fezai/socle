# Décision — la page de dossier est un document

Date : 2026-10-15. Branche `feature/folder-page-document`.

## Décision

Le contenu éditable d’un dossier est un **document ordinaire**, rattaché à ce dossier comme page de dossier (`folders.page_document_id`). Il réutilise le cycle de vie document : versions, historique, comparaison, commentaires, workflow d’approbation de l’espace, droits OpenFGA, audit et export.

`folders.body` n’est ni modifié ni supprimé. La colonne est **obsolète** : aucun nouvel écran ni aucune API ne l’écrit. Le contenu vit dans le document de page.

Aucune page n’est créée tant qu’on n’enregistre pas `PUT /api/v1/folders/{id}/page`.

## Listes

La page n’est pas un document parmi les autres.

| Surface | Choix |
|---|---|
| Arbre d’espace, `documentCount`, documents du dossier | Exclue |
| Récents, Favoris, Accueil | **Exclue** |
| Recherche | Trouvable, titre `Dossier — <nom>`, `folderId` pour ouvrir le dossier |

**Pourquoi exclure Récents, Favoris et Accueil.** Ces listes montrent des documents à reprendre ou à ouvrir. La page est le dossier lui-même : l’y afficher doublerait le dossier et mènerait à un écran document que l’écran de dossier (chantier suivant) remplace. Le dossier reste, lui, un favori ou un élément d’accueil quand il y est déjà en tant que dossier. La recherche est le chemin de découverte du texte : elle garde la page, avec le libellé et le lien vers le dossier.

## Opérations

Déplacer, supprimer ou restaurer la page seule répond `409` `folder_page_locked`. Supprimer, restaurer ou déplacer le dossier emporte la page.

Lire la page exige de pouvoir lire le dossier. La modifier exige le droit d’édition du dossier. Les tuples OpenFGA du document sont ceux d’un document du dossier.

Les événements `document.*` portent `metadata.folderPage = true` et `folderId`.

L’export ZIP du dossier (`GET /api/v1/folders/{id}/export?format=zip`) place la page dans `_dossier.md` à la racine de l’archive. L’export PDF existant ne change pas.
