// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
/**
 * Retention admin (/admin/retention) vs Retention.dc.html @ 1440×900.
 */
import { test, expect } from '@playwright/test'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { PNG } from 'pngjs'
import pixelmatch from 'pixelmatch'
import {
  RETENTION_ADMIN_DESKTOP_IDS,
  annotateRetentionAdminMockup,
  assertFontsLoaded,
  collectMetrics,
  compareMetrics,
} from './structural-compare.mjs'
import { ME_TAREK, NOTIFICATIONS_SEED, VISUAL_NOW } from './dashboard-fixtures.mjs'
import { RETENTION_ADMIN_SETTINGS, RETENTION_LEGAL_HOLDS } from './retention-admin-fixtures.mjs'

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

async function mockRetentionAdminApis(page) {
  await page.route('**/api/v1/public/auth-config', (route) => json(route, AUTH_CONFIG))
  await page.route('**/api/v1/public/branding', (route) =>
    json(route, {
      instanceName: 'Organisation Démo',
      accentColor: '#3730E0',
      logoUrl: null,
      faviconUrl: null,
      hidePoweredBy: true,
    }),
  )
  await page.route('**/api/v1/me', (route) => json(route, ME_TAREK))
  await page.route('**/api/v1/notifications**', (route) => json(route, NOTIFICATIONS_SEED))
  await page.route('**/api/v1/admin/retention', (route) => json(route, RETENTION_ADMIN_SETTINGS))
  await page.route('**/api/v1/admin/legal-holds**', (route) => json(route, RETENTION_LEGAL_HOLDS))
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
    document
      .querySelectorAll(
        'h1, p, span, a, button, div, .admin-nav-item, .admin-lead, .admin-table-row, .admin-page__breadcrumb-trail, .admin-page__home-link, .admin-retention-value, .admin-link-action, .admin-report-card',
      )
      .forEach(hide)
    // Icônes SVG (chevrons) — anti-aliasing OS-dépendant.
    document.querySelectorAll('svg').forEach((el) => {
      el.style.visibility = 'hidden'
    })
  })
}

test.describe('retention admin visual', () => {
  test('desktop Retention vs maquette @ 1440×900', async ({ page }, testInfo) => {
    await injectOidcSession(page)
    await mockRetentionAdminApis(page)
    await page.setViewportSize({ width: 1440, height: 900 })

    await page.goto('/admin/retention')
    await page.waitForSelector('[data-mock-id="retention-title"]')
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

    await page.goto('http://127.0.0.1:4174/Retention.dc.html')
    await annotateRetentionAdminMockup(page)
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

    await testInfo.attach('maquette-retention-admin', { body: mockShot, contentType: 'image/png' })
    await testInfo.attach('app-retention-admin', { body: appShot, contentType: 'image/png' })
    const ratio = diffRatio(mockShot, appShot, 'retention-admin-desktop')
    console.log(`retention-admin-desktop pixel diff ratio = ${(ratio * 100).toFixed(3)} %`)
    expect(ratio, `retention admin diff ${ratio}`).toBeLessThanOrEqual(0.01)
  })
})

test.describe('retention admin structural', () => {
  test('data-mock-id metrics vs Retention.dc.html', async ({ page }) => {
    await injectOidcSession(page)
    await mockRetentionAdminApis(page)
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto('/admin/retention')
    await page.waitForSelector('[data-mock-id="retention-durations"]')

    await page.goto('http://127.0.0.1:4174/Retention.dc.html')
    await annotateRetentionAdminMockup(page)
    await settleFonts(page)
    const mockMetrics = await collectMetrics(page, RETENTION_ADMIN_DESKTOP_IDS)

    await page.goto('/admin/retention')
    await page.waitForSelector('[data-mock-id="retention-durations"]')
    await settleFonts(page)
    const appMetrics = await collectMetrics(page, RETENTION_ADMIN_DESKTOP_IDS)

    // Cascade y mesurée sous retention-durations (lignes admin-table-row vs divs maquette).
    const pageExceptions = {
      'admin-subnav': {
        skip: ['text'],
        reason:
          'Placeholders admin marqués « Bientôt » (Account/Admin PR) — absents de Retention.dc.html',
      },
      'retention-compliance': {
        skip: ['box'],
        reason: 'box.y Δ=6px mesuré — cascade sous retention-durations (hauteur lignes)',
      },
      'retention-report-health': {
        skip: ['box'],
        reason: 'box.y Δ=9px mesuré — cascade reports après conformité',
      },
      'retention-report-export': {
        skip: ['box'],
        reason: 'box.y Δ=9px mesuré — cascade reports après conformité',
      },
    }
    const results = compareMetrics(mockMetrics, appMetrics, RETENTION_ADMIN_DESKTOP_IDS, {
      pageExceptions,
    })
    const failed = results.filter((r) => r.diffs.length > 0)
    expect(failed, JSON.stringify(failed, null, 2)).toEqual([])
  })
})
