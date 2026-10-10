// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
/**
 * Règles : docs/visual-parity.md
 *
 * /admin/branding vs Branding.dc.html @ 1440×900.
 *
 * Décision produit DP-2026-10-11 #6 : Socle est auto-hébergé, pas de plan SaaS à domaine dédié.
 * - Maquette sans équivalent (badge « Entreprise », domaine personnalisé + DNS, encart « Réservé au
 *   plan Entreprise », « domaine dédié » du chapeau) → NOT_IMPLEMENTED, surface chiffrée.
 * - App sans équivalent maquette (URL publique d'instance, encart « Instance auto-hébergée »,
 *   « URL publique » du chapeau) → APP_EXTRA, masque côté app uniquement, surface chiffrée.
 * - Sections entièrement couvertes des deux côtés (libellé + carte domaine, encart d'hébergement) :
 *   non comparées en pixel, surfaces journalisées ci-dessus.
 * - « Envoyer un e-mail de test » (app, même ligne que SPF pour ne pas décaler) → APP_EXTRA.
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
  reportAppExtras,
  reportNotImplemented,
  settleFonts,
  shotSection,
} from './visual-parity-kit.mjs'

const DECISION = 'DP-2026-10-11 #6 — Socle est auto-hébergé, pas de plan SaaS à domaine dédié'
const LEAD_MOCK_TAIL = 'domaine dédié et expéditeur des e-mails.'
const LEAD_APP_TAIL = 'URL publique et expéditeur des e-mails.'

/** @type {import('./visual-parity-kit.mjs').NotImplementedZone[]} */
export const NOT_IMPLEMENTED = [
  {
    id: 'branding-plan-badge',
    page: 'branding-admin',
    mockMask: 'branding-plan-badge',
    reason: 'badge de plan « Entreprise » : pas de plan SaaS',
    backlog: DECISION,
  },
  {
    id: 'branding-custom-domain',
    page: 'branding-admin',
    mockMask: 'branding-custom-domain',
    reason: 'domaine personnalisé (docs.example.com, DNS, vérification) : pas de domaine dédié',
    backlog: DECISION,
  },
  {
    id: 'branding-plan-entreprise',
    page: 'branding-admin',
    mockMask: 'branding-plan-entreprise',
    reason: 'encart « Réservé au plan Entreprise » : pas de plan SaaS',
    backlog: DECISION,
  },
  {
    id: 'branding-lead-domain',
    page: 'branding-admin',
    mockRange: { selector: '[data-mock-id="branding-lead"]', text: LEAD_MOCK_TAIL },
    reason: 'chapeau « domaine dédié » : pas de domaine dédié',
    backlog: DECISION,
  },
]

/** @type {import('./visual-parity-kit.mjs').AppExtraZone[]} */
export const APP_EXTRA = [
  {
    id: 'branding-public-url',
    page: 'branding-admin',
    appMask: 'branding-public-url',
    reason: 'URL publique d\'instance (SOCLE_PUBLIC_BASE_URL, lecture seule) à la place du domaine dédié',
    decision: DECISION,
  },
  {
    id: 'branding-self-hosted',
    page: 'branding-admin',
    appMask: 'branding-self-hosted',
    reason: 'encart « Instance auto-hébergée » à la place de « Réservé au plan Entreprise »',
    decision: DECISION,
  },
  {
    id: 'branding-lead-public-url',
    page: 'branding-admin',
    appRange: { selector: '[data-mock-id="branding-lead"]', text: LEAD_APP_TAIL },
    reason: 'chapeau « URL publique » à la place de « domaine dédié »',
    decision: DECISION,
  },
  {
    id: 'branding-test-email',
    page: 'branding-admin',
    appMask: 'branding-test-email',
    reason: 'envoi d\'e-mail de test (instance auto-hébergée, absent de la maquette SaaS)',
    decision: DECISION,
  },
]

const SIZE_EXCEPTIONS = {}

const PIXEL_SECTIONS = [
  { id: 'admin-breadcrumb', name: 'breadcrumb' },
  // Badges « Bientôt » : app uniquement (entrées de navigation non livrées, absentes de la maquette).
  { id: 'admin-subnav', name: 'subnav', appOnlyMasks: ['admin-subnav-bientot'] },
  { id: 'branding-title-row', name: 'title-row', mockMasks: ['branding-plan-badge'] },
  {
    id: 'branding-lead',
    name: 'lead',
    mockRanges: [{ name: 'branding-lead-domain', text: LEAD_MOCK_TAIL }],
    appRanges: [{ name: 'branding-lead-public-url', text: LEAD_APP_TAIL }],
  },
  { id: 'branding-logo-label', name: 'logo-label' },
  { id: 'branding-identity', name: 'identity' },
  { id: 'branding-email-label', name: 'email-label' },
  { id: 'branding-email', name: 'email', appOnlyMasks: ['branding-test-email'] },
  { id: 'branding-rail-where', name: 'rail-where' },
  { id: 'branding-rail-seealso', name: 'rail-seealso' },
]

/** Écarts structurels mesurés, tous issus de DP-2026-10-11 #6. */
const STRUCT_EXCEPTIONS = {
  'branding-lead': { skip: ['text'], reason: `${DECISION} (chapeau)` },
  'branding-domain': { skip: ['text', 'box.height'], reason: `${DECISION} (URL publique ≠ domaine personnalisé)` },
  'branding-email': {
    skip: ['text', 'box.y', 'box.height'],
    reason: `${DECISION} (e-mail de test APP_EXTRA, même ligne que SPF)`,
  },
  'branding-rail': { skip: ['text'], reason: `${DECISION} (encart d'hébergement)` },
}

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
      maskNames: side === 'app' ? (s.appOnlyMasks ?? []) : (s.mockMasks ?? []),
      rangeMasks: side === 'app' ? (s.appRanges ?? []) : (s.mockRanges ?? []),
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
    await reportAppExtras(page, testInfo, APP_EXTRA, 'branding-admin')
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

    expectNoStructuralDiffs(
      compareMetrics(mockMetrics, appMetrics, BRANDING_ADMIN_DESKTOP_IDS, { pageExceptions: STRUCT_EXCEPTIONS }),
    )
  })
})
