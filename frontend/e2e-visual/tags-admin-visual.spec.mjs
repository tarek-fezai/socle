// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
/**
 * Règles : docs/visual-parity.md
 *
 * /admin/tags vs TagsAdmin.dc.html @ 1440×900.
 *
 * Pixel honnête (texte inclus, ≤ 1 % par section) ; seul exclu : `mask` Playwright nommé.
 * Annotation maquette : data-* uniquement (`annotateTagsAdminMockup`).
 *
 * Mesure avant polish : `VISUAL_BASELINE=1 npx playwright test e2e-visual/tags-admin-visual.spec.mjs`
 * (journalise tous les % / tailles, écrit test-results/tags-admin-before.json, n'échoue pas).
 */
import { test } from '@playwright/test'
import {
  TAGS_ADMIN_DESKTOP_IDS,
  annotateTagsAdminMockup,
  collectMetrics,
  compareMetrics,
} from './structural-compare.mjs'
import { ME_TAREK, NOTIFICATIONS_SEED, VISUAL_NOW } from './dashboard-fixtures.mjs'
import { TAGS_ADMIN_LIST } from './tags-admin-fixtures.mjs'
import {
  MOCK_ORIGIN,
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

/** Toutes les zones de la maquette ont une donnée produit : aucune entrée. */
export const NOT_IMPLEMENTED = []

/** Colonne principale et rail de même largeur des deux côtés : aucune SIZE_EXCEPTION. */
const SIZE_EXCEPTIONS = {}

const ROWS = [0, 1, 2, 3, 4, 5, 6]

const PIXEL_SECTIONS = [
  { id: 'admin-breadcrumb', name: 'breadcrumb' },
  // Badges « Bientôt » : app uniquement (entrées de navigation non livrées, absentes de la maquette).
  { id: 'admin-subnav', name: 'subnav', appOnlyMasks: ['admin-subnav-bientot'] },
  { id: 'tags-title-row', name: 'title-row' },
  { id: 'tags-stats', name: 'stats' },
  { id: 'tags-table-head', name: 'table-head' },
  ...ROWS.map((i) => ({ id: `tags-row-${i}`, name: `row-${i}` })),
  { id: 'tags-callout', name: 'callout' },
  { id: 'tags-rail-block-0', name: 'rail-count' },
  { id: 'tags-rail-block-1', name: 'rail-tagged' },
  { id: 'tags-rail-block-2', name: 'rail-policy' },
]

async function mockApis(page) {
  await mockBaseApis(page, { me: ME_TAREK, notifications: NOTIFICATIONS_SEED })
  await page.route('**/api/v1/admin/tags', (route) => json(route, TAGS_ADMIN_LIST))
  await page.route('**/api/v1/admin/tags/**', (route) => json(route, TAGS_ADMIN_LIST.summary))
}

async function shootSections(page, side) {
  const out = {}
  for (const s of PIXEL_SECTIONS) {
    out[s.name] = await shotSection(page, `[data-mock-id="${s.id}"]`, {
      maskNames: side === 'app' ? (s.appOnlyMasks ?? []) : [],
      label: `tags-admin-sec-${s.name}-${side}`,
    })
  }
  return out
}

test.describe('tags admin visual', () => {
  test('desktop Tags — sections pixel ≤ 1 % (texte inclus)', async ({ page }, testInfo) => {
    await injectOidcSession(page, VISUAL_NOW)
    await mockApis(page)
    await page.setViewportSize({ width: 1440, height: 900 })

    await page.goto(`${MOCK_ORIGIN}/TagsAdmin.dc.html`)
    await annotateTagsAdminMockup(page)
    await settleFonts(page)
    await reportNotImplemented(page, testInfo, NOT_IMPLEMENTED, 'tags-admin')
    const mockSec = await shootSections(page, 'mock')

    await page.goto('/admin/tags')
    await page.waitForSelector('[data-mock-id="tags-row-6"]')
    await page.mouse.move(1, 1)
    await settleFonts(page)
    const appSec = await shootSections(page, 'app')

    const compare = compareSectionShots(SIZE_EXCEPTIONS)
    const results = PIXEL_SECTIONS.map((s) =>
      compare(`tags-admin-${s.name}`, mockSec[s.name].shot, appSec[s.name].shot, testInfo),
    )
    finishPixelResults('tags-admin', results)
  })

  test('Tags structural — data-mock-id', async ({ page }) => {
    await injectOidcSession(page, VISUAL_NOW)
    await mockApis(page)
    await page.setViewportSize({ width: 1440, height: 900 })

    await page.goto(`${MOCK_ORIGIN}/TagsAdmin.dc.html`)
    await annotateTagsAdminMockup(page)
    await settleFonts(page)
    const mockMetrics = await collectMetrics(page, TAGS_ADMIN_DESKTOP_IDS)

    await page.goto('/admin/tags')
    await page.waitForSelector('[data-mock-id="tags-row-6"]')
    await settleFonts(page)
    const appMetrics = await collectMetrics(page, TAGS_ADMIN_DESKTOP_IDS)

    expectNoStructuralDiffs(compareMetrics(mockMetrics, appMetrics, TAGS_ADMIN_DESKTOP_IDS))
  })
})
