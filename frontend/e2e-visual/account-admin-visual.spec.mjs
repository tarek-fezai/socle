// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
/**
 * /account et /admin vs Account.dc.html & Admin.dc.html @ 1440×900.
 *
 * Pixel ≤ 1 % sur zones hautes stables (clip). Écarts bas de page documentés
 * individuellement (sessions/PAT/historique export ; domaines/mapping/langues).
 */
import { test, expect } from '@playwright/test'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { PNG } from 'pngjs'
import pixelmatch from 'pixelmatch'
import { assertFontsLoaded, collectMetrics, compareMetrics } from './structural-compare.mjs'
import { ME_TAREK, NOTIFICATIONS_SEED, VISUAL_NOW } from './dashboard-fixtures.mjs'
import { ADMIN_OVERVIEW_SEED } from './account-admin-fixtures.mjs'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const outDir = path.join(__dirname, 'test-results')

const AUTH_CONFIG = {
  authority: 'http://127.0.0.1:9/realms/socle',
  clientId: 'socle-frontend',
  scopes: ['openid', 'profile', 'email'],
  organizationName: 'Organisation Démo',
  displayName: 'Organisation Démo',
  idpDisplayName: 'Demo IdP',
}

const ME_AUDITEUR = { ...ME_TAREK, roles: ['AUDITEUR'] }

const ACCOUNT_IDS = ['account-title', 'account-profile']
const ADMIN_IDS = ['admin-home-title', 'admin-nav-identity']

const json = (route, body, status = 200) =>
  route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })

function diffRatio(a, b, label) {
  const img1 = PNG.sync.read(a)
  const img2 = PNG.sync.read(b)
  const { width, height } = img1
  if (img2.width !== width || img2.height !== height) {
    throw new Error(`size mismatch ${label}: ${width}x${height} vs ${img2.width}x${img2.height}`)
  }
  const diff = new PNG({ width, height })
  const n = pixelmatch(img1.data, img2.data, diff.data, width, height, { threshold: 0.1 })
  const ratio = n / (width * height)
  fs.mkdirSync(outDir, { recursive: true })
  fs.writeFileSync(path.join(outDir, `${label}-diff.png`), PNG.sync.write(diff))
  return ratio
}

async function injectOidcSession(page) {
  await page.addInitScript(
    ({ authority, clientId, now }) => {
      Date.now = () => now
      sessionStorage.setItem(
        `oidc.user:${authority}:${clientId}`,
        JSON.stringify({
          access_token: 'visual-access-token',
          token_type: 'Bearer',
          expires_at: Math.floor(now / 1000) + 3600,
          profile: { sub: 'tarek-visual', name: 'Tarek Fezai' },
        }),
      )
    },
    { authority: AUTH_CONFIG.authority, clientId: AUTH_CONFIG.clientId, now: VISUAL_NOW },
  )
}

async function mockApis(page, me) {
  await page.route('**/api/v1/public/auth-config', (route) => json(route, AUTH_CONFIG))
  await page.route('**/api/v1/me', (route) => json(route, me))
  await page.route('**/api/v1/me/export', (route) => json(route, { profile: { email: me.email } }))
  await page.route('**/api/v1/notifications**', (route) => json(route, NOTIFICATIONS_SEED))
  await page.route('**/api/v1/admin/overview', (route) => json(route, ADMIN_OVERVIEW_SEED))
}

async function settleFonts(page) {
  await assertFontsLoaded(page)
}

async function maskGlyphs(page, selector) {
  await page.evaluate((sel) => {
    document.querySelectorAll(sel).forEach((el) => {
      el.style.color = 'transparent'
      el.style.webkitTextFillColor = 'transparent'
      el.style.textShadow = 'none'
    })
  }, selector)
}

async function annotateAccountMockup(page) {
  await page.evaluate(() => {
    const h1 = document.querySelector('h1')
    if (h1) h1.setAttribute('data-mock-id', 'account-title')
    const profile = h1?.nextElementSibling
    if (profile) profile.setAttribute('data-mock-id', 'account-profile')
  })
}

async function annotateAdminMockup(page) {
  await page.evaluate(() => {
    const h1 = document.querySelector('h1')
    if (h1) h1.setAttribute('data-mock-id', 'admin-home-title')
    const nav = [...document.querySelectorAll('a')].find((a) =>
      /Identité/.test(a.textContent || ''),
    )
    if (nav) nav.setAttribute('data-mock-id', 'admin-nav-identity')
  })
}

test.describe('account admin visual', () => {
  test('desktop Account (admin) — clip haut ≤ 1 %', async ({ page }, testInfo) => {
    await injectOidcSession(page)
    await mockApis(page, ME_TAREK)
    await page.setViewportSize({ width: 1440, height: 900 })

    await page.goto('/account')
    await page.waitForSelector('[data-testid="account-page"]')
    await page.evaluate(() => {
      document.documentElement.style.overflow = 'hidden'
      document.body.style.overflow = 'hidden'
      const root = document.querySelector('.account-page')
      if (root) {
        root.style.width = '1440px'
        root.style.height = '900px'
      }
    })
    await settleFonts(page)
    await maskGlyphs(
      page,
      '.account-title, .account-profile-name, .account-profile-meta, .account-section-title, .account-theme-label, a, button, span',
    )
    await page.waitForTimeout(120)
    const clip = { x: 0, y: 0, width: 1440, height: 360 }
    const appShot = await page.screenshot({ fullPage: false, clip })
    fs.mkdirSync(outDir, { recursive: true })
    fs.writeFileSync(path.join(outDir, 'account-desktop-app.png'), appShot)

    await page.goto('http://127.0.0.1:4174/Account.dc.html')
    await annotateAccountMockup(page)
    await settleFonts(page)
    await maskGlyphs(page, 'h1, div, span, a, button, p')
    await page.waitForTimeout(120)
    const mockShot = await page.screenshot({ fullPage: false, clip })
    fs.writeFileSync(path.join(outDir, 'account-desktop-maquette.png'), mockShot)

    const ratio = diffRatio(mockShot, appShot, 'account-desktop')
    console.log(`account-desktop (clip 360) pixel diff = ${(ratio * 100).toFixed(3)} %`)
    testInfo.annotations.push({
      type: 'measured-exception',
      description:
        'Hors clip : sessions fictives, PAT listés, historique export de la maquette absents (spec — aucune donnée inventée).',
    })
    expect(ratio, `account clip diff ${ratio}`).toBeLessThanOrEqual(0.01)
  })

  test('desktop Admin home — clip OIDC ≤ 1 %', async ({ page }, testInfo) => {
    await injectOidcSession(page)
    await mockApis(page, ME_TAREK)
    await page.setViewportSize({ width: 1440, height: 900 })

    await page.goto('/admin')
    await page.waitForSelector('[data-mock-id="admin-home-oidc"]')
    await page.evaluate(() => {
      document.documentElement.style.overflow = 'hidden'
      document.body.style.overflow = 'hidden'
      const admin = document.querySelector('.admin-page')
      if (admin) {
        admin.style.width = '1440px'
        admin.style.height = '900px'
      }
    })
    await settleFonts(page)
    await maskGlyphs(
      page,
      'h1, p, span, a, .admin-nav-item, .admin-mono, .admin-rail__label, .admin-rail__value, .admin-rail__text, .admin-lead',
    )
    await page.waitForTimeout(120)
    // Fil d'Ariane + titre + carte OIDC (sous-nav masquée hors clip x)
    const clip = { x: 240, y: 0, width: 920, height: 320 }
    const appShot = await page.screenshot({ fullPage: false, clip })
    fs.mkdirSync(outDir, { recursive: true })
    fs.writeFileSync(path.join(outDir, 'admin-desktop-app.png'), appShot)

    await page.goto('http://127.0.0.1:4174/Admin.dc.html')
    await annotateAdminMockup(page)
    await settleFonts(page)
    await maskGlyphs(page, 'h1, p, span, a, div')
    await page.waitForTimeout(120)
    const mockShot = await page.screenshot({ fullPage: false, clip })
    fs.writeFileSync(path.join(outDir, 'admin-desktop-maquette.png'), mockShot)

    const ratio = diffRatio(mockShot, appShot, 'admin-desktop')
    console.log(`admin-desktop (clip) pixel diff = ${(ratio * 100).toFixed(3)} %`)
    testInfo.annotations.push({
      type: 'measured-exception',
      description:
        'Hors clip : domaines autorisés, mapping rôles, langues org. de la maquette → Bientôt (pas d’endpoint).',
    })
    expect(ratio, `admin clip diff ${ratio}`).toBeLessThanOrEqual(0.01)
  })

  test('Account structural — titre / profil', async ({ page }) => {
    await injectOidcSession(page)
    await mockApis(page, ME_TAREK)
    await page.setViewportSize({ width: 1440, height: 900 })

    await page.goto('http://127.0.0.1:4174/Account.dc.html')
    await annotateAccountMockup(page)
    await settleFonts(page)
    const mockMetrics = await collectMetrics(page, ACCOUNT_IDS)

    await page.goto('/account')
    await page.waitForSelector('[data-testid="account-page"]')
    await settleFonts(page)
    const appMetrics = await collectMetrics(page, ACCOUNT_IDS)

    const results = compareMetrics(mockMetrics, appMetrics, ACCOUNT_IDS, { pageExceptions: {} })
    const failed = results.filter((r) => r.diffs.length > 0)
    expect(failed, JSON.stringify(failed, null, 2)).toEqual([])
  })

  test('Admin structural — titre / nav Identité', async ({ page }) => {
    await injectOidcSession(page)
    await mockApis(page, ME_TAREK)
    await page.setViewportSize({ width: 1440, height: 900 })

    await page.goto('http://127.0.0.1:4174/Admin.dc.html')
    await annotateAdminMockup(page)
    await settleFonts(page)
    const mockMetrics = await collectMetrics(page, ADMIN_IDS)

    await page.goto('/admin')
    await page.waitForSelector('[data-mock-id="admin-home-title"]')
    await settleFonts(page)
    const appMetrics = await collectMetrics(page, ADMIN_IDS)

    const results = compareMetrics(mockMetrics, appMetrics, ADMIN_IDS, { pageExceptions: {} })
    const failed = results.filter((r) => r.diffs.length > 0)
    expect(failed, JSON.stringify(failed, null, 2)).toEqual([])
  })

  test('Account non-admin : pas de carte Organisation', async ({ page }) => {
    await injectOidcSession(page)
    await mockApis(page, ME_AUDITEUR)
    await page.goto('/account')
    await page.waitForSelector('[data-testid="account-page"]')
    await expect(page.getByTestId('account-org-admin')).toHaveCount(0)
  })
})
