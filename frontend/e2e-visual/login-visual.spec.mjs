// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
/**
 * Règles : docs/visual-parity.md
 *
 * /login vs Login.dc.html (1440×900), /login/erreur vs LoginError.dc.html (1440×900),
 * /login vs MobileLogin.dc.html (390×844).
 *
 * États figés de la maquette reproduits par interaction / données réelles :
 * - Login : « tarek.fezai » saisi au clavier dans le champ e-mail, puis focus retiré (la maquette
 *   n'a pas d'anneau de focus ; un champ focalisé fait aussi passer Chromium en anti-crénelage
 *   niveaux de gris sur le texte des boutons) ;
 * - LoginError : e-mail issu du profil OIDC de la session (jamais réécrit dans le DOM).
 *
 * Pixel honnête (texte inclus, ≤ 1 % par section) ; seul exclu : `mask` Playwright nommé.
 * Annotation maquette : data-* uniquement (`annotateLogin*Mockup`).
 *
 * Mesure avant polish : `VISUAL_BASELINE=1 npx playwright test e2e-visual/login-visual.spec.mjs`
 * (écrit test-results/login-*-before.json, n'échoue pas).
 */
import { test, expect } from '@playwright/test'
import {
  LOGIN_DESKTOP_IDS,
  LOGIN_ERROR_IDS,
  LOGIN_MOBILE_IDS,
  annotateLoginMockup,
  annotateLoginErrorMockup,
  annotateMobileMockup,
  assertFontsLoaded,
  collectMetrics,
  compareMetrics,
} from './structural-compare.mjs'
import { VISUAL_NOW } from './dashboard-fixtures.mjs'
import {
  AUTH_CONFIG,
  MOCK_ORIGIN,
  assertNotImplementedGuards,
  compareSectionShots,
  expectNoStructuralDiffs,
  finishPixelResults,
  injectOidcSession,
  json,
  pageAreaFor,
  reportNotImplemented,
  settleFonts,
  shotSection,
} from './visual-parity-kit.mjs'

const LOGIN_AUTH_CONFIG = {
  ...AUTH_CONFIG,
  idpDisplayName: 'Okta',
  supportContact: 'identite@example.com',
  passkeyAcrValues: 'phr',
}

/** Marque par défaut de l'instance (aucun nom personnalisé) : « Socle », comme la maquette. */
const DEFAULT_PUBLIC_BRANDING = { accentColor: '#3730E0', hidePoweredBy: false }

const MOBILE = { width: 390, height: 844 }

/**
 * Zones maquette sans donnée / fonctionnalité produit — NON comparées, jamais « passées ».
 * @type {import('./visual-parity-kit.mjs').NotImplementedZone[]}
 */
export const NOT_IMPLEMENTED = [
  {
    id: 'login-email-domain-hint',
    page: 'login',
    reason:
      'Suggestion grisée du domaine de l’organisation (« @example.com ») après la saisie de l’identifiant : l’app n’a pas de complétion de domaine — le champ est un input e-mail simple',
    backlog: 'LOGIN-EMAIL-DOMAIN-HINT',
    mockMask: 'login-email-domain-hint',
  },
  {
    id: 'login-error-provisioning-conflicts',
    page: 'login-error',
    reason:
      'Troisième cause « 2 conflits de provisioning sont en attente… » : le nombre de conflits n’est exposé par aucune API publique (la page d’erreur est non authentifiée)',
    backlog: 'LOGIN-ERROR-PROVISIONING-CONFLICTS',
    mockMask: 'login-error-provisioning-conflicts',
    appImplementedProbe: '[data-mock-id="cause-2"]',
  },
]

/** Cascade verticale sous la liste des causes (troisième cause NOT_IMPLEMENTED). */
const CENTERED_CASCADE = {
  skip: ['box.y'],
  reason:
    'box.y : colonne centrée verticalement, plus courte de la cause NOT_IMPLEMENTED (login-error-provisioning-conflicts)',
}

const ERROR_STRUCT_EXCEPTIONS = {
  'brand-name': CENTERED_CASCADE,
  title: CENTERED_CASCADE,
  subtitle: CENTERED_CASCADE,
  'causes-title': CENTERED_CASCADE,
  'cause-0': CENTERED_CASCADE,
  'cause-1': CENTERED_CASCADE,
  causes: {
    skip: ['text', 'box.height', 'box.y'],
    reason: 'Cadre des causes : troisième cause NOT_IMPLEMENTED (login-error-provisioning-conflicts) ; causes comparées une à une',
  },
  'cta-retry': { skip: ['box.y'], reason: 'box.y : cascade sous la cause NOT_IMPLEMENTED' },
  'contact-support': { skip: ['box.y'], reason: 'box.y : cascade sous la cause NOT_IMPLEMENTED' },
  footer: { skip: ['box.y'], reason: 'box.y : cascade sous la cause NOT_IMPLEMENTED' },
}

/** Caret dessiné de la maquette (span 2×16) : l'app a un vrai caret, masqué à la capture. */
const FAKE_CARET_MASK = {
  name: 'login-mock-fake-caret',
  selector: '[data-visual-mask="login-mock-fake-caret"]',
  reason: 'caret dessiné de la maquette (le caret réel de l’app est masqué par la capture)',
}

const SIZE_EXCEPTIONS = {}

const DESKTOP_SECTIONS = [
  { id: 'login-brand', name: 'brand' },
  { id: 'title', name: 'title' },
  { id: 'subtitle', name: 'subtitle' },
  { id: 'label-email', name: 'label-email' },
  {
    id: 'email-field',
    name: 'email-field',
    mockMasks: ['login-email-domain-hint'],
    mockTargeted: [FAKE_CARET_MASK],
  },
  { id: 'cta-continue', name: 'cta' },
  { id: 'login-divider', name: 'divider' },
  { id: 'sso-org-btn', name: 'sso-org' },
  { id: 'sso-passkey-btn', name: 'sso-passkey' },
  { id: 'footer', name: 'footer' },
  { id: 'login-visual', name: 'visual' },
]

const ERROR_SECTIONS = [
  { id: 'login-brand', name: 'brand' },
  { id: 'login-alert-icon', name: 'alert-icon' },
  { id: 'title', name: 'title' },
  { id: 'subtitle', name: 'subtitle' },
  { id: 'causes-title', name: 'causes-title' },
  { id: 'cause-0', name: 'cause-0' },
  { id: 'cause-1', name: 'cause-1' },
  { id: 'cta-retry', name: 'retry' },
  { id: 'contact-support', name: 'contact' },
  { id: 'footer', name: 'footer' },
  { id: 'login-visual', name: 'visual' },
]

const MOBILE_SECTIONS = [
  { id: 'mobile-brand-block', name: 'brand' },
  { id: 'mobile-sso-btn', name: 'sso' },
  { id: 'mobile-note', name: 'note' },
  { id: 'mobile-help', name: 'help' },
]

async function mockLoginApis(page, authOverrides = {}) {
  await page.route('**/api/v1/public/auth-config', (route) =>
    json(route, { ...LOGIN_AUTH_CONFIG, ...authOverrides }),
  )
  await page.route('**/api/v1/public/branding', (route) => json(route, DEFAULT_PUBLIC_BRANDING))
}

async function shootSections(page, sections, side, prefix, pageArea) {
  const out = {}
  for (const s of sections) {
    out[s.name] = await shotSection(page, `[data-mock-id="${s.id}"]`, {
      maskNames: side === 'mock' ? (s.mockMasks ?? []) : [],
      targeted: side === 'mock' ? (s.mockTargeted ?? []) : [],
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

test.describe('login visual parity', () => {
  test('no Google Fonts network on /login', async ({ page }) => {
    const blocked = []
    page.on('request', (req) => {
      const u = req.url()
      if (u.includes('fonts.googleapis.com') || u.includes('fonts.gstatic.com')) blocked.push(u)
    })
    await mockLoginApis(page)
    await page.goto('/login')
    await page.waitForSelector('text=Se connecter')
    expect(blocked, `Google Fonts requests: ${blocked.join(', ')}`).toEqual([])
  })

  test('desktop Login — sections pixel ≤ 1 % (texte inclus)', async ({ page }, testInfo) => {
    await mockLoginApis(page)
    await page.setViewportSize({ width: 1440, height: 900 })

    await page.goto(`${MOCK_ORIGIN}/Login.dc.html`)
    await annotateLoginMockup(page)
    await settleFonts(page)
    await reportNotImplemented(page, testInfo, NOT_IMPLEMENTED, 'login')
    const mockSec = await shootSections(page, DESKTOP_SECTIONS, 'mock', 'login')

    await page.goto('/login')
    await page.waitForSelector('[data-mock-id="sso-passkey-btn"]')
    await page.fill('#login-email', 'tarek.fezai')
    await page.locator('#login-email').blur()
    await page.mouse.move(1, 1)
    await settleFonts(page)
    await assertNotImplementedGuards(page, NOT_IMPLEMENTED, 'login')
    const appSec = await shootSections(page, DESKTOP_SECTIONS, 'app', 'login')

    finishPixelResults('login', compareAll(DESKTOP_SECTIONS, mockSec, appSec, 'login', testInfo))
  })

  test('LoginError — sections pixel ≤ 1 % (texte inclus)', async ({ page }, testInfo) => {
    await injectOidcSession(page, VISUAL_NOW, { profile: { email: 'tarek.fezai@example.com' } })
    await mockLoginApis(page)
    await page.setViewportSize({ width: 1440, height: 900 })

    await page.goto(`${MOCK_ORIGIN}/LoginError.dc.html`)
    await annotateLoginErrorMockup(page)
    await settleFonts(page)
    await reportNotImplemented(page, testInfo, NOT_IMPLEMENTED, 'login-error')
    const mockSec = await shootSections(page, ERROR_SECTIONS, 'mock', 'login-error')

    await page.goto('/login/erreur?reason=not_provisioned')
    await page.waitForSelector('text=tarek.fezai@example.com')
    await page.mouse.move(1, 1)
    await settleFonts(page)
    await assertNotImplementedGuards(page, NOT_IMPLEMENTED, 'login-error')
    const appSec = await shootSections(page, ERROR_SECTIONS, 'app', 'login-error')

    finishPixelResults('login-error', compareAll(ERROR_SECTIONS, mockSec, appSec, 'login-error', testInfo))
  })

  test('MobileLogin — sections pixel ≤ 1 % @ 390×844', async ({ page }, testInfo) => {
    await mockLoginApis(page)
    await page.setViewportSize(MOBILE)
    const area = pageAreaFor(MOBILE)

    await page.goto(`${MOCK_ORIGIN}/MobileLogin.dc.html`)
    await annotateMobileMockup(page)
    await settleFonts(page)
    const mockSec = await shootSections(page, MOBILE_SECTIONS, 'mock', 'login-mobile', area)

    await page.goto('/login')
    await page.waitForSelector('[data-mock-id="mobile-sso-btn"]')
    await page.mouse.move(1, 1)
    await settleFonts(page)
    const appSec = await shootSections(page, MOBILE_SECTIONS, 'app', 'login-mobile', area)

    finishPixelResults('login-mobile', compareAll(MOBILE_SECTIONS, mockSec, appSec, 'login-mobile', testInfo))
  })
})

test.describe('login structural typography', () => {
  test('embedded OFL fonts loaded on /login', async ({ page }) => {
    await mockLoginApis(page)
    await page.goto('/login')
    await page.waitForSelector('[data-mock-id="title"]')
    const fonts = await assertFontsLoaded(page)
    for (const f of fonts.filter((x) => x.family !== 'IBM Plex Mono')) {
      expect(f.loaded, `${f.family} not loaded: ${JSON.stringify(f)}`).toBe(true)
    }
  })

  test('desktop Login structural', async ({ page }) => {
    await mockLoginApis(page)
    await page.setViewportSize({ width: 1440, height: 900 })

    await page.goto(`${MOCK_ORIGIN}/Login.dc.html`)
    await annotateLoginMockup(page)
    await settleFonts(page)
    const mockMap = await collectMetrics(page, LOGIN_DESKTOP_IDS)

    await page.goto('/login')
    await page.waitForSelector('[data-mock-id="sso-passkey-btn"]')
    await page.fill('#login-email', 'tarek.fezai')
    await page.locator('#login-email').blur()
    await settleFonts(page)
    const appMap = await collectMetrics(page, LOGIN_DESKTOP_IDS)

    expectNoStructuralDiffs(compareMetrics(mockMap, appMap, LOGIN_DESKTOP_IDS))
  })

  test('LoginError structural', async ({ page }) => {
    await injectOidcSession(page, VISUAL_NOW, { profile: { email: 'tarek.fezai@example.com' } })
    await mockLoginApis(page)
    await page.setViewportSize({ width: 1440, height: 900 })

    await page.goto(`${MOCK_ORIGIN}/LoginError.dc.html`)
    await annotateLoginErrorMockup(page)
    await settleFonts(page)
    const mockMap = await collectMetrics(page, LOGIN_ERROR_IDS)

    await page.goto('/login/erreur?reason=not_provisioned')
    await page.waitForSelector('text=tarek.fezai@example.com')
    await settleFonts(page)
    const appMap = await collectMetrics(page, LOGIN_ERROR_IDS)

    expectNoStructuralDiffs(
      compareMetrics(mockMap, appMap, LOGIN_ERROR_IDS, { pageExceptions: ERROR_STRUCT_EXCEPTIONS }),
    )
  })

  test('MobileLogin structural @ 390×844', async ({ page }) => {
    await mockLoginApis(page)
    await page.setViewportSize(MOBILE)

    await page.goto(`${MOCK_ORIGIN}/MobileLogin.dc.html`)
    await annotateMobileMockup(page)
    await settleFonts(page)
    const mockMap = await collectMetrics(page, LOGIN_MOBILE_IDS)

    await page.goto('/login')
    await page.waitForSelector('[data-mock-id="mobile-sso-btn"]')
    await settleFonts(page)
    const appMap = await collectMetrics(page, LOGIN_MOBILE_IDS)

    expectNoStructuralDiffs(compareMetrics(mockMap, appMap, LOGIN_MOBILE_IDS))
  })
})

test.describe('login auth behaviour', () => {
  test('passkey button hidden when passkeyAcrValues empty', async ({ page }) => {
    await mockLoginApis(page, { passkeyAcrValues: '' })
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto('/login')
    await expect(page.getByRole('button', { name: /clé de sécurité/i })).toHaveCount(0)
    await expect(page.getByRole('button', { name: /SSO de l'organisation/i })).toBeVisible()
  })

  test('passkey button present when configured', async ({ page }) => {
    await mockLoginApis(page, { passkeyAcrValues: 'phr' })
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto('/login')
    await expect(page.getByRole('button', { name: /clé de sécurité/i })).toBeVisible()
  })
})
