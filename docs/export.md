# Export PDF documentaire

## Objectif

Exporter un document (ou une collection dossier/tag) en PDF **sans contourner**
la confidentialité : même résolution de transclusion et même filtre OpenFGA que
la lecture normale.

## Décisions

| Décision | Choix | Justification |
|----------|-------|----------------|
| Résolution contenu | Réutilise **`TransclusionResolver.resolve`** | Pas de second chemin de rendu — dette Transclusion fermée. |
| Collection dossier/tag | ∩ **`listViewableDocumentIds`** | Même famille Search / Graphe / ContentHealth. |
| Bloc transclus interdit | Libellé **« Contenu non accessible »** (jamais omis, jamais le contenu) | Aligné vue web (`CompositePage`). |
| Format V1 | **PDF** synchrone (OpenPDF) | Maquettes prévoient aussi Word — hors scope V1. |
| ZIP dossier | `GET /folders/{id}/export?format=zip` | La page de dossier est `_dossier.md` à la racine. Le PDF fusionné reste le défaut. |
| ZIP espace | `GET /spaces/{id}/export?format=zip` | La page d'espace est `_espace.md` à la racine. |
| Job async `folder_exports` | **Non utilisé** pour V1 | Génération à la demande ; table schéma inchangée pour une évolution future. |
| Audit | **Oui** — `document.exported` / `folder.exported` / `space.exported` / `tag.exported` | Un export est un vecteur de fuite a posteriori ; traçabilité cohérente Spaces/Groupes. |
| RGPD `ExportPersonalData` | **Hors scope** | Sujet distinct (données personnelles de l'utilisateur). |

## API

| Endpoint | Rôle |
|----------|------|
| `GET /api/v1/documents/{id}/export` | PDF du document (body résolu) |
| `GET /api/v1/folders/{id}/export` | PDF fusionné des docs **lisibles** du dossier (+ sous-dossiers) |
| `GET /api/v1/spaces/{id}/export?format=zip` | ZIP de l'espace ; la page est `_espace.md` à la racine |
| `GET /api/v1/tags/{id}/export` | PDF fusionné des docs **lisibles** portant le tag |

Réponse : `Content-Disposition: attachment`, `Cache-Control: no-store`.

## Frontend

| Route | Maquette |
|-------|----------|
| `/docs/:id/export` | `Export.dc.html` |
| `/folders/:id/export` | `ExportFolder.dc.html` |
| `/tags/:id/export` | `ExportTag.dc.html` |

## Hors scope

- Export Word / Markdown / ZIP multi-fichiers
- Filigrane, en-têtes riches, commentaires
- ExportPersonalData (RGPD)
- Soft-lock, auditeur transverse, certifications
