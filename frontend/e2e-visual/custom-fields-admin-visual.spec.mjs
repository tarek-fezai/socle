// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
/**
 * Custom fields admin (/admin/custom-fields) vs CustomFields.dc.html (liste + rail).
 */
import { test, expect } from '@playwright/test'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { PNG } from 'pngjs'
import pixelmatch from 'pixelmatch'
import {
  CUSTOM_FIELDS_ADMIN_DESKTOP_IDS,
  annotateCustomFieldsAdminMockup,
  assertFontsLoaded,
  collectMetrics,
  compareMetrics,
} from './structural-compare.mjs'
import { ME_TAREK, NOTIFICATIONS_SEED, VISUAL_NOW } from './dashboard-fixtures.mjs'
import {
  CUSTOM_FIELDS_ADMIN_LIST,
  SPACE_CONFORMITE,
  SPACE_INFRA,
} from './custom-fields-admin-fixtures.mjs'

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
  fs.writeFileSync(path.join(outDir, `${label}-diff.png`), PNG.sync.write(diff))
  return n / (width * height)
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

async function mockCustomFieldsAdminApis(page) {
  await page.route('**/api/v1/public/auth-config', (route) => json(route, AUTH_CONFIG))
  await page.route('**/api/v1/me', (route) => json(route, ME_TAREK))
  await page.route('**/api/v1/notifications**', (route) => json(route, NOTIFICATIONS_SEED))
  await page.route('**/api/v1/spaces', (route) =>
    json(route, [SPACE_CONFORMITE, SPACE_INFRA]),
  )
  await page.route('**/api/v1/admin/custom-fields', (route) => json(route, CUSTOM_FIELDS_ADMIN_LIST))
}

/** Masque les glyphes (OS) — le structural-compare valide le texte. */
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
        'h1, p, span, a, button, li, .admin-nav-item, .admin-lead, .admin-table-head, .admin-table-row, .admin-rail__label, .admin-rail__text, .admin-page__breadcrumb-trail, .admin-page__home-link, .admin-cta, .admin-type-chip, .mono',
      )
      .forEach(hide)
  })
}

test.describe('custom fields admin visual', () => {
  test('desktop CustomFields (liste) vs maquette @ 1440×900', async ({ page }, testInfo) => {
    await injectOidcSession(page)
    await mockCustomFieldsAdminApis(page)
    await page.setViewportSize({ width: 1440, height: 900 })

    await page.goto('/admin/custom-fields')
    await page.waitForSelector('[data-mock-id="custom-fields-title"]')
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
    await assertFontsLoaded(page)
    await maskAdminGlyphs(page)
    await page.waitForTimeout(120)
    const appShot = await page.screenshot({
      fullPage: false,
      clip: { x: 0, y: 0, width: 1440, height: 900 },
    })

    await page.goto('http://127.0.0.1:4174/CustomFields.dc.html')
    await annotateCustomFieldsAdminMockup(page)
    await page.evaluate(() => {
      const root = document.querySelector('body div[style*="1440px"]') || document.body.firstElementChild
      if (root) {
        root.style.width = '1440px'
        root.style.height = '900px'
      }
      const builderBlock = Array.from(root?.querySelectorAll('div') ?? []).find((d) =>
        (d.textContent || '').includes('Aperçu du constructeur'),
      )
      if (builderBlock) builderBlock.style.display = 'none'
      const actions = root?.querySelector('a.cta[href="CustomFields.dc.html"]')
      if (actions?.parentElement?.parentElement?.style) {
        const row = actions.closest('div[style*="justify-content: flex-end"]')
        if (row) row.style.display = 'none'
      }
    })
    await assertFontsLoaded(page)
    await maskAdminGlyphs(page)
    await page.waitForTimeout(120)
    const mockShot = await page.screenshot({
      fullPage: false,
      clip: { x: 0, y: 0, width: 1440, height: 900 },
    })

    await testInfo.attach('maquette-custom-fields-admin', { body: mockShot, contentType: 'image/png' })
    await testInfo.attach('app-custom-fields-admin', { body: appShot, contentType: 'image/png' })
    const ratio = diffRatio(mockShot, appShot, 'custom-fields-admin-desktop')
    console.log(`custom-fields-admin-desktop pixel diff ratio = ${(ratio * 100).toFixed(3)} %`)
    expect(ratio, `custom fields admin diff ${ratio}`).toBeLessThanOrEqual(0.01)
  })
})

test.describe('custom fields admin structural', () => {
  test('data-mock-id metrics vs CustomFields.dc.html (liste)', async ({ page }) => {
    await injectOidcSession(page)
    await mockCustomFieldsAdminApis(page)
    await page.setViewportSize({ width: 1440, height: 900 })

    await page.goto('http://127.0.0.1:4174/CustomFields.dc.html')
    await annotateCustomFieldsAdminMockup(page)
    await assertFontsLoaded(page)
    const layout = await page.evaluate(() => {
      const sub = document.querySelector('[data-mock-id="admin-subnav"]')
      const title = document.querySelector('[data-mock-id="custom-fields-title"]')
      const cs = sub ? getComputedStyle(sub) : null
      const r = sub?.getBoundingClientRect()
      const t = title?.getBoundingClientRect()
      return {
        boxSizing: cs?.boxSizing,
        subnavWidth: r?.width,
        mainContentX: t?.x,
      }
    })
    console.log('custom-fields maquette layout', JSON.stringify(layout))
    const mockMetrics = await collectMetrics(page, CUSTOM_FIELDS_ADMIN_DESKTOP_IDS)

    await page.goto('/admin/custom-fields')
    await page.waitForSelector('[data-mock-id="custom-fields-table"]')
    await assertFontsLoaded(page)
    const appLayout = await page.evaluate(() => {
      const sub = document.querySelector('[data-mock-id="admin-subnav"]')
      const title = document.querySelector('[data-mock-id="custom-fields-title"]')
      const cs = sub ? getComputedStyle(sub) : null
      const r = sub?.getBoundingClientRect()
      const t = title?.getBoundingClientRect()
      return {
        boxSizing: cs?.boxSizing,
        subnavWidth: r?.width,
        mainContentX: t?.x,
      }
    })
    console.log('custom-fields app layout', JSON.stringify(appLayout))
    const appMetrics = await collectMetrics(page, CUSTOM_FIELDS_ADMIN_DESKTOP_IDS)

    const results = compareMetrics(mockMetrics, appMetrics, CUSTOM_FIELDS_ADMIN_DESKTOP_IDS)
    const failed = results.filter((r) => r.diffs.length > 0)
    expect(failed, JSON.stringify(failed, null, 2)).toEqual([])
  })
})
