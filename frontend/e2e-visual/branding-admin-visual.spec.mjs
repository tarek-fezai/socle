// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
/**
 * Règles : docs/visual-parity.md
 *
 * /admin/branding vs Branding.dc.html @ 1440×900.
 *
 * La maquette n'est pas modifiée : badge « Entreprise », domaine personnalisé + DNS, encart
 * « Réservé au plan Entreprise » restent visibles et comparés. Les écarts avec l'instance
 * auto-hébergée (URL publique en lecture seule, e-mail de test) sont des écarts de conception
 * à trancher — les sections concernées échouent tant qu'ils ne sont pas décidés.
 *
 * Pixel honnête (texte inclus, ≤ 1 % par section) ; seul exclu : `mask` Playwright nommé.
 * Annotation maquette : data-* uniquement (`annotateBrandingAdminMockup`).
 *
 * Mesure avant polish : `VISUAL_BASELINE=1 npx playwright test e2e-visual/branding-admin-visual.spec.mjs`
 */
import { test } from '@playwright/test'
import {
  BRANDING_ADMIN_DESKTOP_IDS,
  annotateBrandingAdminMockup,
  collectMetrics,
  compareMetrics,
} from './structural-compare.mjs'
import { ME_TAREK, NOTIFICATIONS_SEED, VISUAL_NOW } from './dashboard-fixtures.mjs'
import { BRANDING_ADMIN_VIEW, PUBLIC_BRANDING_VIEW } from './branding-admin-fixtures.mjs'
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

/** Toutes les zones de la maquette ont une donnée produit ou un écart de conception déclaré. */
export const NOT_IMPLEMENTED = []

const SIZE_EXCEPTIONS = {}

const PIXEL_SECTIONS = [
  { id: 'admin-breadcrumb', name: 'breadcrumb' },
  // Badges « Bientôt » : app uniquement (entrées de navigation non livrées, absentes de la maquette).
  { id: 'admin-subnav', name: 'subnav', appOnlyMasks: ['admin-subnav-bientot'] },
  { id: 'branding-title-row', name: 'title-row' },
  { id: 'branding-lead', name: 'lead' },
  { id: 'branding-logo-label', name: 'logo-label' },
  { id: 'branding-identity', name: 'identity' },
  { id: 'branding-domain-label', name: 'domain-label' },
  { id: 'branding-domain', name: 'domain' },
  { id: 'branding-email-label', name: 'email-label' },
  { id: 'branding-email', name: 'email' },
  { id: 'branding-rail-hosting', name: 'rail-hosting' },
  { id: 'branding-rail-where', name: 'rail-where' },
  { id: 'branding-rail-seealso', name: 'rail-seealso' },
]

async function mockApis(page) {
  await mockBaseApis(page, { me: ME_TAREK, notifications: NOTIFICATIONS_SEED })
  await page.route('**/api/v1/public/branding', (route) => json(route, PUBLIC_BRANDING_VIEW))
  await page.route('**/api/v1/admin/branding', (route) => json(route, BRANDING_ADMIN_VIEW))
  await page.route('**/api/v1/admin/branding/**', (route) => json(route, BRANDING_ADMIN_VIEW))
}

async function shootSections(page, side) {
  const out = {}
  for (const s of PIXEL_SECTIONS) {
    out[s.name] = await shotSection(page, `[data-mock-id="${s.id}"]`, {
      maskNames: side === 'app' ? (s.appOnlyMasks ?? []) : [],
      label: `branding-admin-sec-${s.name}-${side}`,
    })
  }
  return out
}

test.describe('branding admin visual', () => {
  test('desktop Branding — sections pixel ≤ 1 % (texte inclus)', async ({ page }, testInfo) => {
    await injectOidcSession(page, VISUAL_NOW)
    await mockApis(page)
    await page.setViewportSize({ width: 1440, height: 900 })

    await page.goto(`${MOCK_ORIGIN}/Branding.dc.html`)
    await annotateBrandingAdminMockup(page)
    await settleFonts(page)
    await reportNotImplemented(page, testInfo, NOT_IMPLEMENTED, 'branding-admin')
    const mockSec = await shootSections(page, 'mock')

    await page.goto('/admin/branding')
    await page.waitForSelector('[data-mock-id="branding-identity"]')
    await page.mouse.move(1, 1)
    await settleFonts(page)
    const appSec = await shootSections(page, 'app')

    const compare = compareSectionShots(SIZE_EXCEPTIONS)
    const results = PIXEL_SECTIONS.map((s) =>
      compare(`branding-admin-${s.name}`, mockSec[s.name].shot, appSec[s.name].shot, testInfo),
    )
    finishPixelResults('branding-admin', results)
  })

  test('Branding structural — data-mock-id', async ({ page }) => {
    await injectOidcSession(page, VISUAL_NOW)
    await mockApis(page)
    await page.setViewportSize({ width: 1440, height: 900 })

    await page.goto(`${MOCK_ORIGIN}/Branding.dc.html`)
    await annotateBrandingAdminMockup(page)
    await settleFonts(page)
    const mockMetrics = await collectMetrics(page, BRANDING_ADMIN_DESKTOP_IDS)

    await page.goto('/admin/branding')
    await page.waitForSelector('[data-mock-id="branding-identity"]')
    await settleFonts(page)
    const appMetrics = await collectMetrics(page, BRANDING_ADMIN_DESKTOP_IDS)

    expectNoStructuralDiffs(compareMetrics(mockMetrics, appMetrics, BRANDING_ADMIN_DESKTOP_IDS))
  })
})
