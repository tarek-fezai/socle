// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
/**
 * Règles : docs/visual-parity.md
 *
 * Page de lecture /docs/:id vs Main.dc.html (desktop 1440×900) et MobilePage.dc.html (mobile 390×844).
 *
 * Pixel honnête (texte inclus, ≤ 1 % par section) ; seul exclu : `mask` Playwright nommé
 * (`data-visual-mask`). Annotation maquette ET app : attributs `data-*` uniquement
 * (aucun style / DOM modifié avant capture).
 *
 * ── Périmètre desktop ─────────────────────────────────────────────────────────────────
 *  Colonne principale + rail (fil d'Ariane, actions, menu d'export, onglets, statut, attestation,
 *  titre, chapeau, corps, documents liés, retour d'utilité, rail d'infos). La barre latérale (shell)
 *  n'est PAS comparée ici : Main.dc.html montre un arbre de dossiers + boutons Document/Dossier,
 *  l'app le shell Favoris / Espaces / Membres (décision tf : shell canonique, docs/visual-parity.md).
 *  Le menu « Imprimer / Exporter » est ouvert dans la maquette (état figé) : il est donc ouvert
 *  dans l'app par un clic avant capture (interaction utilisateur, pas une mutation du DOM).
 *  Les captures « sous la ligne de flottaison » défilent dans la page (maquette : conteneur
 *  `overflow: hidden` défilé par scrollIntoView, l'app défile normalement).
 *
 * ── Contexte de rendu ─────────────────────────────────────────────────────────────────
 *  User-Agent macOS : la maquette est dessinée pour macOS (« ⌘P ») ; l'app affiche « Ctrl+P »
 *  hors Apple (comportement voulu, `isApplePlatform`). Même Chromium des deux côtés.
 *  Fixtures de lecture : retour d'utilité sans totaux (la maquette n'affiche pas « 18 oui · 2 non »,
 *  réservés aux éditeurs côté app).
 *
 * Mesure avant polish : `VISUAL_BASELINE=1 npx playwright test e2e-visual/page-visual.spec.mjs`
 * (journalise tous les % / tailles, écrit test-results/page-before.json, n'échoue pas).
 */
import { test, expect } from '@playwright/test'
import {
  PAGE_BELOW_FOLD_IDS,
  PAGE_DESKTOP_IDS,
  PAGE_MOBILE_IDS,
  annotateMobilePageMockup,
  annotatePageMockup,
  assertFontsLoaded,
  collectMetrics,
  compareMetrics,
} from './structural-compare.mjs'
import {
  CAMPAIGN_ID,
  ME_TAREK,
  PAGE_ATTESTATION,
  PAGE_COMMENTS,
  PAGE_DOC_ID,
  PAGE_FEEDBACK_VIEWER,
  PAGE_LINKS,
  SPACE_IDENTITE,
  TREE_IDENTITE,
  VISUAL_NOW,
  ATTACHMENT_FILE_ID,
  ATTACHMENT_IMAGE_ID,
  mockAttachmentRoutes,
  pageDocument,
  pageDocumentViewer,
} from './page-fixtures.mjs'
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
  measureMaskName,
  mockBaseApis,
  pageAreaFor,
  reportNotImplemented,
  settleFonts,
  shotSection,
} from './visual-parity-kit.mjs'

const MAC_UA =
  'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36'

/**
 * Anti-crénelage : barre haute / onglets collants (couche composée dans l'app) sont rendus en niveaux
 * de gris, la maquette (sans couche) en LCD. Même rendu des deux côtés (--disable-lcd-text), uniquement
 * pour ce fichier ; le texte reste comparé pixel à pixel (même réglage que new-document-visual).
 */
test.use({
  timezoneId: 'Europe/Paris',
  userAgent: MAC_UA,
  launchOptions: { args: ['--disable-lcd-text'] },
})

/**
 * Zones maquette sans équivalent app — NON comparées, jamais « passées ».
 * @type {import('./visual-parity-kit.mjs').NotImplementedZone[]}
 */
export const NOT_IMPLEMENTED = [
  {
    id: 'page-drawio',
    page: 'page',
    reason:
      'Diagrammes draw.io (Fig. 1 et 3 + mention « Diagramme importé depuis draw.io ») : bloc `drawio` non rendu — l’app affiche « Bloc non pris en charge dans cette version »',
    backlog: 'DOC-DRAWIO-RENDER (lot 3)',
    mockMask: 'page-drawio',
    appPlaceholderSelector: '[data-testid="unsupported-block"]',
  },
  {
    id: 'page-figure-image',
    page: 'page',
    reason:
      'Capture d’écran Fig. 2 : la maquette montre un gabarit pointillé « [Capture d’écran — …] » ; l’app rend la pièce jointe réelle — pas d’image comparable (légende comparée)',
    backlog: 'DOC-IMAGE-PLACEHOLDER-MOCKUP',
    mockMask: 'page-figure-image',
    appPlaceholderSelector: '[data-visual-mask="page-figure-image"]',
  },
  {
    id: 'page-custom-fields',
    page: 'page',
    reason:
      'Rail « Champs personnalisés » (Référence réglementaire, Système concerné) : la page de lecture ne charge aucune valeur de champ (pas d’appel GET custom-fields côté lecture)',
    backlog: 'DOC-READ-CUSTOM-FIELDS',
    mockMask: 'page-custom-fields',
    appImplementedProbe: '[data-mock-id="rail-custom-fields"]',
  },
  {
    id: 'mpage-drawio',
    page: 'page-mobile',
    reason:
      'Schéma draw.io mobile (« Flux de provisionnement — schéma draw.io ») : bloc `drawio` non rendu — l’app affiche « Bloc non pris en charge dans cette version »',
    backlog: 'DOC-DRAWIO-RENDER (lot 3)',
    mockMask: 'mpage-drawio',
    appPlaceholderSelector: '[data-testid="unsupported-block"]',
  },
]

/**
 * Boîtes mesurées à ±1-2 px après un bloc draw.io NOT_IMPLEMENTED : la hauteur du placeholder
 * « Bloc non pris en charge » diffère de la figure SVG de la maquette (et de sa fraction de pixel),
 * donc le décalage sous-pixel cascade sur tout ce qui suit (arrondi de la boîte de capture).
 * Valeurs = écarts mesurés (app − maquette).
 */
const SIZE_EXCEPTIONS = {
  'page-desktop-figure-2': {
    dw: 0,
    dh: -1,
    reason: 'arrondi de boîte (fraction de pixel héritée du bloc draw.io NOT_IMPLEMENTED au-dessus)',
  },
}

/* ------------------------------------------------------------------ */
/* Sections                                                           */
/* ------------------------------------------------------------------ */

/** id = data-mock-id posé par `annotateSections*` (maquette et app). */
const DESKTOP_SECTIONS = [
  { id: 'pg-crumbs', name: 'crumbs' },
  { id: 'pg-actions', name: 'actions' },
  { id: 'pg-export-menu', name: 'export-menu' },
  { id: 'pg-tabs', name: 'tabs' },
  { id: 'pg-status', name: 'status' },
  { id: 'pg-attestation', name: 'attestation' },
  { id: 'pg-title', name: 'title' },
  { id: 'pg-lead', name: 'lead' },
  // Rail (sticky dans l'app) mesuré avant tout défilement : la maquette ne défile pas.
  { id: 'pg-rail-toc', name: 'rail-toc' },
  { id: 'pg-rail-owner', name: 'rail-owner' },
  { id: 'pg-rail-author', name: 'rail-author' },
  { id: 'pg-rail-modified', name: 'rail-modified' },
  { id: 'pg-rail-tags', name: 'rail-tags' },
  { id: 'pg-rail-reliability', name: 'rail-reliability' },
  { id: 'pg-h2-principe', name: 'h2-principe' },
  { id: 'pg-p-principe', name: 'p-principe' },
  { id: 'pg-quote', name: 'quote' },
  { id: 'pg-h2-roles', name: 'h2-roles' },
  { id: 'pg-table', name: 'table' },
  { id: 'pg-figure-2', name: 'figure-2', masks: ['page-figure-image'] },
  { id: 'pg-h2-flux', name: 'h2-flux' },
  { id: 'pg-p-flux', name: 'p-flux' },
  { id: 'pg-related-title', name: 'related-title' },
  { id: 'pg-related-links', name: 'related-links' },
  { id: 'pg-feedback', name: 'feedback' },
]

const MOBILE_SECTIONS = [
  { id: 'pg-m-top', name: 'top' },
  // Barre d'onglets (sticky dans l'app) mesurée avant tout défilement : la maquette ne défile pas.
  { id: 'pg-m-tabbar', name: 'tabbar' },
  { id: 'pg-m-status', name: 'status' },
  { id: 'pg-m-title', name: 'title' },
  { id: 'pg-m-tags', name: 'tags' },
  { id: 'pg-m-lead', name: 'lead' },
  { id: 'pg-m-h2-principe', name: 'h2-principe' },
  { id: 'pg-m-p-principe', name: 'p-principe' },
  { id: 'pg-m-h2-roles', name: 'h2-roles' },
  { id: 'pg-m-roles', name: 'roles' },
  { id: 'pg-m-feedback', name: 'feedback' },
]

/* ------------------------------------------------------------------ */
/* Annotation (data-* uniquement)                                      */
/* ------------------------------------------------------------------ */

/** Main.dc.html — sections pixel. À appeler après `annotatePageMockup` (ids structurels). */
async function annotateSectionsMock(page) {
  await page.evaluate(() => {
    const set = (el, id) => el?.setAttribute('data-mock-id', id)
    const mask = (el, name) => el?.setAttribute('data-visual-mask', name)
    const root = document.querySelector('body div[style*="1440px"]') || document.body.firstElementChild
    const main = root.children[1]
    const [topbar, tabs, content] = [...main.children]
    set(topbar.children[0], 'pg-crumbs')
    set(topbar.children[1], 'pg-actions')
    set(topbar.querySelector('span[style*="position: absolute"]'), 'pg-export-menu')
    set(tabs, 'pg-tabs')
    const article = content.children[0]
    const rail = content.children[1]
    const k = [...article.children]
    set(k[0], 'pg-status')
    set(k[1], 'pg-attestation')
    set(k[2], 'pg-title')
    set(k[3], 'pg-lead')
    set(k[4], 'pg-h2-principe')
    set(k[5], 'pg-p-principe')
    set(k[6], 'pg-quote')
    set(k[7], 'pg-h2-roles')
    set(k[8], 'pg-table')
    mask(k[9], 'page-drawio')
    mask(k[10], 'page-drawio')
    set(k[11], 'pg-figure-2')
    mask(k[11].children[0], 'page-figure-image')
    set(k[12], 'pg-h2-flux')
    set(k[13], 'pg-p-flux')
    mask(k[14], 'page-drawio')
    mask(k[15], 'page-drawio')
    set(k[17], 'pg-related-title')
    set(k[18], 'pg-related-links')
    set(k[19], 'pg-feedback')
    const stick = rail.firstElementChild
    const blocks = [...stick.children]
    set(blocks[0], 'pg-rail-toc')
    set(blocks[1], 'pg-rail-owner')
    set(blocks[2], 'pg-rail-author')
    set(blocks[3], 'pg-rail-modified')
    set(blocks[4], 'pg-rail-tags')
    set(blocks[5], 'pg-rail-reliability')
    mask(blocks[6], 'page-custom-fields')
  })
}

/** App /docs/:id desktop — mêmes ids de section (data-* uniquement). */
async function annotateSectionsApp(page) {
  await page.evaluate(() => {
    const set = (el, id) => el?.setAttribute('data-mock-id', id)
    const mask = (el, name) => el?.setAttribute('data-visual-mask', name)
    set(document.querySelector('.doc-topbar .doc-crumbs'), 'pg-crumbs')
    set(document.querySelector('.doc-topbar .doc-actions'), 'pg-actions')
    set(document.querySelector('.doc-tabs'), 'pg-tabs')
    set(document.querySelector('.doc-status'), 'pg-status')
    set(document.querySelector('.doc-attestation'), 'pg-attestation')
    set(document.querySelector('.doc-main .doc-title'), 'pg-title')
    const k = [...document.querySelectorAll('.doc-body .doc-read > *')]
    set(k[0], 'pg-lead')
    set(k[1], 'pg-h2-principe')
    set(k[2], 'pg-p-principe')
    set(k[3], 'pg-quote')
    set(k[4], 'pg-h2-roles')
    set(k[5], 'pg-table')
    // k[6] : drawio (Fig. 1), k[7] : image (Fig. 2)
    set(k[7], 'pg-figure-2')
    mask(k[7]?.querySelector('img'), 'page-figure-image')
    set(k[8], 'pg-h2-flux')
    set(k[9], 'pg-p-flux')
    set(document.querySelector('h2#documents-lies'), 'pg-related-title')
    set(document.querySelector('.doc-related'), 'pg-related-links')
    set(document.querySelector('.doc-feedback'), 'pg-feedback')
    const rail = document.querySelector('.doc-rail-inner')
    set(rail.querySelector('nav[aria-label="Sur cette page"]'), 'pg-rail-toc')
    set(rail.querySelector('[data-mock-id="rail-owner"]'), 'pg-rail-owner')
    set(rail.querySelector('[data-mock-id="rail-author"]'), 'pg-rail-author')
    set(rail.querySelector('[data-mock-id="rail-modified"]'), 'pg-rail-modified')
    set(rail.querySelector('[data-mock-id="rail-tags"]'), 'pg-rail-tags')
    set(rail.querySelector('[data-mock-id="rail-reliability"]'), 'pg-rail-reliability')
    // Menu d'export (ouvert par clic avant l'annotation).
    set(document.querySelector('[data-testid="doc-export-menu"]'), 'pg-export-menu')
    // Zones NOT_IMPLEMENTED : masque nommé sur l'élément app (placeholders).
    document.querySelectorAll('[data-testid="unsupported-block"]').forEach((el) => mask(el, 'page-drawio'))
  })
}

/** MobilePage.dc.html — sections pixel. */
async function annotateSectionsMobileMock(page) {
  await page.evaluate(() => {
    const set = (el, id) => el?.setAttribute('data-mock-id', id)
    const mask = (el, name) => el?.setAttribute('data-visual-mask', name)
    const root = Array.from(document.querySelectorAll('div')).find((d) =>
      (d.getAttribute('style') || '').includes('390px'),
    )
    const [top, content, bar] = [...root.children]
    set(top, 'pg-m-top')
    const k = [...content.children]
    set(k[0], 'pg-m-status')
    set(k[1], 'pg-m-title')
    set(k[2], 'pg-m-tags')
    set(k[3], 'pg-m-lead')
    set(k[4], 'pg-m-h2-principe')
    set(k[5], 'pg-m-p-principe')
    set(k[6], 'pg-m-h2-roles')
    set(k[7], 'pg-m-roles')
    mask(k[8], 'mpage-drawio')
    set(k[9], 'pg-m-feedback')
    set(bar, 'pg-m-tabbar')
  })
}

async function annotateSectionsMobileApp(page) {
  await page.evaluate(() => {
    const set = (el, id) => el?.setAttribute('data-mock-id', id)
    const mask = (el, name) => el?.setAttribute('data-visual-mask', name)
    set(document.querySelector('.doc-mobile-top'), 'pg-m-top')
    set(document.querySelector('.doc-status'), 'pg-m-status')
    set(document.querySelector('.doc-main .doc-title'), 'pg-m-title')
    set(document.querySelector('.doc-tags--mobile'), 'pg-m-tags')
    const k = [...document.querySelectorAll('.doc-body .doc-read > *')]
    set(k[0], 'pg-m-lead')
    set(k[1], 'pg-m-h2-principe')
    set(k[2], 'pg-m-p-principe')
    set(k[3], 'pg-m-h2-roles')
    set(k[4], 'pg-m-roles')
    document.querySelectorAll('[data-testid="unsupported-block"]').forEach((el) => mask(el, 'mpage-drawio'))
    set(document.querySelector('.doc-feedback'), 'pg-m-feedback')
    set(document.querySelector('.doc-mobile-tabs'), 'pg-m-tabbar')
  })
}

/* ------------------------------------------------------------------ */
/* API                                                                */
/* ------------------------------------------------------------------ */

/**
 * @param {import('@playwright/test').Page} page
 * @param {{ variant?: 'desktop'|'mobile', attestation?: boolean, editor?: boolean, related?: boolean }} [opts]
 */
async function mockPageApis(page, { variant = 'desktop', attestation = true, editor = true, related = true } = {}) {
  await mockBaseApis(page, { me: ME_TAREK, notifications: NOTIFICATIONS_SEED, spaces: [SPACE_IDENTITE, SPACE_INFRA] })
  await page.route('**/api/v1/public/auth-config', (route) =>
    json(route, { ...AUTH_CONFIG, supportContact: 'identite@example.com', passkeyAcrValues: 'phr' }),
  )
  await mockAttachmentRoutes(page)
  await page.route(`**/api/v1/documents/${PAGE_DOC_ID}/resolved`, (route) =>
    json(route, editor ? pageDocument(variant) : pageDocumentViewer(variant)),
  )
  await page.route(`**/api/v1/documents/${PAGE_DOC_ID}/view`, (route) => route.fulfill({ status: 204 }))
  await page.route(`**/api/v1/documents/${PAGE_DOC_ID}/comments**`, (route) => json(route, PAGE_COMMENTS))
  await page.route(`**/api/v1/documents/${PAGE_DOC_ID}/feedback`, (route) => json(route, PAGE_FEEDBACK_VIEWER))
  await page.route(`**/api/v1/documents/${PAGE_DOC_ID}/attestations/active`, (route) =>
    attestation ? json(route, PAGE_ATTESTATION) : route.fulfill({ status: 204 }),
  )
  await page.route(`**/api/v1/documents/${PAGE_DOC_ID}/attestations/${CAMPAIGN_ID}/acknowledge`, (route) =>
    json(route, { ...PAGE_ATTESTATION, acknowledged: true, ackCount: PAGE_ATTESTATION.ackCount + 1 }),
  )
  await page.route(`**/api/v1/documents/${PAGE_DOC_ID}/links`, (route) =>
    json(route, related ? PAGE_LINKS : { outgoing: [], incoming: [] }),
  )
  await page.route('**/api/v1/favorites/**', (route) => json(route, { favorited: true }))
  await page.route('**/api/v1/favorites', (route) => json(route, FAVORITES_SEED))
  await page.route(`**/api/v1/spaces/${SPACE_IDENTITE.id}`, (route) => json(route, SPACE_IDENTITE))
  await page.route(`**/api/v1/spaces/${SPACE_IDENTITE.id}/owners`, (route) =>
    json(route, {
      spaceId: SPACE_IDENTITE.id,
      owners: [{ userId: ME_TAREK.id, email: ME_TAREK.email, displayName: ME_TAREK.displayName, responsible: true }],
    }),
  )
  await page.route(`**/api/v1/spaces/${SPACE_IDENTITE.id}/tree**`, (route) => json(route, TREE_IDENTITE))
  await page.route(`**/api/v1/spaces/${SPACE_INFRA.id}/tree**`, (route) => json(route, TREE_INFRA))
}

async function prep(page, opts) {
  await injectOidcSession(page, VISUAL_NOW)
  await page.addInitScript(() => {
    localStorage.setItem(
      'socle.shell.expandedSpaces',
      JSON.stringify(['s0000001-0000-4000-8000-000000000001', 's0000001-0000-4000-8000-000000000002']),
    )
  })
  await mockPageApis(page, opts)
}

async function waitDesktopApp(page) {
  await page.waitForSelector('[data-mock-id="doc-title"]')
  await page.waitForSelector('[data-mock-id="doc-attestation"]')
  await page.waitForSelector('[data-mock-id="rail-reliability"]')
  await page.waitForSelector('[data-mock-id="doc-related-link"]')
}

/** Menu d'export ouvert (maquette : état figé ouvert) — clic utilisateur, pas de mutation du DOM. */
async function openAppExportMenu(page) {
  await page.getByTestId('doc-export-btn').click()
  await page.waitForSelector('[data-testid="doc-export-menu"]')
  await page.mouse.move(1, 1)
}

/* ------------------------------------------------------------------ */
/* Pixel                                                              */
/* ------------------------------------------------------------------ */

async function shootAll(page, sections, prefix, side) {
  const out = {}
  for (const s of sections) {
    out[s.name] = await shotSection(page, `[data-mock-id="${s.id}"]`, {
      maskNames: s.masks ?? [],
      label: `${prefix}-sec-${s.name}-${side}`,
    })
  }
  return out
}

test.describe('document page visual parity', () => {
  test('desktop Page vs Main.dc.html — sections pixel ≤ 1 % (texte inclus)', async ({ page }, testInfo) => {
    await prep(page)
    await page.setViewportSize({ width: 1440, height: 900 })

    await page.goto(`${MOCK_ORIGIN}/Main.dc.html`)
    await annotateSectionsMock(page)
    await settleFonts(page)
    await reportNotImplemented(page, testInfo, NOT_IMPLEMENTED, 'page')
    const mockSec = await shootAll(page, DESKTOP_SECTIONS, 'page-desktop', 'mock')

    await page.goto(`/docs/${PAGE_DOC_ID}`)
    await waitDesktopApp(page)
    await openAppExportMenu(page)
    await annotateSectionsApp(page)
    await settleFonts(page)
    await assertNotImplementedGuards(page, NOT_IMPLEMENTED, 'page')

    const compare = compareSectionShots(SIZE_EXCEPTIONS)
    const results = []
    // Haut de page d'abord (sections sticky / menu), puis le corps, puis le rail.
    for (const s of DESKTOP_SECTIONS) {
      await page.mouse.move(1, 1)
      const app = await shotSection(page, `[data-mock-id="${s.id}"]`, {
        maskNames: s.masks ?? [],
        label: `page-desktop-sec-${s.name}-app`,
      })
      results.push(compare(`page-desktop-${s.name}`, mockSec[s.name].shot, app.shot, testInfo))
    }
    finishPixelResults('page', results)
  })

  test('mobile Page vs MobilePage.dc.html @ 390×844 — sections pixel ≤ 1 % (texte inclus)', async ({
    page,
  }, testInfo) => {
    await prep(page, { variant: 'mobile', attestation: false, related: false })
    await page.setViewportSize({ width: 390, height: 844 })

    await page.goto(`${MOCK_ORIGIN}/MobilePage.dc.html`)
    await annotateSectionsMobileMock(page)
    await settleFonts(page)
    // Surface mobile : 390×844 (pas 1440×900).
    const area = pageAreaFor({ width: 390, height: 844 })
    const m = await measureMaskName(page, 'mpage-drawio', area)
    const mz = NOT_IMPLEMENTED.find((z) => z.id === 'mpage-drawio')
    const desc = `NOT_IMPLEMENTED ${mz.id}: ${m.areaPx} px (${m.pctOfPage} % page 390×844) — ${mz.reason} [${mz.backlog}]`
    console.log(desc)
    testInfo.annotations.push({ type: 'not-implemented', description: desc })
    const mockSec = {}
    for (const s of MOBILE_SECTIONS) {
      mockSec[s.name] = await shotSection(page, `[data-mock-id="${s.id}"]`, {
        label: `page-mobile-sec-${s.name}-mock`,
      })
    }

    await page.goto(`/docs/${PAGE_DOC_ID}`)
    await page.waitForSelector('[data-mock-id="doc-title"]')
    await page.waitForSelector('[data-mock-id="doc-mobile-tabs"]')
    await annotateSectionsMobileApp(page)
    await settleFonts(page)
    await assertNotImplementedGuards(page, NOT_IMPLEMENTED, 'page-mobile')

    const compare = compareSectionShots(SIZE_EXCEPTIONS)
    const results = []
    for (const s of MOBILE_SECTIONS) {
      await page.mouse.move(1, 1)
      const app = await shotSection(page, `[data-mock-id="${s.id}"]`, {
        label: `page-mobile-sec-${s.name}-app`,
      })
      results.push(compare(`page-mobile-${s.name}`, mockSec[s.name].shot, app.shot, testInfo))
    }
    finishPixelResults('page-mobile', results)
  })
})

/* ------------------------------------------------------------------ */
/* Structurel                                                         */
/* ------------------------------------------------------------------ */

test.describe('document page structural', () => {
  const FLOW_Y =
    'box.y : cascade verticale (figures draw.io rendues en « Bloc non pris en charge », fichier joint absent de la maquette, Fig. 4 non seedée) ; x / w / h comparés.'
  const desktopExceptions = {
    'doc-topbar': { skip: ['text'], reason: 'Conteneur — libellés des boutons d’action comparés par le pixel (sections actions / export-menu)' },
    'doc-breadcrumb': { skip: ['text'], reason: 'Séparateurs → et liens : textContent agrégé ; comparé par le pixel (section crumbs)' },
    'doc-switch-index': {
      skip: ['box', 'lineHeight'],
      reason: 'Onglet Index désactivé (title="bientôt") : <button> au lieu du lien Index.dc.html',
    },
    'doc-tabs': { skip: ['text'], reason: 'Libellés dans les enfants ; Modifier/Accès conditionnels aux droits' },
    'doc-attestation': { skip: ['text'], reason: 'Conteneur — textes comparés sur les enfants annotés' },
    'rail-owner-name': {
      skip: ['text', 'box.width'],
      reason: 'Propriétaire = « Équipe {nom de l’espace} » (maquette : « Équipe Identité ») — donnée réelle, largeur du texte en découle',
    },
  }

  const belowFoldExceptions = {
    'doc-related-title': { skip: ['box.y'], reason: FLOW_Y },
    'doc-related-link': { skip: ['box.y'], reason: FLOW_Y },
    'doc-feedback-label': { skip: ['box.y', 'text'], reason: `${FLOW_Y} Libellé desktop/mobile partagé (deux <span> dans l’app).` },
  }

  test('desktop Page structural match', async ({ page }, testInfo) => {
    await prep(page)
    await page.setViewportSize({ width: 1440, height: 900 })

    await page.goto(`${MOCK_ORIGIN}/Main.dc.html`)
    await settleFonts(page)
    await annotatePageMockup(page)
    const mockMap = await collectMetrics(page, PAGE_DESKTOP_IDS)

    await page.goto(`/docs/${PAGE_DOC_ID}`)
    await waitDesktopApp(page)
    await settleFonts(page)
    const appMap = await collectMetrics(page, PAGE_DESKTOP_IDS)

    const fonts = await assertFontsLoaded(page)
    expect(fonts.find((f) => f.family === 'Instrument Serif')?.loaded).toBe(true)
    expect(fonts.find((f) => f.family === 'IBM Plex Sans')?.loaded).toBe(true)

    const results = compareMetrics(mockMap, appMap, PAGE_DESKTOP_IDS, { pageExceptions: desktopExceptions })
    await testInfo.attach('structural-page-desktop.json', {
      body: Buffer.from(JSON.stringify(results, null, 2)),
      contentType: 'application/json',
    })
    expectNoStructuralDiffs(results)
  })

  test('mobile Page structural match', async ({ page }) => {
    await prep(page, { variant: 'mobile', attestation: false, related: false })
    await page.setViewportSize({ width: 390, height: 844 })

    await page.goto(`${MOCK_ORIGIN}/MobilePage.dc.html`)
    await settleFonts(page)
    await annotateMobilePageMockup(page)
    const mockMap = await collectMetrics(page, PAGE_MOBILE_IDS)

    await page.goto(`/docs/${PAGE_DOC_ID}`)
    await page.waitForSelector('[data-mock-id="doc-title"]')
    await page.waitForSelector('[data-mock-id="doc-mobile-tabs"]')
    await settleFonts(page)
    const appMap = await collectMetrics(page, PAGE_MOBILE_IDS)

    const results = compareMetrics(mockMap, appMap, PAGE_MOBILE_IDS, { pageExceptions: {} })
    expectNoStructuralDiffs(results)
  })

  test('desktop below-fold : Documents liés + feedback (après défilement)', async ({ page }) => {
    await prep(page)
    await page.setViewportSize({ width: 1440, height: 900 })

    await page.goto(`${MOCK_ORIGIN}/Main.dc.html`)
    await settleFonts(page)
    await annotatePageMockup(page)
    for (const id of PAGE_BELOW_FOLD_IDS) {
      await page.locator(`[data-mock-id="${id}"]`).first().scrollIntoViewIfNeeded()
    }
    const mockMap = await collectMetrics(page, PAGE_BELOW_FOLD_IDS)

    await page.goto(`/docs/${PAGE_DOC_ID}`)
    await page.waitForSelector('[data-mock-id="doc-related-title"]')
    await page.waitForSelector('[data-mock-id="doc-feedback-label"]')
    await settleFonts(page)
    for (const id of PAGE_BELOW_FOLD_IDS) {
      await page.locator(`[data-mock-id="${id}"]`).first().scrollIntoViewIfNeeded()
    }
    const appMap = await collectMetrics(page, PAGE_BELOW_FOLD_IDS)

    const results = compareMetrics(mockMap, appMap, PAGE_BELOW_FOLD_IDS, {
      pageExceptions: belowFoldExceptions,
    })
    expectNoStructuralDiffs(results)
  })
})

/* ------------------------------------------------------------------ */
/* Comportement (non visuel)                                           */
/* ------------------------------------------------------------------ */

test.describe('document page behaviour', () => {
  test('print : masque shell, barre, onglets et rail ; garde titre et contenu', async ({ page }) => {
    await prep(page)
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto(`/docs/${PAGE_DOC_ID}`)
    await waitDesktopApp(page)
    await page.emulateMedia({ media: 'print' })

    const display = (sel) =>
      page.evaluate((s) => {
        const el = document.querySelector(s)
        return el ? getComputedStyle(el).display : 'absent'
      }, sel)

    expect(await display('.shell-sidebar')).toBe('none')
    expect(await display('.doc-topbar')).toBe('none')
    expect(await display('.doc-tabs')).toBe('none')
    expect(await display('.doc-rail')).toBe('none')
    expect(await display('.doc-feedback')).toBe('none')
    expect(await display('.doc-title')).not.toBe('none')
    expect(await display('.doc-body')).not.toBe('none')
    await expect(page.locator('.doc-title')).toBeVisible()
    await expect(page.locator('.doc-body')).toBeVisible()

    await page.emulateMedia({ media: 'screen' })
    expect(await display('.doc-topbar')).not.toBe('none')
    expect(await display('.doc-rail')).not.toBe('none')
  })

  test('/docs/:id/view redirige vers /docs/:id', async ({ page }) => {
    await prep(page)
    await page.goto(`/docs/${PAGE_DOC_ID}/view`)
    await page.waitForSelector('[data-mock-id="doc-title"]')
    expect(new URL(page.url()).pathname).toBe(`/docs/${PAGE_DOC_ID}`)
  })

  test('lecteur sans droit d’édition : pas de Modifier / Publier', async ({ page }) => {
    await prep(page, { editor: false })
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto(`/docs/${PAGE_DOC_ID}`)
    await page.waitForSelector('[data-mock-id="doc-title"]')
    await expect(page.getByTestId('tab-edit')).toHaveCount(0)
    await expect(page.getByTestId('doc-publish')).toHaveCount(0)
    await expect(page.getByTestId('doc-feedback')).toBeVisible()
  })

  test('pièces jointes : image légendée (Fig. 2) + carte fichier joint', async ({ page }) => {
    await prep(page)
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto(`/docs/${PAGE_DOC_ID}`)
    await page.waitForSelector('[data-mock-id="doc-title"]')

    const image = page.getByTestId('attachment-image')
    await expect(image).toHaveCount(1)
    await expect(image).toHaveAttribute('data-attachment-id', ATTACHMENT_IMAGE_ID)
    await expect(image).toHaveAttribute('alt', "Capture d'écran du formulaire de demande d'accès")
    await expect(page.locator('figure.doc-figure figcaption')).toContainText(
      'Fig. 2 — Formulaire soumis par le demandeur, avec justification obligatoire au-delà de 90 jours.',
    )

    const file = page.getByTestId('attachment-file')
    await expect(file).toHaveCount(1)
    await expect(file).toHaveAttribute('data-attachment-id', ATTACHMENT_FILE_ID)
    await expect(file.locator('.doc-attachment-name')).toHaveText('matrice-habilitations-2026.pdf')
    await expect(file.locator('.doc-attachment-meta')).toContainText('482 Ko')
  })

  test('blocs non supportés : libellé neutre, pas de JSON brut', async ({ page }) => {
    await prep(page)
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto(`/docs/${PAGE_DOC_ID}`)
    await page.waitForSelector('[data-mock-id="doc-title"]')
    const blocks = page.getByTestId('unsupported-block')
    await expect(blocks.first()).toContainText('Bloc non pris en charge dans cette version')
    const body = await page.locator('.doc-body').innerText()
    expect(body).not.toContain('"type"')
    expect(body).not.toContain('drawio')
  })
})
