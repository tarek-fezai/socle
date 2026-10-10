# Parité visuelle — référence pour toutes les PR

Socle doit être **identique à la maquette** (`systeme-documentation-direction-ui/*.dc.html`).
Ce document est la norme pour tout nouveau spec Playwright sous `frontend/e2e-visual/`.

## État des specs

| État | Specs |
| --- | --- |
| **Propres** (méthode `docs/visual-parity.md` : sections, texte inclus, masques Playwright nommés, `NOT_IMPLEMENTED` / `SIZE_EXCEPTIONS` déclarés) | `account-admin`, `generate-token`, `spaces`, `search`, `notifications`, `favorites`, `new-document`, `page`, `edit`, `history`, `history-mobile`, `diff`, `diff-approval`, `restore`, `approval`, `approval-mobile` |
| **À nettoyer** (audit PR #69 — cheats pré-capture : `maskGlyphs` / `maskAdminGlyphs`, forçage DOM, clips pleine page, seuils relâchés, normalizers) | `login`, `dashboard`, `tags-admin`, `custom-fields-admin`, `retention-admin`, `branding-admin` |

## Décisions produit

- **Spaces : shell avec sidebar conservé** (maquette sans sidebar) — largeur de contenu **1076 px** au lieu de **1344 px** ; décision tf du **2026-10-10**. Les `SIZE_EXCEPTIONS` `spaces-title` / cartes y renvoient explicitement.
- **Comparaison (Diff) : shell avec sidebar conservé** (maquette `Diff.dc.html` sans sidebar) — colonne principale **1172 px** au lieu de **1440 px** ; même décision que Spaces (2026-10-10). Les `SIZE_EXCEPTIONS` `diff-topbar` / `diff-controls` / blocs `@@` (Δw = −268) et côtés ancien / nouveau des lignes (Δw = −134, 50 % de la boîte de diff) y renvoient explicitement.
- **Comparaison d’approbation (DiffApproval) : shell avec sidebar conservé** (maquette `DiffApproval.dc.html` sans sidebar) — mêmes largeurs et mêmes `SIZE_EXCEPTIONS` que Diff (`diff-approval-*`).
- **Approbation (Approval) : shell avec sidebar conservé** (maquette `Approval.dc.html` sans sidebar) — colonne principale **1172 px** au lieu de **1440 px** ; seule la barre haute pleine largeur (`approval-topbar`, Δw = −268) est concernée : colonne centrale (680 px) et rail (349 px) ont la même taille des deux côtés. `MobileApproval.dc.html` n’a pas de sidebar de part et d’autre : aucune exception.
- **Shell canonique : la sidebar de l’app est la référence** (décision tf du **2026-10-10**). `History.dc.html` porte l’ancienne sidebar : elle n’est plus comparée — masque nommé `history-legacy-sidebar` (surface journalisée, ≈ 241 200 px soit 18,6 % de la page 1440×900), motif « décision tf : shell canonique ». Le contenu de la page Historique reste comparé.
- **Maquettes corrigées : boutons en police héritée (IBM Plex)** (décision tf du **2026-10-10**). Chaque `*.dc.html` porte `button, input, select, textarea { font-family: inherit; }` dans son style de base (seule modification des maquettes) ; `serve-mockups.mjs` conserve cette règle quand il retire le `<helmet>`. Le contournement `font-family: revert` (bouton « Révoquer », #68) est retiré de l’app.
- **Historique : jamais d’action « Restaurer » sur la version courante** (desktop et mobile ; `versionRowActions`, test Vitest). En mobile, une seule action par ligne comme `MobileHistory.dc.html` : « Comparer » s’il existe une version précédente, sinon « Restaurer ».
- **Modifier : présence du co-éditeur « YM »** = `NOT_IMPLEMENTED` (`edit-presence-coeditor`, backlog `EDIT-COEDIT-PRESENCE`) — co-édition hors V1.

## Comparaison

- Par **section** (`data-mock-id`), pas page entière floue.
- **Texte inclus** (aucun masquage global des glyphes).
- Viewport desktop **1440×900** ; mobile **390×844** uniquement pour les specs mobiles existants.
- Même Chromium pour maquette (`serve-mockups` :4174) et app (preview :4173).
- États figés de la maquette (menu ouvert, paragraphe en cours d’édition) : reproduits dans l’app par
  **interaction Playwright** (clic, placement du curseur), jamais par modification du DOM.

## Interdits avant capture

- Modifier le DOM ou les styles de l’**app** ou de la **maquette** avant capture  
  (seule l’annotation `data-*` / `data-mock-id` / `data-visual-mask` / `data-visual-ignore` est permise).
- Rendre du texte transparent (`maskGlyphs`, `color: transparent`, etc.).
- Recadrer silencieusement (clips hors sections déclarées).
- Forcer des hauteurs / largeurs / `overflow: hidden` pour « faire rentrer » la page.
- Supprimer des éléments de la maquette (badges, liens, blocs).

## Masques (seule exclusion autorisée)

- Option Playwright `screenshot({ mask, maskColor })` sur un **élément nommé**
  (`data-visual-mask="…"` ou locator ciblé documenté).
- `maskColor` = fond de la section (en pratique `#FFFFFF` sur fond blanc).
- Surface masquée **journalisée** des deux côtés (px et % de la page 1440×900).
- **Échec** si le masque ne trouve aucun élément.

## Tailles

- Tailles PNG différentes → **échec**, sauf entrée dans `SIZE_EXCEPTIONS`
  avec `Δw` / `Δh` **déclarés = mesurés**, puis comparaison sur zone commune
  ancrée en haut à gauche (crop TL), texte inclus, ≤ 1 %.

## Fonctionnalité absente

- Déclarer dans `NOT_IMPLEMENTED` : `id`, surface (px / % page), `reason`, `backlog`.
- **Jamais** comptée comme passée.
- Garde : si la feature apparaît dans l’app (`appImplementedProbe`), le test échoue
  (retirer l’entrée quand elle est livrée).
- Placeholder app optionnel (`appPlaceholderSelector`) pour ancrer la zone « Bientôt ».

## Exceptions structurales

- `box.y` : cascade autorisée uniquement (x / w / h comparés).
- Texte : seulement s’il vient de **données réelles** (API / fixtures AJV), pas de
  copie maquette inventée.
- Seuil pixel : **≤ 1 %** de pixels différents par section (`pixelmatch`, threshold 0.1).

## Fixtures

- Validées AJV contre `openapi/openapi.json` (`fixture-contract.test.ts`).
- Aucune valeur que le serveur ne renvoie pas (pas d’invention de champs backend).

## Écart CSS

- On **corrige l’app** (CSS, markup, libellés). On n’exempte pas.

## Specs hérités

Les specs listés « À nettoyer » dans le tableau **État des specs** violent encore ces règles
(`maskGlyphs`, normalizers, forçage 1440×900, etc.). Les écrans document / historique /
comparaison / approbation / restauration sont passés en méthode honnête (sections + kit) ;
login, dashboard et admin (branding, custom-fields, tags, retention) restent à migrer.
Tout **nouveau** spec doit suivre ce document.
