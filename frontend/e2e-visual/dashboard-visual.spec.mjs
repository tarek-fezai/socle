// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
/**
 * Règles : docs/visual-parity.md
 *
 * / (accueil) vs Dashboard.dc.html (1440×900), MobileDashboard.dc.html (390×844),
 * menu mobile ouvert vs MobileMenu.dc.html (390×844).
 *
 * États figés reproduits sans toucher au DOM :
 * - plateforme de la maquette (macOS : raccourci « ⌘K ») → user-agent du contexte navigateur ;
 * - menu mobile ouvert depuis la page « Politique de gestion des accès » (document actif de la
 *   maquette) → navigation vers /docs/:id puis clic sur le bouton menu.
 *
 * Pixel honnête (texte inclus, ≤ 1 % par section) ; seul exclu : `mask` Playwright nommé.
 * Annotation maquette : data-* uniquement (`annotate*Mockup`).
 *
 * Mesure avant polish : `VISUAL_BASELINE=1 npx playwright test e2e-visual/dashboard-visual.spec.mjs`
 */
import { test } from '@playwright/test'
import {
  DASHBOARD_DESKTOP_IDS,
  DASHBOARD_MOBILE_IDS,
  MOBILE_MENU_IDS,
  annotateDashboardMockup,
  annotateMobileDashboardMockup,
  annotateMobileMenuMockup,
  collectMetrics,
  compareMetrics,
} from './structural-compare.mjs'
import {
  FAVORITES_SEED,
  HOME_SEED,
  ME_TAREK,
  NOTIFICATIONS_SEED,
  SPACE_IDENTITE,
  SPACE_INFRA,
  TREE_IDENTITE,
  TREE_INFRA,
  VISUAL_NOW,
} from './dashboard-fixtures.mjs'
import {
  PAGE_COMMENTS,
  PAGE_DOC_ID,
  PAGE_FEEDBACK_VIEWER,
  mockAttachmentRoutes,
  pageDocument,
} from './page-fixtures.mjs'
import {
  MOCK_ORIGIN,
  assertNotImplementedGuards,
  compareSectionShots,
  expectNoStructuralDiffs,
  finishPixelResults,
  injectOidcSession,
  json,
  mockBaseApis,
  pageAreaFor,
  reportNotImplemented,
  settleFonts,
  shotSection,
} from './visual-parity-kit.mjs'

/** Plateforme de la maquette (raccourci de recherche « ⌘K »). */
const MAC_USER_AGENT =
  'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36'

const MOBILE = { width: 390, height: 844 }

/** Marque par défaut de l'instance (aucun nom personnalisé) : « Socle », comme la maquette. */
const DEFAULT_PUBLIC_BRANDING = { accentColor: '#3730E0', hidePoweredBy: false }

/**
 * Zones maquette sans donnée produit — NON comparées, jamais « passées ».
 * @type {import('./visual-parity-kit.mjs').NotImplementedZone[]}
 */
export const NOT_IMPLEMENTED = [
  {
    id: 'home-approval-proposal',
    page: 'dashboard',
    reason:
      'Carte « Procédure de provisioning — Modifications proposées par Yanis M. » : HomePendingApproval ne distingue pas une proposition de modification (Review) d’une demande d’approbation — l’app affiche « … demande une approbation · — »',
    backlog: 'HOME-PENDING-KIND',
    mockMeasureSelector: '[data-mock-id="home-approval-card-1"]',
  },
]

const SIZE_EXCEPTIONS = {}

const DESKTOP_SECTIONS = [
  { id: 'shell-logo', name: 'logo' },
  { id: 'shell-search-chip', name: 'search' },
  { id: 'shell-nav-favorites', name: 'nav-favorites' },
  { id: 'shell-nav-spaces', name: 'nav-spaces' },
  { id: 'shell-nav-team', name: 'nav-team' },
  { id: 'shell-tree', name: 'tree' },
  { id: 'shell-user', name: 'user' },
  { id: 'shell-header', name: 'header' },
  { id: 'home-greeting', name: 'greeting' },
  { id: 'home-subtitle', name: 'subtitle' },
  { id: 'home-kpi-published', name: 'kpi-published' },
  { id: 'home-kpi-pending', name: 'kpi-pending' },
  { id: 'home-kpi-views', name: 'kpi-views' },
  { id: 'home-kpi-reliability', name: 'kpi-reliability' },
  { id: 'home-section-resume', name: 'section-resume' },
  { id: 'home-resume-card-0', name: 'resume-0' },
  { id: 'home-resume-card-1', name: 'resume-1' },
  { id: 'home-section-published', name: 'section-published' },
  { id: 'home-published-card-0', name: 'published-0' },
  { id: 'home-published-card-1', name: 'published-1' },
  { id: 'home-section-approvals', name: 'section-approvals' },
  { id: 'home-approval-card-0', name: 'approval-0' },
  { id: 'home-section-activity', name: 'section-activity' },
  ...[0, 1, 2].map((i) => ({ id: `home-activity-row-${i}`, name: `activity-${i}` })),
]

const MOBILE_SECTIONS = [
  { id: 'mobile-topbar', name: 'topbar' },
  { id: 'home-greeting', name: 'greeting' },
  { id: 'home-subtitle', name: 'subtitle' },
  { id: 'home-kpi-published', name: 'kpi-published' },
  { id: 'home-kpi-pending', name: 'kpi-pending' },
  { id: 'home-section-resume', name: 'section-resume' },
  { id: 'home-resume-card-0', name: 'resume-0' },
  { id: 'home-resume-card-1', name: 'resume-1' },
  { id: 'home-section-approvals', name: 'section-approvals' },
  { id: 'home-approval-card-0', name: 'approval-0' },
  { id: 'home-section-activity', name: 'section-activity' },
  { id: 'home-activity-row-0', name: 'activity-0' },
  { id: 'home-activity-row-1', name: 'activity-1' },
  { id: 'mobile-tabbar', name: 'tabbar' },
]

const MENU_SECTIONS = [
  { id: 'mobile-menu-head', name: 'head' },
  { id: 'mobile-menu-search', name: 'search' },
  { id: 'mobile-menu-space', name: 'space' },
  { id: 'mobile-menu-active-doc', name: 'active-doc' },
  { id: 'mobile-menu-folder-0', name: 'folder-0' },
  { id: 'mobile-menu-folder-1', name: 'folder-1' },
  { id: 'mobile-menu-actions', name: 'actions' },
  { id: 'mobile-menu-trash', name: 'trash' },
  { id: 'mobile-menu-user', name: 'user' },
]

async function prepHome(page) {
  await injectOidcSession(page, VISUAL_NOW, { expandedSpaces: [SPACE_IDENTITE.id, SPACE_INFRA.id] })
  await mockBaseApis(page, {
    me: ME_TAREK,
    notifications: NOTIFICATIONS_SEED,
    spaces: [SPACE_IDENTITE, SPACE_INFRA],
  })
  await page.route('**/api/v1/public/branding', (route) => json(route, DEFAULT_PUBLIC_BRANDING))
  await page.route('**/api/v1/home', (route) => json(route, HOME_SEED))
  await page.route('**/api/v1/favorites**', (route) => json(route, FAVORITES_SEED))
  await page.route(`**/api/v1/spaces/${SPACE_IDENTITE.id}/tree**`, (route) => json(route, TREE_IDENTITE))
  await page.route(`**/api/v1/spaces/${SPACE_INFRA.id}/tree**`, (route) => json(route, TREE_INFRA))
}

/** MobileMenu.dc.html est ouvert depuis la page du document actif. */
async function openMenuFromDocument(page) {
  await mockAttachmentRoutes(page)
  await page.route(`**/api/v1/documents/${PAGE_DOC_ID}/resolved`, (route) => json(route, pageDocument('mobile')))
  await page.route(`**/api/v1/documents/${PAGE_DOC_ID}/view`, (route) => route.fulfill({ status: 204 }))
  await page.route(`**/api/v1/documents/${PAGE_DOC_ID}/comments**`, (route) => json(route, PAGE_COMMENTS))
  await page.route(`**/api/v1/documents/${PAGE_DOC_ID}/feedback`, (route) => json(route, PAGE_FEEDBACK_VIEWER))
  await page.route(`**/api/v1/documents/${PAGE_DOC_ID}/attestations/active`, (route) => route.fulfill({ status: 204 }))
  await page.route(`**/api/v1/documents/${PAGE_DOC_ID}/links`, (route) => json(route, { outgoing: [], incoming: [] }))
  await page.route(`**/api/v1/spaces/${SPACE_IDENTITE.id}`, (route) => json(route, SPACE_IDENTITE))
  await page.route(`**/api/v1/spaces/${SPACE_IDENTITE.id}/owners`, (route) =>
    json(route, {
      spaceId: SPACE_IDENTITE.id,
      owners: [{ userId: ME_TAREK.id, email: ME_TAREK.email, displayName: ME_TAREK.displayName, responsible: true }],
    }),
  )
  await page.goto(`/docs/${PAGE_DOC_ID}`)
  await page.click('[data-testid="mobile-menu-btn"]')
  await page.waitForSelector('[data-mock-id="mobile-menu-folder-1"]')
}

async function shootSections(page, sections, side, prefix, pageArea) {
  const out = {}
  for (const s of sections) {
    out[s.name] = await shotSection(page, `[data-mock-id="${s.id}"]`, {
      label: `${prefix}-sec-${s.name}-${side}`,
      pageArea,
    })
  }
  return out
}

function compareAll(sections, mockSec, appSec, prefix, testInfo) {
  const compare = compareSectionShots(SIZE_EXCEPTIONS)
  return sections.map((s) => compare(`${prefix}-${s.name}`, mockSec[s.name].shot, appSec[s.name].shot, testInfo))
}

test.describe('dashboard visual parity — desktop', () => {
  test.use({ timezoneId: 'Europe/Paris', userAgent: MAC_USER_AGENT })

  test('desktop Accueil — sections pixel ≤ 1 % (texte inclus)', async ({ page }, testInfo) => {
    await prepHome(page)
    await page.setViewportSize({ width: 1440, height: 900 })

    await page.goto(`${MOCK_ORIGIN}/Dashboard.dc.html`)
    await annotateDashboardMockup(page)
    await settleFonts(page)
    await reportNotImplemented(page, testInfo, NOT_IMPLEMENTED, 'dashboard')
    const mockSec = await shootSections(page, DESKTOP_SECTIONS, 'mock', 'dashboard')

    await page.goto('/')
    await page.waitForSelector('[data-mock-id="home-activity-row-2"]')
    await page.waitForSelector('[data-mock-id="shell-space-section"]')
    await page.mouse.move(1, 1)
    await settleFonts(page)
    await assertNotImplementedGuards(page, NOT_IMPLEMENTED, 'dashboard')
    const appSec = await shootSections(page, DESKTOP_SECTIONS, 'app', 'dashboard')

    finishPixelResults('dashboard', compareAll(DESKTOP_SECTIONS, mockSec, appSec, 'dashboard', testInfo))
  })

  test('desktop Accueil structural', async ({ page }) => {
    await prepHome(page)
    await page.setViewportSize({ width: 1440, height: 900 })

    await page.goto(`${MOCK_ORIGIN}/Dashboard.dc.html`)
    await annotateDashboardMockup(page)
    await settleFonts(page)
    const mockMap = await collectMetrics(page, DASHBOARD_DESKTOP_IDS)

    await page.goto('/')
    await page.waitForSelector('[data-mock-id="home-activity-row-2"]')
    await page.waitForSelector('[data-mock-id="shell-space-section"]')
    await settleFonts(page)
    const appMap = await collectMetrics(page, DASHBOARD_DESKTOP_IDS)

    expectNoStructuralDiffs(compareMetrics(mockMap, appMap, DASHBOARD_DESKTOP_IDS))
  })
})

test.describe('dashboard visual parity — mobile', () => {
  test.use({ timezoneId: 'Europe/Paris' })

  test('mobile Accueil — sections pixel ≤ 1 % @ 390×844', async ({ page }, testInfo) => {
    await prepHome(page)
    await page.setViewportSize(MOBILE)
    const area = pageAreaFor(MOBILE)

    await page.goto(`${MOCK_ORIGIN}/MobileDashboard.dc.html`)
    await annotateMobileDashboardMockup(page)
    await settleFonts(page)
    const mockSec = await shootSections(page, MOBILE_SECTIONS, 'mock', 'dashboard-mobile', area)

    await page.goto('/')
    await page.waitForSelector('[data-mock-id="home-activity-row-1"]')
    await page.mouse.move(1, 1)
    await settleFonts(page)
    const appSec = await shootSections(page, MOBILE_SECTIONS, 'app', 'dashboard-mobile', area)

    finishPixelResults(
      'dashboard-mobile',
      compareAll(MOBILE_SECTIONS, mockSec, appSec, 'dashboard-mobile', testInfo),
    )
  })

  test('menu mobile — sections pixel ≤ 1 % @ 390×844', async ({ page }, testInfo) => {
    await prepHome(page)
    await page.setViewportSize(MOBILE)
    const area = pageAreaFor(MOBILE)

    await page.goto(`${MOCK_ORIGIN}/MobileMenu.dc.html`)
    await annotateMobileMenuMockup(page)
    await settleFonts(page)
    const mockSec = await shootSections(page, MENU_SECTIONS, 'mock', 'dashboard-menu', area)

    await openMenuFromDocument(page)
    await page.mouse.move(1, 1)
    await settleFonts(page)
    const appSec = await shootSections(page, MENU_SECTIONS, 'app', 'dashboard-menu', area)

    finishPixelResults('dashboard-menu', compareAll(MENU_SECTIONS, mockSec, appSec, 'dashboard-menu', testInfo))
  })

  test('mobile Accueil structural', async ({ page }) => {
    await prepHome(page)
    await page.setViewportSize(MOBILE)

    await page.goto(`${MOCK_ORIGIN}/MobileDashboard.dc.html`)
    await annotateMobileDashboardMockup(page)
    await settleFonts(page)
    const mockMap = await collectMetrics(page, DASHBOARD_MOBILE_IDS)

    await page.goto('/')
    await page.waitForSelector('[data-mock-id="home-activity-row-1"]')
    await settleFonts(page)
    const appMap = await collectMetrics(page, DASHBOARD_MOBILE_IDS)

    expectNoStructuralDiffs(compareMetrics(mockMap, appMap, DASHBOARD_MOBILE_IDS))
  })

  test('menu mobile structural', async ({ page }) => {
    await prepHome(page)
    await page.setViewportSize(MOBILE)

    await page.goto(`${MOCK_ORIGIN}/MobileMenu.dc.html`)
    await annotateMobileMenuMockup(page)
    await settleFonts(page)
    const mockMap = await collectMetrics(page, MOBILE_MENU_IDS)

    await openMenuFromDocument(page)
    await settleFonts(page)
    const appMap = await collectMetrics(page, MOBILE_MENU_IDS)

    expectNoStructuralDiffs(compareMetrics(mockMap, appMap, MOBILE_MENU_IDS))
  })
})
