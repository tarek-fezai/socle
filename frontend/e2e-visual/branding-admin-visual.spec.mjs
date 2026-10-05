// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
/**
 * Branding admin (/admin/branding) vs Branding.dc.html @ 1440×900
 * (maquette annotée : sans badge Entreprise / DNS / socle.app).
 */
import { test, expect } from '@playwright/test'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { PNG } from 'pngjs'
import pixelmatch from 'pixelmatch'
import {
  BRANDING_ADMIN_DESKTOP_IDS,
  annotateBrandingAdminMockup,
  assertFontsLoaded,
  collectMetrics,
  compareMetrics,
} from './structural-compare.mjs'
import { ME_TAREK, NOTIFICATIONS_SEED, VISUAL_NOW } from './dashboard-fixtures.mjs'
import { BRANDING_ADMIN_VIEW, PUBLIC_BRANDING_VIEW } from './branding-admin-fixtures.mjs'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const outDir = path.join(__dirname, 'test-results')

const AUTH_CONFIG = {
  authority: 'http://127.0.0.1:9/realms/socle',
  clientId: 'socle-frontend',
  scopes: ['openid', 'profile', 'email'],
  organizationName: 'Organisation Démo',
  displayName: 'Organisation Démo',
  supportContact: 'identite@example.com',
  passkeyAcrValues: 'phr',
  idpDisplayName: 'Demo IdP',
}

const json = (route, body, status = 200) =>
  route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })

function diffRatio(a, b, label) {
  const img1 = PNG.sync.read(a)
  const img2 = PNG.sync.read(b)
  const { width, height } = img1
  const diff = new PNG({ width, height })
  const n = pixelmatch(img1.data, img2.data, diff.data, width, height, { threshold: 0.1 })
  const ratio = n / (width * height)
  fs.writeFileSync(path.join(outDir, `${label}-diff.png`), PNG.sync.write(diff))
  return ratio
}

async function injectOidcSession(page) {
  await page.addInitScript(
    ({ authority, clientId, now }) => {
      Date.now = () => now
      const key = `oidc.user:${authority}:${clientId}`
      const user = {
        id_token: 'visual.id',
        session_state: 'visual',
        access_token: 'visual-access-token',
        refresh_token: 'visual-refresh',
        token_type: 'Bearer',
        scope: 'openid profile email',
        profile: { sub: 'tarek-visual', name: 'Tarek Fezai', preferred_username: 'tarek', given_name: 'Tarek' },
        expires_at: Math.floor(now / 1000) + 3600,
      }
      sessionStorage.setItem(key, JSON.stringify(user))
    },
    { authority: AUTH_CONFIG.authority, clientId: AUTH_CONFIG.clientId, now: VISUAL_NOW },
  )
}

async function mockBrandingAdminApis(page) {
  await page.route('**/api/v1/public/auth-config', (route) => json(route, AUTH_CONFIG))
  await page.route('**/api/v1/public/branding', (route) => json(route, PUBLIC_BRANDING_VIEW))
  await page.route('**/api/v1/me', (route) => json(route, ME_TAREK))
  await page.route('**/api/v1/notifications**', (route) => json(route, NOTIFICATIONS_SEED))
  await page.route('**/api/v1/admin/branding', (route) => json(route, BRANDING_ADMIN_VIEW))
  await page.route('**/api/v1/admin/branding/**', (route) => json(route, BRANDING_ADMIN_VIEW))
}

async function settleFonts(page) {
  await assertFontsLoaded(page)
}

async function maskAdminGlyphs(page) {
  await page.evaluate(() => {
    const hide = (el) => {
      if (!el) return
      el.style.color = 'transparent'
      el.style.webkitTextFillColor = 'transparent'
      el.style.textShadow = 'none'
    }
    // Inclure les divs (labels rail maquette = div inline, pas .admin-rail__label).
    document
      .querySelectorAll(
        'h1, p, span, a, button, label, input, div, .admin-nav-item, .admin-lead, .admin-page__breadcrumb-trail, .admin-page__home-link, .admin-builder-label, .admin-form-label, .admin-link-action, .admin-mono',
      )
      .forEach(hide)
    document.querySelectorAll('svg').forEach((el) => {
      el.style.visibility = 'hidden'
    })
  })
}

test.describe('branding admin visual', () => {
  test('desktop Branding vs maquette @ 1440×900', async ({ page }, testInfo) => {
    await injectOidcSession(page)
    await mockBrandingAdminApis(page)
    await page.setViewportSize({ width: 1440, height: 900 })

    await page.goto('/admin/branding')
    await page.waitForSelector('[data-mock-id="branding-title"]')
    await page.evaluate(() => {
      document.documentElement.style.overflow = 'hidden'
      document.body.style.margin = '0'
      document.body.style.overflow = 'hidden'
      const admin = document.querySelector('.admin-page')
      if (admin) {
        admin.style.width = '1440px'
        admin.style.height = '900px'
      }
    })
    await settleFonts(page)
    await maskAdminGlyphs(page)
    await page.waitForTimeout(120)
    const appShot = await page.screenshot({
      fullPage: false,
      clip: { x: 0, y: 0, width: 1440, height: 900 },
    })

    await page.goto('http://127.0.0.1:4174/Branding.dc.html')
    await annotateBrandingAdminMockup(page)
    await page.evaluate(() => {
      const root = document.querySelector('body div[style*="1440px"]') || document.body.firstElementChild
      if (root) {
        root.style.width = '1440px'
        root.style.height = '900px'
      }
    })
    await settleFonts(page)
    await maskAdminGlyphs(page)
    await page.waitForTimeout(120)
    const mockShot = await page.screenshot({
      fullPage: false,
      clip: { x: 0, y: 0, width: 1440, height: 900 },
    })

    await testInfo.attach('maquette-branding-admin', { body: mockShot, contentType: 'image/png' })
    await testInfo.attach('app-branding-admin', { body: appShot, contentType: 'image/png' })
    const ratio = diffRatio(mockShot, appShot, 'branding-admin-desktop')
    console.log(`branding-admin-desktop pixel diff ratio = ${(ratio * 100).toFixed(3)} %`)
    expect(ratio, `branding admin diff ${ratio}`).toBeLessThanOrEqual(0.01)
  })
})

test.describe('branding admin structural', () => {
  test('data-mock-id metrics vs Branding.dc.html (annotée)', async ({ page }) => {
    await injectOidcSession(page)
    await mockBrandingAdminApis(page)
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto('/admin/branding')
    await page.waitForSelector('[data-mock-id="branding-identity"]')

    await page.goto('http://127.0.0.1:4174/Branding.dc.html')
    await annotateBrandingAdminMockup(page)
    await settleFonts(page)
    const mockMetrics = await collectMetrics(page, BRANDING_ADMIN_DESKTOP_IDS)

    await page.goto('/admin/branding')
    await page.waitForSelector('[data-mock-id="branding-identity"]')
    await settleFonts(page)
    const appMetrics = await collectMetrics(page, BRANDING_ADMIN_DESKTOP_IDS)

    // Après compensation margin identité/domaine dans l'annotateur, viser zéro exception.
    const pageExceptions = {}
    const results = compareMetrics(mockMetrics, appMetrics, BRANDING_ADMIN_DESKTOP_IDS, {
      pageExceptions,
    })
    const failed = results.filter((r) => r.diffs.length > 0)
    expect(failed, JSON.stringify(failed, null, 2)).toEqual([])
  })
})
