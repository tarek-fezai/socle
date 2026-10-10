// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
/**
 * Règles : docs/visual-parity.md
 *
 * Écran Modifier /docs/:id/edit vs Edit.dc.html (desktop 1440×900).
 *
 * Pixel honnête (texte inclus, ≤ 1 % par section) ; seul exclu : `mask` Playwright nommé
 * (`data-visual-mask`). Annotation maquette ET app : attributs `data-*` uniquement
 * (aucun style / DOM modifié avant capture — plus de normalisation de la maquette).
 *
 * ── Périmètre ─────────────────────────────────────────────────────────────────────────────
 *  - Colonne principale + assistant (x ≥ 268) : la barre latérale est celle du shell (cf. Dashboard /
 *    Page).
 *  - Pas de maquette mobile pour l'écran Modifier : aucun test mobile.
 *  - Les blocs sans équivalent app (co-édition, poignées, aide « réduire », puces inline, en-tête de bloc
 *    de code, indicateur de dépôt, aperçu de lien réduit, encadré Note, liste de tâches, carte glossaire)
 *    sont des zones NOT_IMPLEMENTED : masque nommé côté maquette, jamais comptées comme réussies.
 *  - Utilisateur courant de la fixture : « Camille Durand » (initiales CD, comme l'avatar de la maquette).
 *    Le seul détenteur du verrou est cet utilisateur ; la maquette ajoute un second avatar (YM).
 *  - Le menu « Insérer » est ouvert dans la maquette (état figé, en flux) : il est ouvert dans l'app par
 *    un clic avant sa capture (interaction utilisateur, pas une mutation du DOM) — mesuré en dernier car il
 *    recouvre la colonne dans l'app (popover) alors qu'il pousse le contenu dans la maquette.
 *
 * ── Fixture applicative (edit-fixtures.mjs) ──────────────────────────────────────────────
 *  - Corps limité aux nœuds du StarterKit (+ un bloc `image` et un bloc `attachment` en fin de corps) ;
 *    paragraphes de remplissage sous la ligne de flottaison pour afficher « 1 240 mots · 6 min de lecture ».
 *  - Seuil « paragraphe long » du serveur calé entre le chapeau et le paragraphe « en cours d'édition ».
 *
 * ── Écarts de conception assumés (non masqués — voir `.tmp-design-gaps-page-edit.md`) ───────
 *  E1. Barre d'outils : fonctions manquantes rendues désactivées (`aria-disabled`, « Bientôt disponible »).
 *  E2. Menu « Insérer » : popover (maquette : panneau en flux), entrées actives/« bientôt » différentes.
 *  E3. Propriétaire : « Équipe {nom de l'espace} » (maquette : « Équipe Identité »).
 *  E4. Carte « Paragraphe long » : message calculé + bouton « Aller au paragraphe ».
 *  E6. Champs personnalisés : le 1er champ de la maquette est en IBM Plex Mono.
 *  E7. Les contrôles de la barre haute sont des <button> (la maquette : <a>).
 *  E8. Titre : <textarea> auto-dimensionné (la maquette : <h1 contenteditable>).
 *  E11. Barre haute : bouton « Enregistrer la version » absent de Edit.dc.html — décale présence / Aperçu /
 *       « Envoyer en révision » et comprime le fil d'Ariane.
 *
 * Mesure avant polish : `VISUAL_BASELINE=1 npx playwright test e2e-visual/edit-visual.spec.mjs`
 * (journalise tous les % / tailles, écrit test-results/edit-before.json, n'échoue pas).
 */
import { test, expect } from '@playwright/test'
import {
  EDIT_DESKTOP_IDS,
  annotateEditMockup,
  assertFontsLoaded,
  collectMetrics,
  compareMetrics,
} from './structural-compare.mjs'
import {
  EDIT_APPLICABLE_WORKFLOW,
  EDIT_CUSTOM_FIELDS,
  EDIT_DOC_ID,
  EDIT_WRITING_HINTS,
  ME_TAREK,
  PAGE_COMMENTS,
  SPACE_IDENTITE,
  TREE_IDENTITE,
  VISUAL_NOW,
  editDocument,
  editLockMine,
} from './edit-fixtures.mjs'
import { ATTACHMENT_FILE_ID, ATTACHMENT_IMAGE_ID, mockAttachmentRoutes } from './page-fixtures.mjs'
import { FAVORITES_SEED, NOTIFICATIONS_SEED, SPACE_INFRA, TREE_INFRA } from './dashboard-fixtures.mjs'
import {
  AUTH_CONFIG,
  MOCK_ORIGIN,
  assertNotImplementedGuards,
  compareSectionShots,
  expectNoStructuralDiffs,
  finishPixelResults,
  injectOidcSession,
  json,
  mockBaseApis,
  reportNotImplemented,
  settleFonts,
  shotSection,
} from './visual-parity-kit.mjs'

const MAC_UA =
  'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36'

/** Même rendu des deux côtés (couches composées de l'app vs maquette) : texte comparé pixel à pixel. */
test.use({
  timezoneId: 'Europe/Paris',
  userAgent: MAC_UA,
  launchOptions: { args: ['--disable-lcd-text'] },
})

/** Utilisateur courant de la maquette Edit : « CD » (Camille Durand) ; YM est un co-éditeur. */
const ME_EDIT = { ...ME_TAREK, displayName: 'Camille Durand', givenName: 'Camille', avatarInitials: 'CD' }

/**
 * Zones maquette sans équivalent app — NON comparées, jamais « passées ».
 * @type {import('./visual-parity-kit.mjs').NotImplementedZone[]}
 */
export const NOT_IMPLEMENTED = [
  {
    id: 'edit-coedit-banner',
    page: 'edit',
    reason: 'Bandeau « Yanis M. modifie également ce document » : co-édition temps réel hors V1 (verrou exclusif seul)',
    backlog: 'EDIT-COEDIT (hors V1)',
    mockMask: 'edit-coedit-banner',
    appImplementedProbe: '[data-testid="edit-coedit-banner"]',
  },
  {
    id: 'edit-presence-coeditor',
    page: 'edit',
    reason: 'Avatar de présence « YM » (second éditeur) : co-édition hors V1',
    backlog: 'EDIT-COEDIT (hors V1)',
    mockMask: 'edit-presence-coeditor',
    appImplementedProbe: '[data-testid="edit-avatar-coeditor"]',
  },
  {
    id: 'edit-collapse-hint',
    page: 'edit',
    reason: 'Texte d’aide « Un bloc réduit reste dans le document… » : blocs enrichis réductibles non disponibles',
    backlog: 'EDIT-COLLAPSE-BLOCKS',
    mockMask: 'edit-collapse-hint',
  },
  {
    id: 'edit-lead-coedit',
    page: 'edit',
    reason:
      'Chapeau : bulle de sélection flottante, surlignage de sélection, étiquette « Yanis modifie ici » et curseur distant (co-édition)',
    backlog: 'EDIT-COEDIT (hors V1)',
    mockMask: 'edit-lead-coedit',
  },
  {
    id: 'edit-selection-bubble',
    page: 'edit',
    reason: 'Bulle de formatage flottante sur la sélection partagée (co-édition) — recouvre le titre dans la maquette',
    backlog: 'EDIT-COEDIT (hors V1)',
    mockMask: 'edit-selection-bubble',
  },
  {
    id: 'edit-block-handles',
    page: 'edit',
    reason: 'Poignées de bloc visibles (+ / glisser / réduire) : glisser-déposer et blocs réduits hors V1',
    backlog: 'EDIT-DRAG-DROP',
    mockMask: 'edit-block-handles',
  },
  {
    id: 'edit-toolbar-soon',
    page: 'edit',
    reason:
      'Boutons de barre d’outils sans fonction (couleur/surlignage, liste de tâches, retraits, alignements, draw.io, @mention, réduire les blocs) : rendus désactivés « Bientôt disponible » dans l’app',
    backlog: 'EDIT-TOOLBAR-SOON',
    mockMask: 'edit-toolbar-soon',
    appPlaceholderSelector: '.edit-toolbar [data-soon="true"]',
  },
  {
    id: 'edit-insert-soon',
    page: 'edit',
    reason: 'Entrées du menu « Insérer » sans fonction (Transclure une page, Schéma) : rendues désactivées « Bientôt disponible »',
    backlog: 'EDIT-INSERT-SOON',
    mockMask: 'edit-insert-soon',
  },
  {
    id: 'edit-inline-chips',
    page: 'edit',
    reason: 'Paragraphe à puces inline (carte Jira IAM-482, date 15 octobre 2026) : mentions / dates inline hors V1',
    backlog: 'EDIT-INLINE-NODES',
    mockMask: 'edit-inline-chips',
  },
  {
    id: 'edit-code-header',
    page: 'edit',
    reason:
      'Bloc de code : en-tête « bash · script de revue des accès / Copier » et état « déplacement en cours » (opacité) — le bloc de code TipTap n’a ni langue ni bouton Copier, ni glisser-déposer',
    backlog: 'EDIT-CODEBLOCK-HEADER',
    mockMask: 'edit-code-header',
  },
  {
    id: 'edit-drop-line',
    page: 'edit',
    reason: 'Indicateur de dépôt (glisser-déposer de blocs) hors V1',
    backlog: 'EDIT-DRAG-DROP',
    mockMask: 'edit-drop-line',
  },
  {
    id: 'edit-link-preview-collapsed',
    page: 'edit',
    reason: 'Aperçu de lien réduit (bloc enrichi réduit en une ligne) : réduction de blocs hors V1',
    backlog: 'EDIT-COLLAPSE-BLOCKS',
    mockMask: 'edit-link-preview-collapsed',
  },
  {
    id: 'edit-callout-note',
    page: 'edit',
    reason: 'Encadré « Note » (callout) : nœud absent de l’éditeur',
    backlog: 'EDIT-CALLOUT',
    mockMask: 'edit-callout-note',
  },
  {
    id: 'edit-tasklist',
    page: 'edit',
    reason: 'Liste de tâches (cases à cocher) : nœud absent de l’éditeur',
    backlog: 'EDIT-TASKLIST',
    mockMask: 'edit-tasklist',
  },
  {
    id: 'edit-glossary-card',
    page: 'edit',
    reason: 'Carte « Terme non conforme » (règle de glossaire) : règle pas encore livrée côté assistant',
    backlog: 'EDIT-GLOSSARY-RULE',
    mockMask: 'edit-glossary-card',
  },
]

/** Écarts mesurés (app − maquette) déclarés. */
const SIZE_EXCEPTIONS = {
  'edit-desktop-p-editing': {
    dw: 0,
    dh: -4,
    reason:
      'la maquette montre le paragraphe en état « en cours d’édition » (padding + contour pointillé, caret factice) — état de focus transitoire ; le texte est comparé (zone commune en haut à gauche)',
  },
}

/* ------------------------------------------------------------------ */
/* Sections                                                           */
/* ------------------------------------------------------------------ */

/** id = data-mock-id posé par `annotateSections*` (maquette et app). `lead` / `chips` / `code` : NOT_IMPLEMENTED. */
const DESKTOP_SECTIONS = [
  { id: 'ed-meta', name: 'topbar-meta' },
  // L'avatar « YM » (co-éditeur) chevauche le bord gauche de « CD » dans la maquette (masque = carré, non appliqué ici).
  { id: 'ed-presence', name: 'presence' },
  { id: 'ed-preview', name: 'preview' },
  { id: 'ed-send-review', name: 'send-review' },
  { id: 'ed-tabs', name: 'tabs' },
  { id: 'ed-toolbar', name: 'toolbar', masks: ['edit-toolbar-soon'] },
  {
    id: 'ed-title',
    name: 'title',
    // Bulle de formatage flottante (co-édition) : absolue dans la maquette, recouvre le titre.
    mockTargeted: [
      { name: 'edit-selection-bubble', selector: '[data-visual-mask="edit-selection-bubble"]', reason: 'bulle de sélection partagée (NOT_IMPLEMENTED)' },
    ],
  },
  // Nœuds ProseMirror : ProseMirror retire les attributs posés de l'extérieur (observer DOM) → sélecteurs
  // CSS côté app (côté maquette : data-mock-id).
  { id: 'ed-h2-principe', name: 'h2-principe', appSelector: '.ProseMirror > :nth-child(2)' },
  { id: 'ed-p-editing', name: 'p-editing', appSelector: '.ProseMirror > :nth-child(3)' },
  { id: 'ed-h2-cycle', name: 'h2-cycle', appSelector: '.ProseMirror > :nth-child(6)' },
  { id: 'ed-ol', name: 'ol', appSelector: '.ProseMirror > :nth-child(7)' },
  { id: 'ed-hr', name: 'hr', appSelector: '.ProseMirror > :nth-child(8)' },
  { id: 'ed-assistant-label', name: 'assistant-label' },
  { id: 'ed-card-long', name: 'card-long' },
  { id: 'ed-card-link', name: 'card-link' },
  { id: 'ed-meta-block', name: 'meta-block' },
]
/** Menu « Insérer » : mesuré après ouverture dans l'app (voir en-tête). */
const INSERT_SECTION = { id: 'ed-insert-menu', name: 'insert-menu', masks: ['edit-insert-soon'] }

/* ------------------------------------------------------------------ */
/* Annotation (data-* uniquement)                                      */
/* ------------------------------------------------------------------ */

/** Edit.dc.html — sections pixel + masques NOT_IMPLEMENTED (structure d'origine, accès par index). */
async function annotateSectionsMock(page, soonRects, insertSoonRects) {
  await page.evaluate(({ soon, insertSoon }) => {
    const set = (el, id) => el?.setAttribute('data-mock-id', id)
    const mask = (el, name) => el?.setAttribute('data-visual-mask', name)
    const root = document.querySelector('body div[style*="1440px"]') || document.body.firstElementChild
    const main = root.children[1]
    const [topbar, banner, tabs, row] = [...main.children]
    mask(banner, 'edit-coedit-banner')
    set(topbar.children[0], 'ed-meta')
    const actions = topbar.children[1]
    const presence = actions.children[0]
    mask(presence.children[0], 'edit-presence-coeditor')
    set(presence.children[1], 'ed-presence')
    set(actions.children[1], 'ed-preview')
    set(actions.children[2], 'ed-send-review')
    set(tabs, 'ed-tabs')

    const col = row.children[0].children[0]
    const k = [...col.children]
    set(k[0], 'ed-toolbar')
    mask(k[1], 'edit-collapse-hint')
    set(k[2], 'ed-insert-menu')
    set(k[3], 'ed-title')
    mask(k[4], 'edit-lead-coedit')
    mask(k[4].querySelector('p > span > span:nth-child(2)'), 'edit-selection-bubble')
    set(k[5], 'ed-h2-principe')
    set(k[6], 'ed-p-editing')
    mask(k[7], 'edit-inline-chips')
    mask(k[8], 'edit-code-header')
    mask(k[9], 'edit-drop-line')
    mask(k[10], 'edit-link-preview-collapsed')
    mask(k[11], 'edit-callout-note')
    set(k[12], 'ed-h2-cycle')
    set(k[13], 'ed-ol')
    mask(k[14], 'edit-tasklist')
    set(k[15], 'ed-hr')

    const panel = row.children[1]
    set(panel.children[0], 'ed-assistant-label')
    set(panel.children[1], 'ed-card-long')
    set(panel.children[2], 'ed-card-link')
    mask(panel.children[3], 'edit-glossary-card')
    set(panel.children[4], 'ed-meta-block')

    // Poignées de bloc réellement visibles (le reste est masqué par le CSS de la maquette).
    for (const h of document.querySelectorAll('.block-handles')) {
      const cs = getComputedStyle(h)
      if (cs.display !== 'none' && cs.visibility !== 'hidden' && Number(cs.opacity) > 0) mask(h, 'edit-block-handles')
    }

    // Boutons de barre d'outils « bientôt » de l'app : mêmes cases (x, y relatifs à la barre) dans la maquette.
    const tb = k[0].getBoundingClientRect()
    for (const b of k[0].querySelectorAll('button')) {
      const r = b.getBoundingClientRect()
      const x = r.x - tb.x
      const y = r.y - tb.y
      if (soon.some((s) => Math.abs(s.x - x) < 1.5 && Math.abs(s.y - y) < 1.5)) mask(b, 'edit-toolbar-soon')
    }

    // Entrées « bientôt » du menu Insérer : même case (x, y, w, h relatifs au panneau) que dans l'app.
    const panelBox = k[2].getBoundingClientRect()
    for (const el of k[2].querySelectorAll('*')) {
      const r = el.getBoundingClientRect()
      const x = r.x - panelBox.x
      const y = r.y - panelBox.y
      const hit = insertSoon.some(
        (s) =>
          Math.abs(s.x - x) < 1.5 &&
          Math.abs(s.y - y) < 1.5 &&
          Math.abs(s.w - r.width) < 1.5 &&
          Math.abs(s.h - r.height) < 1.5,
      )
      if (hit && !el.parentElement?.closest('[data-visual-mask="edit-insert-soon"]')) mask(el, 'edit-insert-soon')
    }
  }, { soon: soonRects, insertSoon: insertSoonRects })
}

/**
 * App /docs/:id/edit — mêmes ids de section (data-* uniquement).
 * @returns {Promise<{x:number,y:number}[]>} cases (relatives à la barre d'outils) des boutons « bientôt ».
 */
async function annotateSectionsApp(page) {
  return page.evaluate(() => {
    const set = (el, id) => el?.setAttribute('data-mock-id', id)
    set(document.querySelector('.edit-topbar .edit-meta'), 'ed-meta')
    set(document.querySelector('.edit-presence .edit-avatar'), 'ed-presence')
    set(document.querySelector('[data-mock-id="edit-preview"]'), 'ed-preview')
    set(document.querySelector('[data-mock-id="edit-send-review"]'), 'ed-send-review')
    set(document.querySelector('.doc-tabs'), 'ed-tabs')
    set(document.querySelector('.edit-toolbar'), 'ed-toolbar')
    set(document.querySelector('[data-testid="edit-title"]'), 'ed-title')
    // Corps ProseMirror : 1 chapeau · 2 h2 · 3 paragraphe en cours d'édition · 4 chantier · 5 code · 6 h2 · 7 ol · 8 hr
    // (sélectionnés par `appSelector`, cf. DESKTOP_SECTIONS).
    const assistant = document.querySelector('[data-testid="edit-assistant"]')
    set(assistant.querySelector('.edit-panel-label'), 'ed-assistant-label')
    const cards = assistant.querySelectorAll('.edit-card')
    set(cards[0], 'ed-card-long')
    set(cards[1], 'ed-card-link')
    set(document.querySelector('[data-testid="edit-meta"]'), 'ed-meta-block')

    const tb = document.querySelector('.edit-toolbar').getBoundingClientRect()
    return [...document.querySelectorAll('.edit-toolbar [data-soon="true"]')].map((b) => {
      b.setAttribute('data-visual-mask', 'edit-toolbar-soon')
      const r = b.getBoundingClientRect()
      return { x: r.x - tb.x, y: r.y - tb.y }
    })
  })
}

/* ------------------------------------------------------------------ */
/* Mocks API                                                           */
/* ------------------------------------------------------------------ */

/**
 * @param {import('@playwright/test').Page} page
 * @param {{ lockHolder?: string|null, putCalls?: Array<Record<string, unknown>>, draftPutCalls?: Array<Record<string, unknown>>, tags?: unknown[], me?: typeof ME_TAREK }} [opts]
 */
async function mockEditApis(
  page,
  { lockHolder = null, putCalls = [], draftPutCalls = [], tags, me = ME_EDIT } = {},
) {
  const doc = tags ? { ...editDocument(), tags } : editDocument()
  await mockBaseApis(page, { me, notifications: NOTIFICATIONS_SEED, spaces: [SPACE_IDENTITE, SPACE_INFRA] })
  // Brouillon autosave : aucun brouillon à l'ouverture (404) ; PUT = écho + updatedAt.
  await page.route(`**/api/v1/documents/${EDIT_DOC_ID}/draft`, (route) => {
    const req = route.request()
    if (req.method() === 'PUT') {
      const payload = req.postDataJSON()
      draftPutCalls.push(payload)
      return json(route, { ...payload, updatedAt: new Date(VISUAL_NOW + 60_000).toISOString() })
    }
    if (req.method() === 'DELETE') return route.fulfill({ status: 204 })
    return route.fulfill({ status: 404, contentType: 'application/json', body: '{}' })
  })
  await page.route('**/api/v1/public/auth-config', (route) => json(route, AUTH_CONFIG))
  await mockAttachmentRoutes(page)
  await page.route(`**/api/v1/documents/${EDIT_DOC_ID}`, (route) => {
    const req = route.request()
    if (req.method() === 'PUT') {
      const payload = req.postDataJSON()
      putCalls.push(payload)
      return json(route, {
        ...doc,
        title: payload.title,
        body: payload.body,
        currentVersionNo: (doc.currentVersionNo ?? 1) + putCalls.length,
        updatedAt: new Date(VISUAL_NOW + 60_000).toISOString(),
      })
    }
    return json(route, doc)
  })
  await page.route(`**/api/v1/documents/${EDIT_DOC_ID}/edit-lock`, (route) => {
    if (route.request().method() === 'DELETE') return route.fulfill({ status: 204 })
    if (lockHolder) {
      return json(route, {
        active: true,
        holderUserId: 'u-other',
        holderDisplayName: lockHolder,
        acquiredAt: new Date(VISUAL_NOW - 9 * 60_000).toISOString(),
        heartbeatAt: new Date(VISUAL_NOW).toISOString(),
        heldByCurrentUser: false,
        ttlSeconds: 45,
        heartbeatSeconds: 15,
      })
    }
    return json(route, editLockMine())
  })
  await page.route(`**/api/v1/documents/${EDIT_DOC_ID}/writing-assistant`, (route) => json(route, EDIT_WRITING_HINTS))
  await page.route(`**/api/v1/documents/${EDIT_DOC_ID}/custom-fields`, (route) => json(route, EDIT_CUSTOM_FIELDS))
  await page.route(`**/api/v1/documents/${EDIT_DOC_ID}/comments**`, (route) => json(route, PAGE_COMMENTS))
  await page.route(`**/api/v1/documents/${EDIT_DOC_ID}/approvals/current`, (route) => route.fulfill({ status: 204 }))
  await page.route(`**/api/v1/documents/${EDIT_DOC_ID}/approvals/applicable-workflow`, (route) =>
    json(route, EDIT_APPLICABLE_WORKFLOW),
  )
  await page.route('**/api/v1/tags**', (route) => json(route, []))
  await page.route('**/api/v1/favorites/**', (route) => json(route, { favorited: false }))
  await page.route('**/api/v1/favorites', (route) => json(route, FAVORITES_SEED))
  await page.route(`**/api/v1/spaces/${SPACE_IDENTITE.id}`, (route) => json(route, SPACE_IDENTITE))
  await page.route(`**/api/v1/spaces/${SPACE_IDENTITE.id}/tree**`, (route) => json(route, TREE_IDENTITE))
  await page.route(`**/api/v1/spaces/${SPACE_INFRA.id}/tree**`, (route) => json(route, TREE_INFRA))
  await page.route('**/api/v1/search**', (route) => json(route, { query: '', results: [], total: 0 }))
}

async function prep(page, opts) {
  await injectOidcSession(page, VISUAL_NOW)
  await page.addInitScript(() => {
    localStorage.setItem(
      'socle.shell.expandedSpaces',
      JSON.stringify(['s0000001-0000-4000-8000-000000000001', 's0000001-0000-4000-8000-000000000002']),
    )
  })
  await mockEditApis(page, opts)
}

/** Attend que l'écran soit complet (titre, assistant, métadonnées, compteur de commentaires). */
async function waitEditReady(page) {
  await page.waitForSelector('[data-mock-id="edit-title"]')
  await page.waitForSelector('[data-mock-id="edit-card-link"]')
  await page.waitForSelector('[data-mock-id="edit-meta-custom-field-2"]')
  await page.waitForSelector('[data-mock-id="edit-meta-tag"]')
  await page.waitForFunction(() => /3/.test(document.querySelector('[data-mock-id="edit-tabs"]')?.textContent ?? ''))
  // « Titre 2 » dans le menu de style : curseur dans le premier H2
  await page.click('.ProseMirror h2')
  await page.evaluate(() => {
    if (document.activeElement instanceof HTMLElement) document.activeElement.blur()
  })
  await page.mouse.move(1, 1)
}

/* ------------------------------------------------------------------ */
/* Pixel                                                              */
/* ------------------------------------------------------------------ */

async function shoot(page, section, side) {
  const selector = side === 'app' && section.appSelector ? section.appSelector : `[data-mock-id="${section.id}"]`
  return shotSection(page, selector, {
    maskNames: section.masks ?? [],
    targeted: side === 'mock' ? (section.mockTargeted ?? []) : [],
    label: `edit-desktop-sec-${section.name}-${side}`,
  })
}

test.describe('document edit visual parity', () => {
  test('desktop Edit vs Edit.dc.html — sections pixel ≤ 1 % (texte inclus)', async ({ page }, testInfo) => {
    await prep(page)
    await page.setViewportSize({ width: 1440, height: 900 })

    // App d'abord : les boutons « bientôt » de la barre d'outils fixent les cases masquées côté maquette.
    await page.goto(`/docs/${EDIT_DOC_ID}/edit`)
    await waitEditReady(page)
    const soonRects = await annotateSectionsApp(page)
    await settleFonts(page)
    await assertNotImplementedGuards(page, NOT_IMPLEMENTED, 'edit')
    const appSec = {}
    for (const s of DESKTOP_SECTIONS) {
      await page.mouse.move(1, 1)
      appSec[s.name] = await shoot(page, s, 'app')
    }
    // Menu « Insérer » : ouvert par clic (état figé ouvert dans la maquette) — en dernier, il recouvre la colonne.
    await page.getByTestId('edit-insert-btn').click()
    await page.waitForSelector('[data-testid="edit-insert-panel"]')
    const insertSoonRects = await page.evaluate(() => {
      const panel = document.querySelector('[data-testid="edit-insert-panel"]')
      panel.setAttribute('data-mock-id', 'ed-insert-menu')
      const pb = panel.getBoundingClientRect()
      return [...panel.querySelectorAll('[data-soon="true"]')].map((b) => {
        b.setAttribute('data-visual-mask', 'edit-insert-soon')
        const r = b.getBoundingClientRect()
        return { x: r.x - pb.x, y: r.y - pb.y, w: r.width, h: r.height }
      })
    })
    await page.mouse.move(1, 1)
    appSec[INSERT_SECTION.name] = await shoot(page, INSERT_SECTION, 'app')

    await page.goto(`${MOCK_ORIGIN}/Edit.dc.html`)
    await annotateSectionsMock(page, soonRects, insertSoonRects)
    await settleFonts(page)
    await reportNotImplemented(page, testInfo, NOT_IMPLEMENTED, 'edit')
    const mockSec = {}
    for (const s of [...DESKTOP_SECTIONS, INSERT_SECTION]) mockSec[s.name] = await shoot(page, s, 'mock')

    const compare = compareSectionShots(SIZE_EXCEPTIONS)
    const results = []
    for (const s of [...DESKTOP_SECTIONS, INSERT_SECTION]) {
      results.push(compare(`edit-desktop-${s.name}`, mockSec[s.name].shot, appSec[s.name].shot, testInfo))
    }

    finishPixelResults('edit', results)
  })
})

/* ------------------------------------------------------------------ */
/* Structurel                                                         */
/* ------------------------------------------------------------------ */

test.describe('document edit structural', () => {
  const BANNER_Y =
    'box.y : la maquette porte le bandeau de co-édition « Yanis M. modifie également ce document » (53 px, NOT_IMPLEMENTED edit-coedit-banner) au-dessus ; x / w / h comparés.'
  const desktopExceptions = {
    'edit-toolbar': {
      skip: ['text', 'box.y'],
      reason: `E1 : libellés agrégés (icônes SVG + texte) — contrôles comparés par le pixel. ${BANNER_Y}`,
    },
    'edit-tabs': {
      skip: ['text', 'box.y'],
      reason: `Espaces de mise en forme du source de la maquette entre onglets (textContent) ; libellés identiques. ${BANNER_Y}`,
    },
    'edit-title': {
      skip: ['box.y'],
      reason:
        'box.y : la maquette pousse le titre de ~508 px (bandeau de co-édition + aide « réduire » + menu « Insérer » ouvert en flux) ; l’app ouvre « Insérer » en popover. x / w / h comparés.',
    },
    'edit-assistant': {
      skip: ['text', 'box'],
      reason: `Conteneur — textes comparés sur les enfants annotés ; carte glossaire NOT_IMPLEMENTED absente de l’app. box : y décalé (${BANNER_Y}) et hauteur = reste de la fenêtre (900 − y) ; x / w comparés par le pixel (sections).`,
    },
    'edit-assistant-label': { skip: ['box.y'], reason: BANNER_Y },
    'edit-card-link': {
      skip: ['text', 'box.y'],
      reason: `Idem : retours à la ligne du source entre titre et message ; libellés identiques. ${BANNER_Y}`,
    },
    'edit-card-link-text': { skip: ['box.y'], reason: BANNER_Y },
    'edit-card-long-text': {
      skip: ['text', 'box.y'],
      reason: `E4 : message calculé (« Ce paragraphe dépasse N mots (M) ») au lieu du texte statique. ${BANNER_Y}`,
    },
    'edit-card-long': {
      skip: ['text', 'box.y'],
      reason: `E4 : bouton « Aller au paragraphe » ajouté dans l’en-tête ; message calculé. ${BANNER_Y}`,
    },
    'edit-meta-owner': { skip: ['text'], reason: 'E3 : « Équipe {nom de l’espace} » (maquette : « Équipe Identité »)' },
    'edit-meta': { skip: ['text'], reason: 'Conteneur — textes comparés sur les enfants annotés ; E3' },
    'edit-topbar': { skip: ['text'], reason: 'Conteneur — textes comparés sur les enfants annotés' },
    'edit-meta-custom-field-1': {
      skip: ['fontFamily'],
      reason: 'E6 : champ en IBM Plex Mono dans la maquette ; IBM Plex Sans dans l’app',
    },
    'edit-breadcrumb-current': {
      skip: ['box'],
      reason: 'E11 : fil d’Ariane compressé par le bouton « Enregistrer la version » (absent de la maquette)',
    },
    'edit-save-status': {
      skip: ['box'],
      reason: 'E11 : statut décalé par « Enregistrer la version » (absent de Edit.dc.html)',
    },
    'edit-word-count': {
      skip: ['box'],
      reason: 'E11 : compteur décalé par « Enregistrer la version » (absent de Edit.dc.html)',
    },
    'edit-presence': {
      skip: ['box', 'text'],
      reason:
        'Co-édition hors V1 : la maquette montre deux avatars (YM + CD) ; l’app un seul (utilisateur courant). Décalage E11 en plus',
    },
    'edit-preview': {
      skip: ['box'],
      reason: 'E11 : Aperçu décalé par « Enregistrer la version »',
    },
    'edit-send-review': {
      skip: ['box'],
      reason:
        'E11 : déclencheur ChangeSummaryPopover (hauteur/largeur) vs lien plat « Envoyer en révision » de la maquette',
    },
  }

  test('desktop Edit structural match', async ({ page }) => {
    await prep(page)
    await page.setViewportSize({ width: 1440, height: 900 })

    await page.goto(`${MOCK_ORIGIN}/Edit.dc.html`)
    await settleFonts(page)
    await annotateEditMockup(page)
    const mockMap = await collectMetrics(page, EDIT_DESKTOP_IDS)

    await page.goto(`/docs/${EDIT_DOC_ID}/edit`)
    await waitEditReady(page)
    await settleFonts(page)
    const appMap = await collectMetrics(page, EDIT_DESKTOP_IDS)

    const fonts = await assertFontsLoaded(page)
    expect(fonts.find((f) => f.family === 'Instrument Serif')?.loaded).toBe(true)
    expect(fonts.find((f) => f.family === 'IBM Plex Sans')?.loaded).toBe(true)

    const results = compareMetrics(mockMap, appMap, EDIT_DESKTOP_IDS, { pageExceptions: desktopExceptions })
    expectNoStructuralDiffs(results)
  })
})

/* ------------------------------------------------------------------ */
/* Comportement (non visuel)                                           */
/* ------------------------------------------------------------------ */

test.describe('document edit behaviour', () => {
  test('autosave : une frappe déclenche un seul PUT brouillon après ~1 s (aucune version)', async ({ page }) => {
    const putCalls = []
    const draftPutCalls = []
    await prep(page, { putCalls, draftPutCalls })
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto(`/docs/${EDIT_DOC_ID}/edit`)
    await page.waitForSelector('[data-mock-id="edit-title"]')
    await expect(page.getByTestId('edit-save-status')).toHaveText('Brouillon enregistré à 14:22')
    await expect(page.getByTestId('edit-word-count')).toContainText('1 240 mots')
    // L'ouverture seule (acquisition du verrou, normalisation TipTap) n'enregistre rien.
    await page.waitForTimeout(1500)
    expect(putCalls).toHaveLength(0)
    expect(draftPutCalls).toHaveLength(0)

    await page.click('.ProseMirror p')
    await page.keyboard.press('Control+Home')
    await page.keyboard.type('ajout ')
    await expect(page.getByTestId('edit-save-status')).toHaveText('Enregistrement…')
    await expect.poll(() => draftPutCalls.length, { timeout: 5000 }).toBe(1)
    await expect(page.getByTestId('edit-save-status')).toContainText('Brouillon enregistré à')
    expect(JSON.stringify(draftPutCalls[0].body)).toContain('ajout')
    expect(draftPutCalls[0].baseVersionNo).toBe(12)
    // L'autosave n'appelle jamais updateDocument : aucune version créée.
    expect(putCalls).toHaveLength(0)
    await expect(page.getByTestId('edit-word-count')).toContainText('1 241 mots')

    // Ctrl+S : version explicite (un seul PUT du document).
    await page.keyboard.press('Control+s')
    await expect.poll(() => putCalls.length, { timeout: 5000 }).toBe(1)
    expect(putCalls[0].expectedVersionNo).toBe(12)
    expect(JSON.stringify(putCalls[0].body)).toContain('ajout')
    await expect(page.getByTestId('edit-version-msg')).toBeVisible()
  })

  test('verrou détenu par un autre : bandeau, avatar du détenteur, lecture seule', async ({ page }) => {
    await prep(page, { lockHolder: 'Camille Durand' })
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto(`/docs/${EDIT_DOC_ID}/edit`)
    const banner = page.getByTestId('edit-lock-banner')
    await expect(banner).toContainText('Camille Durand')
    await expect(banner).toContainText('9 min')
    await expect(page.getByTestId('edit-avatar-holder')).toHaveText('CD')
    await expect(page.getByTestId('edit-avatar-self')).toHaveCount(0)
    await expect(page.getByTestId('edit-title')).toHaveJSProperty('readOnly', true)
    await expect(page.locator('.ProseMirror')).toHaveAttribute('contenteditable', 'false')
    await expect(page.getByTestId('edit-send-review')).toBeDisabled()
  })

  test('fonctions manquantes de la barre d’outils : aria-disabled + « Bientôt disponible »', async ({ page }) => {
    await prep(page)
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto(`/docs/${EDIT_DOC_ID}/edit`)
    await page.waitForSelector('[data-mock-id="edit-toolbar"]')
    for (const label of ['Couleur et surlignage', 'Insérer un diagramme draw.io', 'Mentionner une personne']) {
      const btn = page.getByRole('button', { name: label })
      await expect(btn).toHaveAttribute('aria-disabled', 'true')
      await expect(btn).toHaveAttribute('title', 'Bientôt disponible')
    }
    for (const label of ['Souligné', 'Insérer un lien', 'Insérer un tableau', 'Insérer une image']) {
      const btn = page.getByRole('button', { name: label })
      await expect(btn).not.toHaveAttribute('aria-disabled', 'true')
      await expect(btn).not.toHaveAttribute('data-soon', 'true')
    }
    await expect(page.getByRole('button', { name: 'Gras' })).not.toHaveAttribute('aria-disabled', 'true')
  })

  test('pièces jointes : bloc image + bloc fichier joint rendus dans l’éditeur', async ({ page }) => {
    await prep(page)
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto(`/docs/${EDIT_DOC_ID}/edit`)
    await page.waitForSelector('[data-mock-id="edit-toolbar"]')

    const image = page.locator('.ProseMirror').getByTestId('attachment-image')
    await expect(image).toHaveCount(1)
    await expect(image).toHaveAttribute('data-attachment-id', ATTACHMENT_IMAGE_ID)

    const file = page.locator('.ProseMirror').getByTestId('attachment-file')
    await expect(file).toHaveCount(1)
    await expect(file).toHaveAttribute('data-attachment-id', ATTACHMENT_FILE_ID)
    await expect(file.locator('.doc-attachment-name')).toHaveText('matrice-habilitations-2026.pdf')
    await expect(file.locator('.doc-attachment-meta')).toContainText('482 Ko')
  })
})
