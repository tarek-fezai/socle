# Parité visuelle — référence pour toutes les PR

Socle doit être **identique à la maquette** (`systeme-documentation-direction-ui/*.dc.html`).
Ce document est la norme pour tout nouveau spec Playwright sous `frontend/e2e-visual/`.

## Comparaison

- Par **section** (`data-mock-id`), pas page entière floue.
- **Texte inclus** (aucun masquage global des glyphes).
- Viewport desktop **1440×900** ; mobile **390×844** uniquement pour les specs mobiles existants.
- Même Chromium pour maquette (`serve-mockups` :4174) et app (preview :4173).

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

Certains specs antérieurs violent encore ces règles (`maskGlyphs`, normalizers,
forçage 1440×900, etc.). Ils sont listés dans l’audit des PR de remédiation ;
tout **nouveau** spec doit suivre ce document.
