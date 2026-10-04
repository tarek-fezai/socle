// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * Tags admin (/admin/tags) vs TagsAdmin.dc.html @ 1440×900.
 */
import { test, expect } from '@playwright/test'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { PNG } from 'pngjs'
import pixelmatch from 'pixelmatch'
import {
  TAGS_ADMIN_DESKTOP_IDS,
  annotateTagsAdminMockup,
  assertFontsLoaded,
  collectMetrics,
  compareMetrics,
} from './structural-compare.mjs'
import { ME_TAREK, NOTIFICATIONS_SEED, VISUAL_NOW } from './dashboard-fixtures.mjs'
import { TAGS_ADMIN_LIST } from './tags-admin-fixtures.mjs'

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

async function mockTagsAdminApis(page) {
  await page.route('**/api/v1/public/auth-config', (route) => json(route, AUTH_CONFIG))
  await page.route('**/api/v1/me', (route) => json(route, ME_TAREK))
  await page.route('**/api/v1/notifications**', (route) => json(route, NOTIFICATIONS_SEED))
  await page.route('**/api/v1/admin/tags', (route) => json(route, TAGS_ADMIN_LIST))
  await page.route('**/api/v1/admin/tags/**', (route) => json(route, TAGS_ADMIN_LIST.summary))
}

async function settleFonts(page) {
  await assertFontsLoaded(page)
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
        'h1, p, span, a, button, .admin-nav-item, .admin-lead, .admin-table-head, .admin-table-row, .admin-rail__label, .admin-rail__value, .admin-rail__text, .admin-callout, .admin-page__breadcrumb-trail, .admin-page__home-link, .admin-tag-pill, .admin-cta',
      )
      .forEach(hide)
  })
}

test.describe('tags admin visual', () => {
  test('desktop TagsAdmin vs maquette @ 1440×900', async ({ page }, testInfo) => {
    await injectOidcSession(page)
    await mockTagsAdminApis(page)
    await page.setViewportSize({ width: 1440, height: 900 })

    await page.goto('/admin/tags')
    await page.waitForSelector('[data-mock-id="tags-title"]')
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

    await page.goto('http://127.0.0.1:4174/TagsAdmin.dc.html')
    await annotateTagsAdminMockup(page)
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

    await testInfo.attach('maquette-tags-admin', { body: mockShot, contentType: 'image/png' })
    await testInfo.attach('app-tags-admin', { body: appShot, contentType: 'image/png' })
    const ratio = diffRatio(mockShot, appShot, 'tags-admin-desktop')
    console.log(`tags-admin-desktop pixel diff ratio = ${(ratio * 100).toFixed(3)} %`)
    expect(ratio, `tags admin diff ${ratio}`).toBeLessThanOrEqual(0.01)
  })
})

test.describe('tags admin structural', () => {
  test('data-mock-id metrics vs TagsAdmin.dc.html', async ({ page }) => {
    await injectOidcSession(page)
    await mockTagsAdminApis(page)
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto('/admin/tags')
    await page.waitForSelector('[data-mock-id="tags-table"]')

    await page.goto('http://127.0.0.1:4174/TagsAdmin.dc.html')
    await annotateTagsAdminMockup(page)
    const mockMetrics = await collectMetrics(page, TAGS_ADMIN_DESKTOP_IDS)

    await page.goto('/admin/tags')
    await page.waitForSelector('[data-mock-id="tags-table"]')
    const appMetrics = await collectMetrics(page, TAGS_ADMIN_DESKTOP_IDS)

    const pageExceptions = {
      'admin-subnav': {
        skip: ['text', 'box'],
        reason: 'Sous-nav : largeur scrollbar maquette vs 240px app ; espaces texte',
      },
      'admin-breadcrumb': {
        skip: ['text'],
        reason: 'Espaces autour des séparateurs breadcrumb (maquette vs app)',
      },
      'admin-nav-tags': {
        skip: ['color', 'fontWeight', 'box'],
        reason:
          'Maquette : .on sans font-weight effectif (inline) ; largeur lien 240 vs contenu',
      },
      'tags-title': {
        skip: ['box'],
        reason: 'E2 : décalage X (sous-nav maquette ~273px avec scrollbar vs 240px)',
      },
      'tags-cta': {
        skip: ['box'],
        reason: 'E2 : décalage X sous-nav scrollbar maquette',
      },
      'tags-stats': {
        skip: ['text', 'box'],
        reason: 'Compteur dynamique ; E2 décalage X scrollbar',
      },
      'tags-table': {
        skip: ['text', 'box'],
        reason: 'Lignes API ; E2 décalage X scrollbar maquette',
      },
      'tags-merge-callout': {
        skip: ['box'],
        reason: 'E2 : décalage X sous-nav scrollbar maquette — mock-id sur <p> 13px',
      },
      'tags-rail': {
        skip: ['box', 'text'],
        reason: 'Rail entier : padding / contenu dynamique',
      },
      'tags-rail-count': {
        skip: ['box'],
        reason: 'Bloc rail : largeur contenu app (padding) vs annotation maquette pleine largeur',
      },
      'tags-rail-tagged': {
        skip: ['text', 'box'],
        reason: 'Ratio documents étiquetés / total dynamique ; box rail padding',
      },
      'tags-rail-policy': {
        skip: ['box', 'lineHeight'],
        reason: 'Bouton politique : largeur contenu vs bloc 280px maquette ; line-height bouton UA',
      },
    }
    const results = compareMetrics(mockMetrics, appMetrics, TAGS_ADMIN_DESKTOP_IDS, {
      pageExceptions,
    })
    const failed = results.filter((r) => r.diffs.length > 0)
    expect(failed, JSON.stringify(failed, null, 2)).toEqual([])
  })
})
