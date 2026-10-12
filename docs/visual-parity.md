# Parité visuelle — référence pour toutes les PR

Socle doit être **identique à la maquette** (`systeme-documentation-direction-ui/*.dc.html`).
Ce document est la norme pour tout nouveau spec Playwright sous `frontend/e2e-visual/`.

## État des specs

| État | Specs |
| --- | --- |
| **Propres** (méthode `docs/visual-parity.md` : sections, texte inclus, masques Playwright nommés, `NOT_IMPLEMENTED` / `APP_EXTRA` / `SIZE_EXCEPTIONS` / `RASTER_EXCEPTIONS` déclarés) | `account-admin`, `generate-token`, `spaces`, `search`, `notifications`, `favorites`, `new-document`, `page`, `edit`, `history`, `history-mobile`, `diff`, `diff-approval`, `restore`, `approval`, `approval-mobile`, `login`, `dashboard`, `tags-admin`, `custom-fields-admin`, `retention-admin`, `branding-admin`, `access`, `export`, `export-folder`, `export-tag`, `space-settings`, `trash`, `create-space`, `team`, `invitations`, `invite-org-member` |
| **Sections > 1 % (échec CI, pas de test.fail)** | — |

## Correspondances 2026-10-11 (Access / Export / Corbeille / Création)

- **Access.dc.html** = accès **document** (visibilité + personnes) → `/documents/:id/access`. `/spaces/:id/access` est l’ACL espace (autre UI) : pas de spec supplémentaire. Pas de variante dossier/tag dans la maquette.
- **Export dossier / étiquette** : l’API `exportFolder` / `exportTag` renvoie un **PDF fusionné**. Les maquettes disent « ZIP de PDF » / « Télécharger l’archive (.zip) ». Décision tf **2026-10-11** : l’export ZIP est la cible (PR suivante, backlog `EXPORT-ZIP`). En attendant, sections download / format → `NOT_IMPLEMENTED` ; libellés app restent **PDF** (pas de mensonge ZIP). Word / Markdown → `APP_EXTRA`.
- **Trash.dc.html** = corbeille **d’espace**. Décision tf **2026-10-11** : vue par espace (maquette) **et** `/trash` globale conservée. `GET /api/v1/trash` n’accepte que `resourceType` / `offset` / `limit` (pas de `spaceId`) → vue d’espace `NOT_IMPLEMENTED` (backlog `TRASH-PER-SPACE`). Filtres de type sur `/trash` → `APP_EXTRA`.

### Données manquantes (backend — ne pas inventer côté app)

- `GET /api/v1/trash?spaceId=` (filtré OpenFGA) — backlog `TRASH-PER-SPACE`.
- **SpaceSettings.dc.html** = arbre 420 px + paramètres 620 px ; `/spaces/:id` reprend cette mise en page. `NOT_IMPLEMENTED` : membres, archivage. `APP_EXTRA` (relecteur) : description, politique de commentaires, propriétaires, Enregistrer. Pas d’affichage d’UUID.
- **CreateSpace.dc.html** = modale 480 px ouverte depuis `/spaces` (interaction Playwright). Le bouton × figure dans la maquette : comparé (`create-close`).
- **Accès** : liste des personnes, « Inviter », onglets document et encart corbeille sont livrés (annuaire nominatif). Plus de `ACCESS-PEOPLE-PICKER` / `access-grant-form`.
- **Invitations** : `TeamInvitations.dc.html` → `/admin/team` onglet Invitations ; `InviteOrgMember.dc.html` → modal « + Inviter un membre ». Voir `docs/invitations.md`. Titre / Annuler / Envoyer du modal : lissage LCD maquette vs `:root antialiased` (8 % / 3–4 %, au-dessus du plafond raster 5 % pour le titre) — libellés comparés.

## Décisions produit

- **Spaces : shell avec sidebar conservé** (maquette sans sidebar) — largeur de contenu **1076 px** au lieu de **1344 px** ; décision tf du **2026-10-10**. Les `SIZE_EXCEPTIONS` `spaces-title` / cartes y renvoient explicitement.
- **Comparaison (Diff) : shell avec sidebar conservé** (maquette `Diff.dc.html` sans sidebar) — colonne principale **1172 px** au lieu de **1440 px** ; même décision que Spaces (2026-10-10). Les `SIZE_EXCEPTIONS` `diff-topbar` / `diff-controls` / blocs `@@` (Δw = −268) et côtés ancien / nouveau des lignes (Δw = −134, 50 % de la boîte de diff) y renvoient explicitement.
- **Comparaison d’approbation (DiffApproval) : shell avec sidebar conservé** (maquette `DiffApproval.dc.html` sans sidebar) — mêmes largeurs et mêmes `SIZE_EXCEPTIONS` que Diff (`diff-approval-*`).
- **Approbation (Approval) : shell avec sidebar conservé** (maquette `Approval.dc.html` sans sidebar) — colonne principale **1172 px** au lieu de **1440 px** ; seule la barre haute pleine largeur (`approval-topbar`, Δw = −268) est concernée : colonne centrale (680 px) et rail (349 px) ont la même taille des deux côtés. `MobileApproval.dc.html` n’a pas de sidebar de part et d’autre : aucune exception.
- **Shell canonique : la sidebar de l’app est la référence** (décision tf du **2026-10-10**). `History.dc.html` porte l’ancienne sidebar : elle n’est plus comparée — masque nommé `history-legacy-sidebar` (surface journalisée, ≈ 241 200 px soit 18,6 % de la page 1440×900), motif « décision tf : shell canonique ». Le contenu de la page Historique reste comparé.
- **Maquettes corrigées : boutons en police héritée (IBM Plex)** (décision tf du **2026-10-10**). Chaque `*.dc.html` porte `button, input, select, textarea { font-family: inherit; }` dans son style de base ; `serve-mockups.mjs` conserve cette règle quand il retire le `<helmet>`. Le contournement `font-family: revert` (bouton « Révoquer », #68) est retiré de l’app.
- **Historique : jamais d’action « Restaurer » sur la version courante** (desktop et mobile ; `versionRowActions`, test Vitest). En mobile, une seule action par ligne comme `MobileHistory.dc.html` : « Comparer » s’il existe une version précédente, sinon « Restaurer ».
- **Modifier : présence du co-éditeur « YM »** = `NOT_IMPLEMENTED` (`edit-presence-coeditor`, backlog `EDIT-COEDIT-PRESENCE`) — co-édition hors V1.

### Décisions du 2026-10-11 (DP-2026-10-11, propriétaire tf + relecteur, revue PR #72)

Les specs y renvoient par leur numéro (`DP-2026-10-11 #n`).

1. **« Propulsé par Socle » retiré** du login et de la sidebar (identique maquette). Le nom Socle reste
   dans la licence. L’interrupteur « Masquer la mention » de Personnalisation de marque (présent dans
   la maquette, champ API `hidePoweredBy`) n’a plus d’effet visible.
2. **Texte secondaire `#75757C` au lieu de `#B0B0B5` : contraste WCAG AA** (4,6:1 sur blanc).
   Maquettes du dépôt corrigées **uniquement pour la couleur de texte** (`color:`) : **315 occurrences
   dans 70 fichiers** `*.dc.html`. Les 30 occurrences de bordures, séparateurs, fonds et icônes
   (`stroke` 18, `background` 9, `fill` 3) restent `#B0B0B5` ; l’app les suit. Seules modifications des
   maquettes : celle-ci et la police héritée des boutons (2026-10-10).
3. **Accueil, activité « a proposé une modification »** : une ligne sans titre du document (comme la
   maquette) ; le titre est porté par `title` / `aria-label` et la ligne est un lien vers le document.
4. **Login-error** : liste des causes et libellé du contact identiques à la maquette (cause « conflits de
   provisioning » sans donnée publique → `NOT_IMPLEMENTED`).
5. **Tags : « Exporter » visible au survol / focus de ligne uniquement** (masqué visuellement au repos,
   jamais retiré de l’ordre de tabulation). Le spec reproduit l’état figé par un survol Playwright de la
   ligne 1 et vérifie l’accès clavier.
6. **`APP_EXTRA`** (voir ci-dessous) : constructeur de champs personnalisés (sélection d’espace, statut,
   pastilles ×, « Nouvelle valeur ») ; Personnalisation de marque : **Socle est auto-hébergé, pas de plan
   SaaS à domaine dédié** → URL publique d’instance, encart auto-hébergé et « Envoyer un e-mail de test »
   en `APP_EXTRA` ; domaine dédié et plan Entreprise de la maquette en `NOT_IMPLEMENTED`.
7. **Temps relatif** au format court de la maquette (« il y a 20 min », « il y a 3h », « hier »), calculé
   côté frontend depuis les horodatages API (`formatRelativeFr`, test Vitest).
8. **Une fixture par maquette** (desktop / mobile / menu), toutes validées AJV.
9. **Menu mobile** : aucune propriété de composition au repos sur le tiroir ; l’ombre est peinte sous le
   tiroir (pseudo-élément) pour que son fond reste opaque et que le texte garde l’anticrénelage LCD.
10. **`compareMetrics` : hauteur de ligne comparée en px**, sans tolérance « `normal` ≈ 1,0–1,55 em ».
11. **`collectMetrics` : texte affiché** — accepté (voir Exceptions structurales).
12. **Search** : plus aucune découpe de texte ni nœud créé dans la maquette ; masque par Range.

### Décisions du 2026-10-11 (revue PR #75, propriétaire tf)

13. **Export dossier / étiquette en ZIP** comme la maquette — PR suivante (`EXPORT-ZIP`). En attendant, download / format `NOT_IMPLEMENTED` ; libellés PDF inchangés.
14. **Corbeille par espace** (maquette) **et** `/trash` globale conservée. Sans filtre `spaceId` API : vue d’espace `NOT_IMPLEMENTED` (`TRASH-PER-SPACE`).
15. **Accès** : people picker / Inviter / onglets document livrés (annuaire). Plus de champ UUID. Invitations e-mail : `docs/invitations.md`.
20. **Référence de mesure = CI Linux** (`ubuntu-latest`). Les mesures locales Windows sont **indicatives**. Les gardes « exception inutile / inutilisée sur ce rasterizer » (`RASTER_EXCEPTIONS`, `SUBPIXEL_EXCEPTIONS`) ne s’évaluent **que lorsque `CI` est défini** : un ≤ 1 % Windows ne force pas le retrait d’une entrée encore nécessaire sur Linux.
16. **Une section > seuil fait échouer la CI.** Interdit : `test.fail()`, `test.fixme()`, skip conditionnel pour rendre un écart « attendu ».
17. **Trash.dc.html : « Supprimer définitivement » est un `<button type="button" class="perm-delete">`** (décision tf **2026-10-12**, artifact Design v144). Règle UA `.perm-delete` dans le `<helmet>` ; `serve-mockups.mjs` conserve ce helmet. Confirmation dans la page à la place de `window.confirm` : backlog, ne pas implémenter ici.
18. **CreateSpace.dc.html : CTA restés `<a>`** (hypothèse `<a>` vs `<button>` réfutée : Approval.dc.html Approuver/Refuser sont des `<a>`, l’app passe ≤ 1 % en `<button>` à 14 px). Submit étiré à 37 px (rangée = cancel + bordure) : `display:flex; align-items:flex-start` pour caler le texte sous le padding haut (y=596), comme generate-token #68. Essais CTA : fond opaque, `appearance` UA, `font-smoothing: auto`, même tag `<a>` — Linux reste à 1,940 %.
19. **`RASTER_EXCEPTIONS`** (`create-space-cancel`, `create-space-submit`) : lissage LCD (`<a>` maquette) vs gris (`<button>` app) à géométrie identique. Annuler : 3,164 % Windows / 4,298 % Linux. CTA : 0,987 % Windows / 1,940 % Linux. Seuil ≤ 5 % ; si ≤ 1 % sur un rasterizer l’entrée reste (l’autre OS peut dépasser).

## Comparaison

- Par **section** (`data-mock-id`), pas page entière floue.
- **Texte inclus** (aucun masquage global des glyphes).
- Viewport desktop **1440×900** ; mobile **390×844** uniquement pour les specs mobiles existants.
- Même Chromium pour maquette (`serve-mockups` :4174) et app (preview :4173).
- Captures avec les **mêmes options des deux côtés** : `SCREENSHOT_OPTIONS` du kit
  (`{ caret: 'hide', animations: 'disabled' }`), y compris dans les specs hérités.
- **Aucun retry** (`playwright.config.mjs`, dédiée à `e2e-visual/`) : un écart instable échoue.
- Une section au-dessus du seuil **échoue** : pas de `test.fail` / `test.fixme` / skip pour en faire un succès.
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
- **Masque de texte partiel** (`rangeMasks` de `shotSection`) : quand seule une partie du texte d’un
  élément existant doit être exclue, ses rectangles sont mesurés par un `Range` (lecture seule, aucun
  nœud créé, aucun texte découpé) puis peints en `maskColor` dans le PNG après capture. Surface
  journalisée ; **échec** si le texte est introuvable.

## Élément en plus dans l’app (`APP_EXTRA`)

- Élément présent dans l’app, **absent de la maquette par décision produit** : `id`, `appMask` (ou
  `appRange`), `reason`, `decision` (référence `DP-…`).
- Masque **côté app uniquement**, surface chiffrée (px / % page) et journalisée.
- Garde : si l’élément **disparaît** de l’app, le test échoue (retirer l’entrée et le masque).
- L’élément est placé de façon à ne pas décaler les éléments de la maquette ; sinon la section reste
  en échec.
- Une zone couverte en entier des deux côtés (`NOT_IMPLEMENTED` maquette + `APP_EXTRA` app) n’est pas
  comparée en pixel : ses deux surfaces sont journalisées.

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

## Lissage LCD vs gris (`RASTER_EXCEPTIONS`)

Un `<a>` maquette et un `<button>` app de **même boîte et même Range de texte** peuvent encore différer
par le mode de lissage (franges LCD vs niveaux de gris), sans calque de composition propre.

```js
RASTER_EXCEPTIONS[sectionId] = { maxPixel: 0.05, reason }
```

- `maxPixel` **≤ 0,05** (5 %) ; `reason` obligatoire.
- Appliquée **seulement si les trois conditions sont mesurées** par le test (sinon seuil 1 %) :
  1. boîte et rectangle du nœud texte identiques au 1/100 px ;
  2. styles calculés de police identiques (famille — premier nom —, taille, graisse,
     `letter-spacing`, `line-height`, couleur) ;
  3. aucun calque propre LayerTree des deux côtés (`ownLayers` vide).
- Si la section ≤ 1 % **sur CI Linux** : l’entrée est inutile → **échec**, la retirer.
- En local (Windows) : ≤ 1 % avec une exception déclarée = succès indicatif ; la garde « inutile » n’est pas évaluée.

| Section | Motif |
|---|---|
| `create-space-cancel` | LCD (`<a>` maquette) vs gris (`<button>` app), géométrie identique |
| `create-space-submit` | idem, 13 px ; 1,940 % Linux / 0,987 % Windows |

## Exceptions structurales

- `box.y` : cascade autorisée uniquement (x / w / h comparés).
- **Texte affiché** (`collectMetrics`) : texte normalisé des seuls nœuds rendus — exclus : éléments en
  `display: none` et éléments masqués visuellement (1×1 px, `overflow: hidden`), comme l’action
  « Exporter » révélée au survol.
- **Hauteur de ligne en px** : valeur calculée ; `normal` est mesuré (canvas détaché, métriques de la
  police) puis comparé strictement, sans tolérance.
- Texte : seulement s’il vient de **données réelles** (API / fixtures AJV), pas de
  copie maquette inventée.
- Seuil pixel : **≤ 1 %** de pixels différents par section (`pixelmatch`, threshold 0.1).

## Fixtures

- Validées AJV contre `openapi/openapi.json` (`fixture-contract.test.ts`).
- Aucune valeur que le serveur ne renvoie pas (pas d’invention de champs backend).

## Écart CSS

- On **corrige l’app** (CSS, markup, libellés). On n’exempte pas.

## Specs hérités

Tous les specs `e2e-visual` suivent ce document (kit, texte inclus, masques nommés, `APP_EXTRA`).
Tout **nouveau** spec doit le suivre.
