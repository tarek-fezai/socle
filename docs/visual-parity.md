# Parité visuelle — référence pour toutes les PR

Socle doit être **identique à la maquette** (`systeme-documentation-direction-ui/*.dc.html`).
Ce document est la norme pour tout nouveau spec Playwright sous `frontend/e2e-visual/`.

## État des specs

| État | Specs |
| --- | --- |
| **Propres** (méthode `docs/visual-parity.md` : sections, texte inclus, masques Playwright nommés, `NOT_IMPLEMENTED` / `SIZE_EXCEPTIONS` déclarés) | `account-admin`, `generate-token`, `spaces`, `notifications`, `favorites`, `new-document`, `page`, `edit`, `history`, `history-mobile`, `diff`, `diff-approval`, `restore`, `approval`, `approval-mobile`, `login`, `dashboard`, `tags-admin`, `custom-fields-admin`, `retention-admin`, `branding-admin` |
| **À nettoyer** (mutation DOM de la maquette avant capture) | `search` : `annotateSearchMockup` scinde le nœud texte « · mentionne … » et l’enveloppe dans un `<span>` créé pour le masquer |

Les specs `login`, `dashboard`, `tags-admin`, `custom-fields-admin` et `branding-admin` sont propres mais
**échouent** : sections encore > 1 % (écarts de conception en attente de décision, anticrénelage du
tiroir mobile). Aucune mutation, aucun masque ajouté pour les faire passer.

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
- Captures avec les **mêmes options des deux côtés** : `SCREENSHOT_OPTIONS` du kit
  (`{ caret: 'hide', animations: 'disabled' }`), y compris dans les specs hérités.
- **Aucun retry** (`playwright.config.mjs`, dédiée à `e2e-visual/`) : un écart instable échoue.
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

## Sous-pixel (`SUBPIXEL_EXCEPTIONS`)

Un bloc `NOT_IMPLEMENTED` de hauteur fractionnaire au-dessus d’une section (SVG draw.io 243,55 px,
bloc co-édition 152,34 px) décale la **fraction** de `y` de la maquette : les glyphes sont alors
rastérisés sur une autre phase de pixel, à texte et boîte identiques.

```js
SUBPIXEL_EXCEPTIONS[sectionId] = { upstreamNotImplemented: '<id NOT_IMPLEMENTED>', maxPixel: 0.08, reason }
```

- `maxPixel` **≤ 0,08** (8 %) ; `reason` et `upstreamNotImplemented` obligatoires.
- Appliquée **seulement si les trois conditions sont mesurées** par le test (sinon seuil 1 %) :
  1. correspondance structurelle : texte normalisé, `font-family`, `font-size`, `font-weight`,
     `line-height`, `box.w` et `box.h` (± 0,01 px) identiques maquette / app ;
  2. `|frac(y_maquette) − frac(y_app)| ≥ 0,25 px` (distance circulaire des fractions) ;
  3. la zone amont existe dans `NOT_IMPLEMENTED` et se situe **au-dessus** de la section dans la maquette.
- Garde : si la zone amont est retirée de `NOT_IMPLEMENTED`, le test **échoue** tant que l’entrée existe.
- Une entrée **inutile** (section ≤ 1 % sans elle) fait échouer le test : la retirer.
- Journal : `y` maquette / app au 1/100 px, fractions, `|Δfrac|`, bas et hauteur de la zone amont, %
  obtenu, appliquée ou non (avec la condition manquante).

| Section | Zone amont `NOT_IMPLEMENTED` |
|---|---|
| `page-desktop-h2-flux`, `page-desktop-p-flux` | `page-drawio` (Fig. 1 draw.io) |
| `edit-desktop-h2-principe`, `edit-desktop-p-editing` | `edit-lead-coedit` (bloc co-édition) |

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

Login, dashboard et admin (branding, custom-fields, tags, retention) sont migrés sur le kit :
plus de `maskGlyphs` / `maskAdminGlyphs`, plus de retrait d’éléments ni de `display: none`, plus
de forçage de `line-height` dans `collectMetrics`. Le tableau **État des specs** liste les specs
qui violent encore ces règles.
Tout **nouveau** spec doit suivre ce document.
