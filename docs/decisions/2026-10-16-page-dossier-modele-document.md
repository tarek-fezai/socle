# Décision — la page de dossier suit la page document

Date : 2026-10-16. Branche `feature/folder-page-document-model`. Décision DP-2026-10-16.

## Modèle

L'écran de dossier reprend le modèle de la page document. Les maquettes `FolderProcedures.dc.html` et `FolderReference.dc.html` (artifact 159) remplacent la ligne Aperçu / Historique / Accès par :

- un sélecteur Page / Graphe / Index dans la barre du haut, avant Exporter ;
- une barre d'onglets pleine largeur : Lire, Modifier, Historique, Commentaires, Accès.

Lire affiche la page en lecture seule, sans barre d'outils et sans crayon de renommage. Renommer reste dans le menu « ⋯ ». Modifier ouvre l'écran d'édition du document de page et enregistre par `PUT /folders/{id}/page`, puis revient à Lire. Historique et Commentaires portent sur ce document. Accès reste l'écran d'accès du dossier. L'onglet Modifier et l'écriture des commentaires n'apparaissent que si le dossier est éditable.

Graphe ouvre le même écran que depuis un document (`/spaces/:spaceId/graph`), sans filtre de sélection. Index reste le contrôle désactivé de la page document (`title="bientôt"`) : l'écran Index n'existe pas, au même titre que sur un document.
