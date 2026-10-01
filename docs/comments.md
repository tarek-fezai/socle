# Commentaires documents

Fils de discussion (1 niveau de réponses), commentaires de page ou **ancrés**
via sélecteur W3C `TextQuoteSelector` (`exact` + `prefix`/`suffix` ≤ 32).
Les ancres ne sont **jamais** écrites dans le body TipTap, Git ou les versions.

## Politique espace

`spaces.comment_policy` : `members` (défaut) | `all_readers`.

- Lire = `document.viewer`
- Commenter = `document.editor` **ou** (`space.viewer` si `members`) **ou**
  (`document.viewer` si `all_readers`)
- Modération (suppression) = auteur ou `space.owner`
- Résoudre / rouvrir = auteur du fil, `document.editor`, `space.owner`

## API

| Méthode | Chemin |
|---------|--------|
| GET | `/api/v1/documents/{id}/comments?status=&version=` |
| POST | `/api/v1/documents/{id}/comments` |
| PATCH/DELETE | `/api/v1/comments/{id}` |
| POST | `/api/v1/comments/{id}/resolve` · `/reopen` |
| GET | `/api/v1/me/export` (RGPD — commentaires inclus) |

## Mentions

Format `@[Nom](uuid)`. Notification `comment_mention` **seulement** si le
mentionné a `document.viewer`. Payload : ids uniquement (pas de titre/extrait
dans le payload). Avertissement API si pas d'accès.

## Soft-delete

Affiché « Commentaire supprimé (par l'auteur | par un modérateur) ».
`anonymizeAuthor(userId)` → « Utilisateur supprimé ».
