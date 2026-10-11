// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
/**
 * Règles : docs/visual-parity.md
 *
 * /admin/custom-fields vs CustomFields.dc.html @ 1440×900 (liste, constructeur ouvert, rail).
 *
 * Le constructeur « Aperçu du constructeur — nouveau champ » est un état figé de la maquette :
 * l'app l'ouvre par clic sur « Nouveau champ » puis le remplit au clavier (nom, description,
 * type « Liste (unique) », trois valeurs) — jamais par modification du DOM.
 *
 * APP_EXTRA (DP-2026-10-11 #6) : sélection de l'espace, statut, pastilles × de retrait et champ
 * « Nouvelle valeur » sont nécessaires au fonctionnement et absents de la maquette → masqués côté
 * app uniquement, surfaces chiffrées ; placés de façon à ne pas décaler les éléments de la maquette.
 *
 * Pixel honnête (texte inclus, ≤ 1 % par section) ; seul exclu : `mask` Playwright nommé.
 * Annotation maquette : data-* uniquement (`annotateCustomFieldsAdminMockup`).
 *
 * Mesure avant polish : `VISUAL_BASELINE=1 npx playwright test e2e-visual/custom-fields-admin-visual.spec.mjs`
 */
import { test } from '@playwright/test'
import {
  CUSTOM_FIELDS_ADMIN_DESKTOP_IDS,
  annotateCustomFieldsAdminMockup,
  collectMetrics,
  compareMetrics,
} from './structural-compare.mjs'
import { ME_TAREK, NOTIFICATIONS_SEED, VISUAL_NOW } from './dashboard-fixtures.mjs'
import {
  CUSTOM_FIELDS_ADMIN_LIST,
  SPACE_CONFORMITE,
  SPACE_INFRA,
} from './custom-fields-admin-fixtures.mjs'
import {
  MOCK_ORIGIN,
  compareSectionShots,
  expectNoStructuralDiffs,
  finishPixelResults,
  injectOidcSession,
  json,
  mockBaseApis,
  reportAppExtras,
  reportNotImplemented,
  settleFonts,
  shotSection,
} from './visual-parity-kit.mjs'

/** Toutes les zones de la maquette ont une donnée produit : aucune entrée. */
export const NOT_IMPLEMENTED = []

const DECISION = 'DP-2026-10-11 #6 — constructeur fonctionnel (portée, statut, édition des valeurs)'

/** @type {import('./visual-parity-kit.mjs').AppExtraZone[]} */
export const APP_EXTRA = [
  {
    id: 'cf-space-select',
    page: 'custom-fields-admin',
    appMask: 'cf-space-select',
    reason: 'choix de l\'espace quand la portée est « Espace spécifique »',
    decision: DECISION,
  },
  {
    id: 'cf-status',
    page: 'custom-fields-admin',
    appMask: 'cf-status',
    reason: 'statut du champ (brouillon / actif / archivé)',
    decision: DECISION,
  },
  {
    id: 'cf-choice-remove',
    page: 'custom-fields-admin',
    appMask: 'cf-choice-remove',
    reason: 'pastilles × de retrait d\'une valeur',
    decision: DECISION,
  },
  {
    id: 'cf-choice-input',
    page: 'custom-fields-admin',
    appMask: 'cf-choice-input',
    reason: 'saisie « Nouvelle valeur »',
    decision: DECISION,
  },
]
const APP_EXTRA_MASKS = APP_EXTRA.map((z) => z.appMask)

const SIZE_EXCEPTIONS = {}

const ROWS = [0, 1, 2, 3, 4]

const PIXEL_SECTIONS = [
  { id: 'admin-breadcrumb', name: 'breadcrumb' },
  // Badges « Bientôt » : app uniquement (entrées de navigation non livrées, absentes de la maquette).
  { id: 'admin-subnav', name: 'subnav', appOnlyMasks: ['admin-subnav-bientot'] },
  { id: 'custom-fields-title-row', name: 'title-row' },
  { id: 'custom-fields-stats', name: 'stats' },
  { id: 'custom-fields-table-head', name: 'table-head' },
  ...ROWS.map((i) => ({ id: `custom-fields-row-${i}`, name: `row-${i}` })),
  { id: 'custom-fields-builder-label', name: 'builder-label' },
  { id: 'custom-fields-builder', name: 'builder', appOnlyMasks: APP_EXTRA_MASKS },
  { id: 'custom-fields-builder-actions', name: 'builder-actions' },
  { id: 'custom-fields-rail-block-0', name: 'rail-where' },
  { id: 'custom-fields-rail-block-1', name: 'rail-limits' },
  { id: 'custom-fields-rail-block-2', name: 'rail-seealso' },
]

const STRUCTURAL_IDS = [
  ...CUSTOM_FIELDS_ADMIN_DESKTOP_IDS,
  'custom-fields-builder-label',
  'custom-fields-builder',
]

async function mockApis(page) {
  await mockBaseApis(page, {
    me: ME_TAREK,
    notifications: NOTIFICATIONS_SEED,
    spaces: [SPACE_CONFORMITE, SPACE_INFRA],
  })
  await page.route('**/api/v1/admin/custom-fields', (route) => json(route, CUSTOM_FIELDS_ADMIN_LIST))
}

/** Reproduit l'état figé « nouveau champ » de la maquette par interaction. */
async function openBuilderLikeMockup(page) {
  await page.click('[data-mock-id="custom-fields-cta"]')
  await page.fill('#cf-name', "Fournisseur d'identité concerné")
  await page.fill('#cf-help', 'Fournisseur IdP concerné par cette procédure')
  await page.getByRole('button', { name: /^Liste \(unique\)/ }).click()
  const add = page.getByPlaceholder('Nouvelle valeur')
  for (const v of ['Okta', 'SailPoint IdentityIQ', 'Entra ID']) {
    await add.fill(v)
    await add.press('Enter')
  }
  await page.locator('[data-mock-id="custom-fields-builder-label"]').click()
}

async function shootSections(page, side) {
  const out = {}
  for (const s of PIXEL_SECTIONS) {
    out[s.name] = await shotSection(page, `[data-mock-id="${s.id}"]`, {
      maskNames: side === 'app' ? (s.appOnlyMasks ?? []) : [],
      label: `custom-fields-admin-sec-${s.name}-${side}`,
    })
  }
  return out
}

test.describe('custom fields admin visual', () => {
  test('desktop Champs personnalisés — sections pixel ≤ 1 % (texte inclus)', async ({ page }, testInfo) => {
    await injectOidcSession(page, VISUAL_NOW)
    await mockApis(page)
    await page.setViewportSize({ width: 1440, height: 900 })

    await page.goto(`${MOCK_ORIGIN}/CustomFields.dc.html`)
    await annotateCustomFieldsAdminMockup(page)
    await settleFonts(page)
    await reportNotImplemented(page, testInfo, NOT_IMPLEMENTED, 'custom-fields-admin')
    const mockSec = await shootSections(page, 'mock')

    await page.goto('/admin/custom-fields')
    await page.waitForSelector('[data-mock-id="custom-fields-row-4"]')
    await openBuilderLikeMockup(page)
    await page.mouse.move(1, 1)
    await settleFonts(page)
    await reportAppExtras(page, testInfo, APP_EXTRA, 'custom-fields-admin')
    const appSec = await shootSections(page, 'app')

    const compare = compareSectionShots(SIZE_EXCEPTIONS)
    const results = PIXEL_SECTIONS.map((s) =>
      compare(`custom-fields-admin-${s.name}`, mockSec[s.name].shot, appSec[s.name].shot, testInfo),
    )
    finishPixelResults('custom-fields-admin', results)
  })

  test('Champs personnalisés structural — data-mock-id', async ({ page }) => {
    await injectOidcSession(page, VISUAL_NOW)
    await mockApis(page)
    await page.setViewportSize({ width: 1440, height: 900 })

    await page.goto(`${MOCK_ORIGIN}/CustomFields.dc.html`)
    await annotateCustomFieldsAdminMockup(page)
    await settleFonts(page)
    const mockMetrics = await collectMetrics(page, STRUCTURAL_IDS)

    await page.goto('/admin/custom-fields')
    await page.waitForSelector('[data-mock-id="custom-fields-row-4"]')
    await openBuilderLikeMockup(page)
    await page.mouse.move(720, 450)
    await page.mouse.wheel(0, -5000)
    await page.waitForFunction(() => document.querySelector('.admin-main')?.scrollTop === 0)
    await settleFonts(page)
    const appMetrics = await collectMetrics(page, STRUCTURAL_IDS)

    expectNoStructuralDiffs(
      compareMetrics(mockMetrics, appMetrics, STRUCTURAL_IDS, {
        pageExceptions: {
          'custom-fields-builder': { skip: ['text'], reason: `${DECISION} : texte des APP_EXTRA` },
        },
      }),
    )
  })
})
