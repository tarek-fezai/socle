# Décision — dossiers dans la sidebar

Date : 2026-10-16. Branche `fix/sidebar-folder-tree`.

## Arbre

La sidebar du shell (`ShellSidebar`) rend l'arbre déjà renvoyé par `GET /api/v1/spaces/{id}/tree`. Aucun second appel. L'ordre, pour chaque espace : documents à la racine, puis dossiers. À l'intérieur d'un dossier : ses documents, puis ses sous-dossiers. Le tri est position, puis nom. La profondeur n'est pas bornée côté écran : le client n'envoie plus `depth=1`, qui coupait les sous-dossiers. Le plafond reste celui de l'API (`SOCLE_FOLDERS_MAX_DEPTH`, 5 par défaut).

La page du dossier n'est pas une ligne de document. `FolderService.loadDocs` la retire déjà (`FolderPages.EXCLUDE_SQL` : `NOT EXISTS` sur `folders.page_document_id`). L'écran ne la réintroduit pas.

Un document au statut `brouillon` porte le libellé « brouillon » à droite, comme dans `FolderProcedures.dc.html`.

Arriver sur un document ou un dossier ouvre son espace et les dossiers ancêtres, et surligne l'élément courant. Les autres dossiers gardent l'état choisi dans la session.

## Retrait

La maquette aligne un dossier racine à 10 px (`padding: 8px 10px`) et un document dans ce dossier à 22 px. Chaque niveau supplémentaire, absent de la maquette, ajoute 12 px :

| Niveau | Dossier (`padding-left`) | Document (`padding-left`) |
| --- | --- | --- |
| Racine de l'espace | 10 px | 10 px (lien existant) |
| Dans un dossier | 22 px | 22 px |
| Un cran de plus | +12 px | +12 px |

Le chevron est le bouton d'ouverture (`aria-expanded`, flèches gauche et droite). Le nom mène à `/folders/{id}`. Le dossier actif a le fond `#F0EFFC` et le texte `#3730E0` en gras, comme la ligne active de la maquette. Les autres dossiers restent en `#6B6B72`.

## Parité

`Dashboard.dc.html` ne dessinait pas les dossiers que la fixture `TREE_IDENTITE` contient déjà (Procédures, Référence), ni le libellé brouillon de Politique et du plan de reprise. Les afficher dans l'app sans toucher la maquette aurait changé la section `shell-tree`. La maquette d'accueil reprend ces lignes, dossiers repliés, pour que la comparaison reste le même arbre des deux côtés.

DP-2026-10-10 conserve la sidebar de l'app. `FolderProcedures.dc.html` et `FolderReference.dc.html` ont encore l'ancienne sidebar (bouton de repli, Document / Dossier, pas Favoris ni Tous les espaces ni Membres). Comparer cette sidebar entière au shell est impossible pour cette raison : ce n'est pas le même chrome. La section `shell-tree-folders` ne compare que le dossier courant et ses documents, le bloc que les deux côtés partagent. Pas d'exception de taille.
