// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
/**
 * Règles : docs/visual-parity.md
 *
 * /admin/retention vs Retention.dc.html @ 1440×900.
 *
 * Pixel honnête (texte inclus, ≤ 1 % par section) ; seul exclu : `mask` Playwright nommé.
 * Annotation maquette : data-* uniquement (`annotateRetentionAdminMockup`).
 *
 * Mesure avant polish : `VISUAL_BASELINE=1 npx playwright test e2e-visual/retention-admin-visual.spec.mjs`
 */
import { test } from '@playwright/test'
import {
  RETENTION_ADMIN_DESKTOP_IDS,
  annotateRetentionAdminMockup,
  collectMetrics,
  compareMetrics,
} from './structural-compare.mjs'
import { ME_TAREK, NOTIFICATIONS_SEED, VISUAL_NOW } from './dashboard-fixtures.mjs'
import { RETENTION_ADMIN_SETTINGS, RETENTION_LEGAL_HOLDS } from './retention-admin-fixtures.mjs'
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

const SIZE_EXCEPTIONS = {}

const PIXEL_SECTIONS = [
  { id: 'admin-breadcrumb', name: 'breadcrumb' },
  // Badges « Bientôt » : app uniquement (entrées de navigation non livrées, absentes de la maquette).
  { id: 'admin-subnav', name: 'subnav', appOnlyMasks: ['admin-subnav-bientot'] },
  { id: 'retention-title', name: 'title' },
  { id: 'retention-lead', name: 'lead' },
  { id: 'retention-durations-label', name: 'durations-label' },
  ...[0, 1, 2].map((i) => ({ id: `retention-durations-row-${i}`, name: `durations-row-${i}` })),
  { id: 'retention-compliance-label', name: 'compliance-label' },
  ...[0, 1, 2].map((i) => ({ id: `retention-compliance-row-${i}`, name: `compliance-row-${i}` })),
  { id: 'retention-reports-label', name: 'reports-label' },
  { id: 'retention-report-health', name: 'report-health' },
  { id: 'retention-report-export', name: 'report-export' },
]

async function mockApis(page) {
  await mockBaseApis(page, { me: ME_TAREK, notifications: NOTIFICATIONS_SEED })
  await page.route('**/api/v1/admin/retention', (route) => json(route, RETENTION_ADMIN_SETTINGS))
  await page.route('**/api/v1/admin/legal-holds**', (route) => json(route, RETENTION_LEGAL_HOLDS))
}

async function shootSections(page, side) {
  const out = {}
  for (const s of PIXEL_SECTIONS) {
    out[s.name] = await shotSection(page, `[data-mock-id="${s.id}"]`, {
      maskNames: side === 'app' ? (s.appOnlyMasks ?? []) : [],
      label: `retention-admin-sec-${s.name}-${side}`,
    })
  }
  return out
}

test.describe('retention admin visual', () => {
  test('desktop Rétention — sections pixel ≤ 1 % (texte inclus)', async ({ page }, testInfo) => {
    await injectOidcSession(page, VISUAL_NOW)
    await mockApis(page)
    await page.setViewportSize({ width: 1440, height: 900 })

    await page.goto(`${MOCK_ORIGIN}/Retention.dc.html`)
    await annotateRetentionAdminMockup(page)
    await settleFonts(page)
    await reportNotImplemented(page, testInfo, NOT_IMPLEMENTED, 'retention-admin')
    const mockSec = await shootSections(page, 'mock')

    await page.goto('/admin/retention')
    await page.waitForSelector('text=24 mois')
    await page.mouse.move(1, 1)
    await settleFonts(page)
    const appSec = await shootSections(page, 'app')

    const compare = compareSectionShots(SIZE_EXCEPTIONS)
    const results = PIXEL_SECTIONS.map((s) =>
      compare(`retention-admin-${s.name}`, mockSec[s.name].shot, appSec[s.name].shot, testInfo),
    )
    finishPixelResults('retention-admin', results)
  })

  test('Rétention structural — data-mock-id', async ({ page }) => {
    await injectOidcSession(page, VISUAL_NOW)
    await mockApis(page)
    await page.setViewportSize({ width: 1440, height: 900 })

    await page.goto(`${MOCK_ORIGIN}/Retention.dc.html`)
    await annotateRetentionAdminMockup(page)
    await settleFonts(page)
    const mockMetrics = await collectMetrics(page, RETENTION_ADMIN_DESKTOP_IDS)

    await page.goto('/admin/retention')
    await page.waitForSelector('text=24 mois')
    await settleFonts(page)
    const appMetrics = await collectMetrics(page, RETENTION_ADMIN_DESKTOP_IDS)

    expectNoStructuralDiffs(compareMetrics(mockMetrics, appMetrics, RETENTION_ADMIN_DESKTOP_IDS))
  })
})
