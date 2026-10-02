// SPDX-License-Identifier: AGPL-3.0-or-later
import { test, expect } from '@playwright/test'
import fs from 'node:fs'
import path from 'node:path'
import { PNG } from 'pngjs'
import pixelmatch from 'pixelmatch'
import { fileURLToPath } from 'node:url'
import {
  DASHBOARD_DESKTOP_IDS,
  DASHBOARD_MOBILE_IDS,
  MOBILE_MENU_IDS,
  annotateDashboardMockup,
  annotateMobileDashboardMockup,
  annotateMobileMenuMockup,
  assertFontsLoaded,
  collectMetrics,
  compareMetrics,
} from './structural-compare.mjs'
import {
  FAVORITES_SEED,
  HOME_SEED,
  ME_TAREK,
  NOTIFICATIONS_SEED,
  SPACE_IDENTITE,
  SPACE_INFRA,
  TREE_IDENTITE,
  TREE_INFRA,
  VISUAL_NOW,
} from './dashboard-fixtures.mjs'

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

async function mockAuthConfig(page) {
  await page.route('**/api/v1/public/auth-config', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(AUTH_CONFIG),
    })
  })
}

async function injectOidcSession(page) {
  await page.addInitScript(
    ({ authority, clientId, now }) => {
      Date.now = () => now
      const key = `oidc.user:${authority}:${clientId}`
      const exp = Math.floor(now / 1000) + 3600
      const user = {
        id_token: 'visual.id',
        session_state: 'visual',
        access_token: 'visual-access-token',
        refresh_token: 'visual-refresh',
        token_type: 'Bearer',
        scope: 'openid profile email',
        profile: {
          sub: 'tarek-visual',
          name: 'Tarek Fezai',
          preferred_username: 'tarek',
          given_name: 'Tarek',
        },
        expires_at: exp,
      }
      sessionStorage.setItem(key, JSON.stringify(user))
      localStorage.setItem(
        'socle.shell.expandedSpaces',
        JSON.stringify([
          's0000001-0000-4000-8000-000000000001',
          's0000001-0000-4000-8000-000000000002',
        ]),
      )
    },
    { authority: AUTH_CONFIG.authority, clientId: AUTH_CONFIG.clientId, now: VISUAL_NOW },
  )
}

async function mockDashboardApis(page) {
  await page.route('**/api/v1/me', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(ME_TAREK),
    })
  })
  await page.route('**/api/v1/home', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(HOME_SEED),
    })
  })
  await page.route('**/api/v1/favorites**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(FAVORITES_SEED),
    })
  })
  await page.route('**/api/v1/notifications**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(NOTIFICATIONS_SEED),
    })
  })
  await page.route('**/api/v1/spaces', async (route) => {
    if (route.request().method() !== 'GET') {
      await route.continue()
      return
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([SPACE_IDENTITE, SPACE_INFRA]),
    })
  })
  await page.route(`**/api/v1/spaces/${SPACE_IDENTITE.id}/tree**`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(TREE_IDENTITE),
    })
  })
  await page.route(`**/api/v1/spaces/${SPACE_INFRA.id}/tree**`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(TREE_INFRA),
    })
  })
  await page.route('**/api/v1/search**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ query: '', results: [], total: 0 }),
    })
  })
}

function diffRatio(a, b, label) {
  const imgA = PNG.sync.read(a)
  const imgB = PNG.sync.read(b)
  if (imgA.width !== imgB.width || imgA.height !== imgB.height) {
    throw new Error(
      `${label}: size mismatch ${imgA.width}x${imgA.height} vs ${imgB.width}x${imgB.height}`,
    )
  }
  const diff = new PNG({ width: imgA.width, height: imgA.height })
  const mismatched = pixelmatch(imgA.data, imgB.data, diff.data, imgA.width, imgA.height, {
    threshold: 0.2,
    includeAA: false,
  })
  fs.mkdirSync(outDir, { recursive: true })
  fs.writeFileSync(path.join(outDir, `${label}-maquette.png`), a)
  fs.writeFileSync(path.join(outDir, `${label}-app.png`), b)
  fs.writeFileSync(path.join(outDir, `${label}-diff.png`), PNG.sync.write(diff))
  return mismatched / (imgA.width * imgA.height)
}

async function settleFonts(page) {
  await page.evaluate(() => document.fonts.ready)
}

/** Mask glyph rasters for OS-stable chrome comparison. */
async function maskDashboardGlyphs(page, mode) {
  await page.evaluate((m) => {
    const hide = (el) => {
      if (!el) return
      el.style.color = 'transparent'
      el.style.webkitTextFillColor = 'transparent'
      el.style.textShadow = 'none'
    }
    if (m === 'app' || m === 'mock') {
      document.querySelectorAll('h1, .home-title, .home-subtitle, .home-kpi-value, .home-kpi-label').forEach(hide)
      document.querySelectorAll('.home-card-title, .home-card-meta, .home-card-action, .home-section-title').forEach(hide)
      document.querySelectorAll('.home-activity-row, .shell-nav-link, .shell-nav-item, .shell-tree-section').forEach(hide)
      document.querySelectorAll('.shell-logo-name, .shell-user-name, .shell-cta, .shell-search-chip').forEach(hide)
      document.querySelectorAll('.shell-mobile-brand, .shell-tab span, .shell-drawer-link, .shell-drawer-space-title').forEach(hide)
      document.querySelectorAll('.shell-drawer-space-sub, .shell-drawer-folder, .shell-drawer-trash, .shell-drawer-user span').forEach(hide)
      document.querySelectorAll('.shell-drawer-logo-name, .shell-drawer-search, .shell-drawer-action').forEach(hide)
    }
  }, mode)
}

async function prepAuthenticatedHome(page) {
  await mockAuthConfig(page)
  await injectOidcSession(page)
  await mockDashboardApis(page)
}

test.describe('dashboard visual parity', () => {
  test('desktop Home vs Dashboard.dc.html @ 1440×900', async ({ page }, testInfo) => {
    await prepAuthenticatedHome(page)
    await page.setViewportSize({ width: 1440, height: 900 })

    await page.goto('/')
    await page.waitForSelector('[data-mock-id="home-greeting"]')
    await page.evaluate(() => {
      document.documentElement.style.overflow = 'hidden'
      document.body.style.margin = '0'
      document.body.style.overflow = 'hidden'
      const frame = document.querySelector('.shell-desktop')
      if (frame) {
        frame.style.width = '1440px'
        frame.style.height = '900px'
      }
      const sidebar = document.querySelector('.shell-sidebar')
      if (sidebar) {
        sidebar.style.height = '900px'
      }
      const main = document.querySelector('.shell-main-col')
      if (main) main.style.height = '900px'
    })
    await settleFonts(page)
    await maskDashboardGlyphs(page, 'app')
    await page.waitForTimeout(120)
    const appShot = await page.screenshot({
      fullPage: false,
      clip: { x: 0, y: 0, width: 1440, height: 900 },
    })

    await page.goto('http://127.0.0.1:4174/Dashboard.dc.html')
    await page.evaluate(() => {
      const root =
        document.querySelector('body div[style*="1440px"]') || document.querySelector('body > div')
      if (root) {
        root.style.width = '1440px'
        root.style.height = '900px'
      }
    })
    await settleFonts(page)
    await maskDashboardGlyphs(page, 'mock')
    await page.waitForTimeout(120)
    const mockShot = await page.screenshot({
      fullPage: false,
      clip: { x: 0, y: 0, width: 1440, height: 900 },
    })

    await testInfo.attach('maquette-dashboard', { body: mockShot, contentType: 'image/png' })
    await testInfo.attach('app-dashboard', { body: appShot, contentType: 'image/png' })
    const ratio = diffRatio(mockShot, appShot, 'dashboard-desktop')
    expect(ratio, `desktop dashboard diff ${ratio}`).toBeLessThanOrEqual(0.02)
  })

  test('mobile Home vs MobileDashboard.dc.html @ 390×844', async ({ page }, testInfo) => {
    await prepAuthenticatedHome(page)
    await page.setViewportSize({ width: 390, height: 844 })

    await page.goto('http://127.0.0.1:4174/MobileDashboard.dc.html')
    await page.evaluate(() => {
      const root = Array.from(document.querySelectorAll('div')).find((d) =>
        (d.getAttribute('style') || '').includes('390px'),
      )
      if (root) {
        root.style.position = 'fixed'
        root.style.left = '0'
        root.style.top = '0'
        root.style.width = '390px'
        root.style.height = '844px'
        root.style.boxSizing = 'border-box'
      }
    })
    await settleFonts(page)
    await maskDashboardGlyphs(page, 'mock')
    await page.waitForTimeout(100)
    const mockShot = await page.locator('div[style*="390px"]').first().screenshot()

    await page.goto('/')
    await page.waitForSelector('[data-mock-id="home-greeting"]')
    await page.evaluate(() => {
      document.body.style.margin = '0'
      const root = document.querySelector('.shell-root')
      if (root) {
        root.style.width = '390px'
        root.style.height = '844px'
        root.style.overflow = 'hidden'
      }
      const desk = document.querySelector('.shell-desktop')
      if (desk) {
        desk.style.width = '390px'
        desk.style.height = '844px'
      }
    })
    await settleFonts(page)
    await maskDashboardGlyphs(page, 'app')
    await page.waitForTimeout(100)
    const appShot = await page.locator('.shell-root').screenshot()

    const ratio = diffRatio(mockShot, appShot, 'dashboard-mobile')
    expect(ratio, `mobile dashboard diff ${ratio}`).toBeLessThanOrEqual(0.03)
  })

  test('mobile menu vs MobileMenu.dc.html @ 390×844', async ({ page }, testInfo) => {
    await prepAuthenticatedHome(page)
    await page.setViewportSize({ width: 390, height: 844 })

    await page.goto('/')
    await page.waitForSelector('[data-testid="mobile-menu-btn"]')
    await page.click('[data-testid="mobile-menu-btn"]')
    await page.waitForSelector('[data-testid="mobile-menu"]')
    await page.evaluate(() => {
      document.body.style.margin = '0'
      const root = document.querySelector('.shell-root')
      if (root) {
        root.style.width = '390px'
        root.style.height = '844px'
        root.style.overflow = 'hidden'
      }
    })
    await settleFonts(page)
    await maskDashboardGlyphs(page, 'app')
    await page.waitForTimeout(100)
    const appShot = await page.screenshot({
      fullPage: false,
      clip: { x: 0, y: 0, width: 390, height: 844 },
    })

    await page.goto('http://127.0.0.1:4174/MobileMenu.dc.html')
    await page.evaluate(() => {
      const root = Array.from(document.querySelectorAll('div')).find((d) =>
        (d.getAttribute('style') || '').includes('390px'),
      )
      if (root) {
        root.style.position = 'fixed'
        root.style.left = '0'
        root.style.top = '0'
        root.style.width = '390px'
        root.style.height = '844px'
      }
    })
    await settleFonts(page)
    await maskDashboardGlyphs(page, 'mock')
    await page.waitForTimeout(100)
    const mockShot = await page.screenshot({
      fullPage: false,
      clip: { x: 0, y: 0, width: 390, height: 844 },
    })

    const ratio = diffRatio(mockShot, appShot, 'dashboard-mobile-menu')
    expect(ratio, `mobile menu diff ${ratio}`).toBeLessThanOrEqual(0.04)
  })
})

test.describe('dashboard structural', () => {
  test('desktop Dashboard structural match', async ({ page }, testInfo) => {
    await prepAuthenticatedHome(page)
    await page.setViewportSize({ width: 1440, height: 900 })

    await page.goto('http://127.0.0.1:4174/Dashboard.dc.html')
    await settleFonts(page)
    await annotateDashboardMockup(page)
    const mockMap = await collectMetrics(page, DASHBOARD_DESKTOP_IDS)

    await page.goto('/')
    await page.waitForSelector('[data-mock-id="home-greeting"]')
    await settleFonts(page)
    const appMap = await collectMetrics(page, DASHBOARD_DESKTOP_IDS)
    const fonts = await assertFontsLoaded(page)
    expect(fonts.find((f) => f.family === 'Instrument Serif')?.loaded).toBe(true)
    expect(fonts.find((f) => f.family === 'IBM Plex Sans')?.loaded).toBe(true)

    const pageExceptions = {
      'shell-search-chip': {
        skip: ['text', 'box', 'color', 'lineHeight'],
        reason: 'Chip bouton app vs lien maquette ; libellé raccourci Ctrl+K vs ⌘K selon OS',
      },
      'shell-user': {
        skip: ['box', 'text', 'color', 'lineHeight'],
        reason: 'Bouton déconnexion vs lien Account maquette — contenu proche, boîte différente',
      },
      'shell-space-section': {
        skip: ['box'],
        reason: 'Bouton expand/collapse vs div statique maquette',
      },
      'home-subtitle': {
        skip: ['box', 'text'],
        reason: 'Sous-titre desktop+mobile dans le même nœud (textContent agrège les deux spans)',
      },
      'home-kpi-published': {
        skip: ['text', 'box'],
        reason: 'Valeur KPI dans enfant .home-kpi-value ; boîte carte vs metric exacte',
      },
      'home-kpi-pending': {
        skip: ['text', 'box'],
        reason: 'Idem KPI — texte agrégé carte + labels desktop/mobile',
      },
      'home-kpi-views': {
        skip: ['text', 'box'],
        reason: 'Idem KPI',
      },
      'home-kpi-reliability': {
        skip: ['text', 'box'],
        reason: 'Idem KPI',
      },
      'shell-header-new-doc': {
        skip: ['box', 'lineHeight'],
        reason: 'Position dépend de la largeur utile (scrollbar / outlet)',
      },
      'home-section-resume': {
        skip: ['box'],
        reason: 'Décalage vertical lié à la hauteur agrégée des KPI / sous-titre',
      },
      'home-section-approvals': {
        skip: ['box'],
        reason: 'Colonne latérale — position dépend du contenu Reprendre dynamique',
      },
      'home-section-activity': {
        skip: ['box'],
        reason: 'Position dépend du nombre de cartes Reprendre / approbations',
      },
    }
    const results = compareMetrics(mockMap, appMap, DASHBOARD_DESKTOP_IDS, { pageExceptions })
    await testInfo.attach('structural-dashboard-desktop.json', {
      body: Buffer.from(JSON.stringify(results, null, 2)),
      contentType: 'application/json',
    })
    fs.mkdirSync(outDir, { recursive: true })
    fs.writeFileSync(
      path.join(outDir, 'structural-dashboard-desktop.json'),
      JSON.stringify(results, null, 2),
    )
    const failures = results.filter((r) => r.diffs.length)
    expect(failures, JSON.stringify(failures, null, 2)).toEqual([])
  })

  test('mobile Dashboard structural match', async ({ page }, testInfo) => {
    await prepAuthenticatedHome(page)
    await page.setViewportSize({ width: 390, height: 844 })

    await page.goto('http://127.0.0.1:4174/MobileDashboard.dc.html')
    await settleFonts(page)
    await annotateMobileDashboardMockup(page)
    const mockMap = await collectMetrics(page, DASHBOARD_MOBILE_IDS)

    await page.goto('/')
    await page.waitForSelector('[data-mock-id="home-greeting"]')
    await settleFonts(page)
    const appMap = await collectMetrics(page, DASHBOARD_MOBILE_IDS)

    const pageExceptions = {
      'home-subtitle': {
        skip: ['box', 'text'],
        reason: 'Spans desktop/mobile — textContent agrège les deux variantes',
      },
      'home-kpi-published': { skip: ['text', 'box'], reason: 'Carte KPI agrégée' },
      'home-kpi-pending': { skip: ['text', 'box'], reason: 'Carte KPI agrégée' },
      'mobile-topbar': { skip: ['text', 'box'], reason: 'Conteneur top bar — enfants annotés' },
      'home-section-resume': {
        skip: ['box'],
        reason: 'Décalage vertical lié aux KPI / sous-titre',
      },
      'home-section-approvals': {
        skip: ['box'],
        reason: 'Position dépend du contenu Reprendre dynamique',
      },
    }
    const results = compareMetrics(mockMap, appMap, DASHBOARD_MOBILE_IDS, { pageExceptions })
    fs.mkdirSync(outDir, { recursive: true })
    fs.writeFileSync(
      path.join(outDir, 'structural-dashboard-mobile.json'),
      JSON.stringify(results, null, 2),
    )
    const failures = results.filter((r) => r.diffs.length)
    expect(failures, JSON.stringify(failures, null, 2)).toEqual([])
  })

  test('mobile menu structural match', async ({ page }, testInfo) => {
    await prepAuthenticatedHome(page)
    await page.setViewportSize({ width: 390, height: 844 })

    await page.goto('http://127.0.0.1:4174/MobileMenu.dc.html')
    await settleFonts(page)
    await annotateMobileMenuMockup(page)
    const mockMap = await collectMetrics(page, MOBILE_MENU_IDS)

    await page.goto('/')
    await page.click('[data-testid="mobile-menu-btn"]')
    await page.waitForSelector('[data-testid="mobile-menu"]')
    await settleFonts(page)
    const appMap = await collectMetrics(page, MOBILE_MENU_IDS)

    const pageExceptions = {
      'mobile-menu': {
        skip: ['text', 'box'],
        reason: 'Drawer app = overlay root ; maquette = panneau 320px — texte arbre dynamique',
      },
      'mobile-menu-space': {
        skip: ['box', 'text', 'color', 'lineHeight'],
        reason: 'Bouton cycle espaces vs lien Spaces maquette',
      },
      'mobile-menu-user': {
        skip: ['box', 'text', 'color', 'lineHeight'],
        reason: 'Lien /team vs Account.dc.html',
      },
    }
    const results = compareMetrics(mockMap, appMap, MOBILE_MENU_IDS, { pageExceptions })
    fs.mkdirSync(outDir, { recursive: true })
    fs.writeFileSync(
      path.join(outDir, 'structural-mobile-menu.json'),
      JSON.stringify(results, null, 2),
    )
    const failures = results.filter((r) => r.diffs.length)
    expect(failures, JSON.stringify(failures, null, 2)).toEqual([])
  })
})
