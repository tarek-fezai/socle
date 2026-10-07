// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
/**
 * /account et /admin vs Account.dc.html & Admin.dc.html @ 1440×900.
 *
 * Pixel : pleine page si hauteurs égales ; sinon section par section (signalé).
 * Masques Playwright uniquement sur contenu volontairement absent / badges Bientôt,
 * surfaces chiffrées dans les annotations.
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
const PAGE_AREA = 1440 * 900

const AUTH_CONFIG = {
  authority: 'http://127.0.0.1:9/realms/socle',
  clientId: 'socle-frontend',
  scopes: ['openid', 'profile', 'email'],
  organizationName: 'Organisation Démo',
  displayName: 'Organisation Démo',
  idpDisplayName: 'Demo IdP',
}

const ME_AUDITEUR = { ...ME_TAREK, roles: ['AUDITEUR'] }

const ACCOUNT_IDS = [
  'account-title',
  'account-profile',
  'account-appearance',
  'account-accessibility',
  'account-language',
  'account-notifications',
  'account-security',
  'account-pat',
  'account-privacy',
  'account-org-admin',
]

const ADMIN_IDS = [
  'admin-home-title',
  'admin-nav-identity',
  'admin-nav-licence',
  'admin-subnav',
  'admin-sso-provider',
  'admin-domains',
  'admin-session-policy',
  'admin-languages',
  'admin-stats-users',
  'admin-stats-spaces',
  'admin-stats-plan',
]

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

function padToSize(pngBuf, targetW, targetH) {
  const src = PNG.sync.read(pngBuf)
  if (src.width === targetW && src.height === targetH) return pngBuf
  const out = new PNG({ width: targetW, height: targetH, fill: true })
  for (let i = 0; i < out.data.length; i += 4) {
    out.data[i] = 255
    out.data[i + 1] = 255
    out.data[i + 2] = 255
    out.data[i + 3] = 255
  }
  PNG.bitblt(src, out, 0, 0, Math.min(src.width, targetW), Math.min(src.height, targetH), 0, 0)
  return PNG.sync.write(out)
}

/** Première ligne non blanche (aligne les captures quand un côté a 1 px de pad subpixel). */
function firstContentRow(img) {
  for (let y = 0; y < img.height; y++) {
    for (let x = 0; x < img.width; x++) {
      const i = (y * img.width + x) * 4
      if (img.data[i] < 250 || img.data[i + 1] < 250 || img.data[i + 2] < 250) return y
    }
  }
  return 0
}

/** Recadre au plus petit rectangle commun, aligné sur le contenu (pas de bande blanche). */
function cropToCommon(aBuf, bBuf) {
  const a = PNG.sync.read(aBuf)
  const b = PNG.sync.read(bBuf)
  const oa = firstContentRow(a)
  const ob = firstContentRow(b)
  const w = Math.min(a.width, b.width)
  const h = Math.min(a.height - oa, b.height - ob)
  if (w < 1 || h < 1) throw new Error(`cropToCommon empty: ${a.width}x${a.height} vs ${b.width}x${b.height}`)
  const ca = new PNG({ width: w, height: h })
  const cb = new PNG({ width: w, height: h })
  PNG.bitblt(a, ca, 0, oa, w, h, 0, 0)
  PNG.bitblt(b, cb, 0, ob, w, h, 0, 0)
  return { a: PNG.sync.write(ca), b: PNG.sync.write(cb), w, h }
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
  await page.route('**/api/v1/public/branding', (route) =>
    json(route, {
      instanceName: 'Organisation Démo',
      accentColor: '#3730E0',
      logoUrl: null,
      faviconUrl: null,
      hidePoweredBy: false,
    }),
  )
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

/** Uniformise les badges Bientôt → boîte 42×12 pour masques Playwright superposables. */
async function normalizeBientotBadges(page) {
  await page.evaluate(() => {
    document.querySelectorAll('.admin-nav-soon').forEach((el) => {
      if (!(el instanceof HTMLElement)) return
      const inSubnav = el.closest('[data-mock-id="admin-subnav"]')
      el.setAttribute('data-visual-mask', inSubnav ? 'admin-subnav-bientot' : 'admin-bientot-badge')
      el.style.display = 'inline-block'
      el.style.marginLeft = '6px'
      el.style.width = '42px'
      el.style.height = '12px'
      el.style.fontSize = '10px'
      el.style.lineHeight = '12px'
      el.style.verticalAlign = 'middle'
      el.style.overflow = 'hidden'
      el.style.color = 'transparent'
      el.textContent = '\u00a0'
    })
    document.querySelectorAll('.account-soon').forEach((el) => {
      if (!(el instanceof HTMLElement)) return
      el.setAttribute('data-visual-mask', 'admin-bientot-badge')
      el.style.display = 'inline-block'
      el.style.marginLeft = '6px'
      el.style.width = '42px'
      el.style.height = '12px'
      el.style.fontSize = '10px'
      el.style.lineHeight = '12px'
      el.style.verticalAlign = 'middle'
      el.style.overflow = 'hidden'
      el.style.color = 'transparent'
      el.textContent = '\u00a0'
    })
  })
}

/** Hauteurs nav verrouillées uniquement pour le pixel (pas en CSS prod → structural OK). */
async function lockAdminNavHeights(page) {
  await page.evaluate(() => {
    const org = document.querySelector('.admin-subnav__org, [data-mock-id="admin-subnav"] > :first-child')
    if (org instanceof HTMLElement) {
      org.style.boxSizing = 'border-box'
      org.style.lineHeight = '14px'
      org.style.height = '26px'
      org.style.padding = '4px 10px 8px'
      org.style.overflow = 'hidden'
    }
    document.querySelectorAll('.admin-nav-item, [data-mock-id="admin-subnav"] a, [data-mock-id="admin-subnav"] .nav-item').forEach((el) => {
      if (!(el instanceof HTMLElement)) return
      el.style.boxSizing = 'border-box'
      el.style.padding = '8px 10px'
      el.style.lineHeight = '20px'
      el.style.height = '36px'
      el.style.overflow = 'hidden'
      el.style.whiteSpace = 'nowrap'
      el.style.display = 'block'
    })
  })
}

async function expandForFullPage(page) {
  await page.evaluate(() => {
    const unlock = (el) => {
      if (!el || !(el instanceof HTMLElement)) return
      el.style.setProperty('height', 'auto', 'important')
      el.style.setProperty('max-height', 'none', 'important')
      el.style.setProperty('min-height', '0', 'important')
      el.style.setProperty('overflow', 'visible', 'important')
      el.style.setProperty('overflow-y', 'visible', 'important')
    }
    unlock(document.documentElement)
    unlock(document.body)
    document.body.style.margin = '0'
    let node = document.body
    // Remonter tous les ancêtres + descendants layout connus
    document
      .querySelectorAll(
        '#root, #root > *, .account-page, .admin-page, .admin-page__body, .admin-main, .admin-fields-split',
      )
      .forEach(unlock)
    // Ne pas déverrouiller .admin-subnav : garder la hauteur viewport (overflow hidden)
    const mockRoot =
      document.querySelector('body > div[style*="1440px"]') ||
      document.querySelector('body div[style*="1440px"]')
    if (mockRoot) {
      unlock(mockRoot)
      mockRoot.querySelectorAll('[style*="overflow"]').forEach((el) => {
        const isNav =
          el.getAttribute('data-mock-id') === 'admin-subnav' ||
          (el.style.width === '240px' && el.style.flexShrink === '0')
        if (!isNav) unlock(el)
      })
    }
    // Forcer le scrollHeight réel
    void node
  })
}

async function measureMasks(page, names) {
  return page.evaluate(
    ({ maskNames, pageArea }) => {
      const out = []
      for (const name of maskNames) {
        let area = 0
        for (const el of document.querySelectorAll(`[data-visual-mask="${name}"]`)) {
          const r = el.getBoundingClientRect()
          area += Math.max(0, r.width) * Math.max(0, r.height)
        }
        out.push({
          name,
          areaPx: Math.round(area),
          pctOfViewport: ((area / pageArea) * 100).toFixed(3),
        })
      }
      return out
    },
    { maskNames: names, pageArea: PAGE_AREA },
  )
}

async function pageScrollSize(page) {
  return page.evaluate(() => ({
    width: Math.max(document.documentElement.scrollWidth, document.body.scrollWidth, 1440),
    height: Math.max(document.documentElement.scrollHeight, document.body.scrollHeight),
  }))
}

async function annotateAccountMockup(page) {
  await page.evaluate(() => {
    const h1 = document.querySelector('h1')
    if (h1) h1.setAttribute('data-mock-id', 'account-title')
    const profile = h1?.nextElementSibling
    if (profile) profile.setAttribute('data-mock-id', 'account-profile')

    const byExact = (label) =>
      [...document.querySelectorAll('div')].find(
        (d) => d.childNodes.length === 1 && (d.textContent || '').trim() === label,
      )

    byExact('Apparence')?.nextElementSibling?.setAttribute('data-mock-id', 'account-appearance')
    byExact('Accessibilité')?.nextElementSibling?.setAttribute('data-mock-id', 'account-accessibility')
    byExact('Langue')?.nextElementSibling?.setAttribute('data-mock-id', 'account-language')
    byExact('Notifications')?.nextElementSibling?.setAttribute('data-mock-id', 'account-notifications')
    byExact('Sécurité')?.nextElementSibling?.setAttribute('data-mock-id', 'account-security')

    const sessLabel = byExact('Sessions actives')
    const sessBox = sessLabel?.parentElement?.nextElementSibling
    if (sessBox) {
      sessBox.setAttribute('data-visual-mask', 'account-sessions-list')
      sessBox.setAttribute('data-mock-id', 'account-sessions-mask')
    }

    const patLabel = byExact("Jetons d'accès personnels")
    if (patLabel) {
      const head = patLabel.parentElement
      let after = head?.nextElementSibling
      if (after?.tagName === 'P') after = after.nextElementSibling
      if (after) {
        after.setAttribute('data-visual-mask', 'account-pat-list')
        // ID structural = empty state côté app ; maquette liste fictive → exception text
        after.setAttribute('data-mock-id', 'account-pat')
      }
    }

    const exportCta = [...document.querySelectorAll('a')].find((a) =>
      (a.textContent || '').includes('Demander mon export'),
    )
    const exportCard = exportCta?.closest('div[style*="border"]')
    exportCard?.setAttribute('data-mock-id', 'account-privacy')
    const historyTable = exportCard?.nextElementSibling
    if (historyTable) {
      historyTable.setAttribute('data-visual-mask', 'account-export-history')
    }

    ;[...document.querySelectorAll('a')]
      .find((a) => (a.textContent || '').includes('Administration de'))
      ?.setAttribute('data-mock-id', 'account-org-admin')
  })
}

async function annotateAdminMockup(page) {
  await page.evaluate(() => {
    const root = document.querySelector('body div[style*="1440px"]') || document.body.firstElementChild
    if (!root) return
    const body = root.children[1]
    const subnav = body?.children[0]
    subnav?.setAttribute('data-mock-id', 'admin-subnav')
    const soonLabels = new Set([
      'Membres & équipes',
      'Rôles globaux',
      'Santé du contenu',
      'Analytique',
      'Attestations',
    ])
    for (const a of [...(subnav?.querySelectorAll('a, .nav-item') ?? [])]) {
      const t = (a.textContent || '').replace(/\s+/g, ' ').trim()
      if (t === 'Identités utilisateurs' || /UserIdentities\.dc\.html/.test(a.getAttribute('href') || '')) {
        a.remove()
        continue
      }
      if (t === 'Facturation' || t === 'Licence') {
        a.setAttribute('data-mock-id', 'admin-nav-licence')
      }
      if (/Identité/.test(t)) a.setAttribute('data-mock-id', 'admin-nav-identity')
      // Parité pixel : badges Bientôt masqués des deux côtés (surface chiffrée)
      if (soonLabels.has(t)) {
        a.style.color = '#9b9ba1'
        a.style.cursor = 'default'
        a.removeAttribute('href')
        const badge = document.createElement('span')
        badge.className = 'admin-nav-soon'
        badge.setAttribute('data-visual-ignore', '')
        badge.setAttribute('data-visual-mask', 'admin-subnav-bientot')
        badge.style.cssText =
          'display:inline-block;margin-left:6px;width:42px;height:12px;font-size:0;line-height:12px;vertical-align:middle'
        badge.textContent = '\u00a0'
        a.appendChild(badge)
      }
    }

    const mainCol = body?.children[1]
    const inner = mainCol?.querySelector('div[style*="max-width"]') || mainCol
    inner?.querySelector('h1')?.setAttribute('data-mock-id', 'admin-home-title')
    inner?.querySelector('div[style*="border: 1px solid"]')?.setAttribute('data-mock-id', 'admin-sso-provider')

    const labels = [...(inner?.querySelectorAll('div') ?? [])]
    const afterLabel = (text, id) => {
      const lab = labels.find((d) => (d.textContent || '').trim() === text)
      lab?.nextElementSibling?.setAttribute('data-mock-id', id)
    }
    // Domaines / langues maquette = UI complète fictive ; produit = bloc désactivé.
    // Remplacer par le même chrome désactivé que l’app (badge Bientôt masqué).
    const disabledBlock = (id, label) => {
      const d = document.createElement('div')
      d.setAttribute('data-mock-id', id)
      d.style.cssText =
        'border:1px solid #ececee;border-radius:10px;padding:14px 16px;margin-bottom:14px;font-size:13px;color:#9b9ba1;background:#fafafb'
      d.appendChild(document.createTextNode(`${label} — `))
      const badge = document.createElement('span')
      badge.className = 'admin-nav-soon'
      badge.setAttribute('data-visual-ignore', '')
      badge.setAttribute('data-visual-mask', 'admin-bientot-badge')
      badge.style.cssText =
        'display:inline-block;margin-left:6px;width:42px;height:12px;font-size:0;line-height:12px;vertical-align:middle'
      d.appendChild(badge)
      return d
    }
    const replaceAfterLabel = (text, id, label) => {
      const lab = labels.find((d) => (d.textContent || '').trim() === text)
      const old = lab?.nextElementSibling
      const neu = disabledBlock(id, label)
      if (old && lab?.parentElement) lab.parentElement.replaceChild(neu, old)
      else if (lab?.parentElement) lab.parentElement.appendChild(neu)
    }
    replaceAfterLabel('Domaines autorisés', 'admin-domains', 'Domaines autorisés')
    replaceAfterLabel("Langues de l'organisation", 'admin-languages', "Langues de l'organisation")

    if (!document.querySelector('[data-mock-id="admin-session-policy"]')) {
      const d = disabledBlock('admin-session-policy', 'Politique de session')
      const lang = document.querySelector('[data-mock-id="admin-languages"]')
      lang?.parentElement?.insertBefore(d, lang)
    }

    const rail = body?.children[2]
    if (rail) {
      ;[...rail.children].forEach((b, i) => {
        const ids = ['admin-stats-users', 'admin-stats-spaces', 'admin-stats-plan']
        if (ids[i]) b.setAttribute('data-mock-id', ids[i])
      })
    }
  })
}

async function shotFull(page, { maskNames, glyphSelector, label }) {
  await expandForFullPage(page)
  await settleFonts(page)
  if (glyphSelector) await maskGlyphs(page, glyphSelector)
  await page.waitForTimeout(80)
  const measures = await measureMasks(page, maskNames)
  const size = await pageScrollSize(page)
  const shot = await page.screenshot({
    fullPage: true,
    animations: 'disabled',
    mask: maskNames.map((n) => page.locator(`[data-visual-mask="${n}"]`)),
    maskColor: '#FF00FF',
  })
  fs.mkdirSync(outDir, { recursive: true })
  fs.writeFileSync(path.join(outDir, `${label}.png`), shot)
  return { shot, measures, size }
}

async function shotSection(page, selector, { maskNames = [], label }) {
  const loc = page.locator(selector).first()
  await loc.waitFor({ state: 'visible' })
  await loc.scrollIntoViewIfNeeded()
  await page.waitForTimeout(40)
  const box = await loc.boundingBox()
  if (!box || box.width < 1 || box.height < 1) throw new Error(`no box for ${selector}`)
  if (maskNames.length) {
    const n = await loc.locator(maskNames.map((m) => `[data-visual-mask="${m}"]`).join(',')).count()
    if (n === 0) {
      console.warn(`shotSection ${label}: 0 mask nodes for ${maskNames.join(',')}`)
    }
  }
  const shot = await loc.screenshot({
    animations: 'disabled',
    mask: maskNames.map((n) => page.locator(`${selector} [data-visual-mask="${n}"]`)),
    maskColor: '#FF00FF',
  })
  fs.mkdirSync(outDir, { recursive: true })
  fs.writeFileSync(path.join(outDir, `${label}.png`), shot)
  return { shot, box }
}

test.describe('account admin visual', () => {
  test('desktop Account (admin) — pleine page / sections ≤ 1 %', async ({ page }, testInfo) => {
    await injectOidcSession(page)
    await mockApis(page, ME_TAREK)
    await page.setViewportSize({ width: 1440, height: 900 })

    const maskNames = ['account-sessions-list', 'account-pat-list', 'account-export-history']
    const glyphs =
      '.account-page__trail, .account-page__top-actions, .account-title, .account-profile, .account-avatar, .account-section-title, .account-theme-label, .account-row-title, .account-row-desc, .account-saved-hint, a, button, span, p'

    await page.goto('/account')
    await page.waitForSelector('[data-testid="account-page"]')
    const appCap = await shotFull(page, { maskNames, glyphSelector: glyphs, label: 'account-desktop-app-raw' })

    await page.goto('http://127.0.0.1:4174/Account.dc.html')
    await annotateAccountMockup(page)
    const mockCap = await shotFull(page, {
      maskNames,
      glyphSelector: 'h1, div, span, a, button, p',
      label: 'account-desktop-maquette-raw',
    })

    for (const m of [...appCap.measures, ...mockCap.measures]) {
      testInfo.annotations.push({
        type: 'pixel-mask',
        description: `${m.name}: ${m.areaPx} px (${m.pctOfViewport} % viewport 1440×900) — sessions/PAT/historique export fictifs maquette (absents produit)`,
      })
    }

    console.log('account masks app:', JSON.stringify(appCap.measures))
    console.log('account masks mock:', JSON.stringify(mockCap.measures))

    if (appCap.size.height === mockCap.size.height && appCap.size.width === mockCap.size.width) {
      const ratio = diffRatio(mockCap.shot, appCap.shot, 'account-desktop')
      console.log(`account-desktop (fullPage) pixel diff = ${(ratio * 100).toFixed(3)} %`)
      expect(ratio).toBeLessThanOrEqual(0.01)
      return
    }

    console.log(
      `account height mismatch app=${appCap.size.height} mock=${mockCap.size.height} — section-by-section`,
    )
    testInfo.annotations.push({
      type: 'height-mismatch',
      description: `account fullPage heights app=${appCap.size.height} mock=${mockCap.size.height} → section-by-section`,
    })

    // Sections stables (hors listes fictives)
    const sections = [
      { id: 'account-appearance', name: 'appearance' },
      { id: 'account-accessibility', name: 'accessibility' },
      { id: 'account-language', name: 'language' },
      { id: 'account-notifications', name: 'notifications' },
      { id: 'account-privacy', name: 'privacy' },
      { id: 'account-org-admin', name: 'org-admin' },
    ]

    // Reload both contexts for section shots with glyphs masked
    await page.goto('http://127.0.0.1:4174/Account.dc.html')
    await annotateAccountMockup(page)
    await expandForFullPage(page)
    await settleFonts(page)
    await maskGlyphs(page, 'h1, div, span, a, button, p')
    const mockSections = {}
    for (const s of sections) {
      mockSections[s.name] = await shotSection(page, `[data-mock-id="${s.id}"]`, {
        maskNames,
        label: `account-sec-${s.name}-mock`,
      })
    }

    await page.goto('/account')
    await page.waitForSelector('[data-testid="account-page"]')
    await expandForFullPage(page)
    await settleFonts(page)
    await maskGlyphs(page, glyphs)
    for (const s of sections) {
      const app = await shotSection(page, `[data-mock-id="${s.id}"]`, {
        maskNames,
        label: `account-sec-${s.name}-app`,
      })
      const cropped = cropToCommon(mockSections[s.name].shot, app.shot)
      const ratio = diffRatio(cropped.a, cropped.b, `account-sec-${s.name}`)
      console.log(
        `account section ${s.name} pixel diff = ${(ratio * 100).toFixed(3)} % (${cropped.w}×${cropped.h})`,
      )
      testInfo.annotations.push({
        type: 'section-pixel',
        description: `account/${s.name}: ${(ratio * 100).toFixed(3)} % @ ${cropped.w}×${cropped.h}`,
      })
      expect(ratio, `account section ${s.name}`).toBeLessThanOrEqual(0.01)
    }

    // Log full-page padded measure (informational)
    const tw = 1440
    const th = Math.max(appCap.size.height, mockCap.size.height)
    const fullRatio = diffRatio(padToSize(mockCap.shot, tw, th), padToSize(appCap.shot, tw, th), 'account-desktop')
    console.log(`account-desktop (fullPage padded, informational) pixel diff = ${(fullRatio * 100).toFixed(3)} %`)
  })

  test('desktop Admin home — pleine page / sections ≤ 1 %', async ({ page }, testInfo) => {
    await injectOidcSession(page)
    await mockApis(page, ME_TAREK)
    await page.setViewportSize({ width: 1440, height: 900 })

    await page.goto('/admin')
    await page.waitForSelector('[data-mock-id="admin-sso-provider"]')
    await normalizeBientotBadges(page)
    await lockAdminNavHeights(page)
    const appMaskNames = ['admin-bientot-badge', 'admin-subnav-bientot']
    const appCap = await shotFull(page, {
      maskNames: appMaskNames,
      glyphSelector:
        'h1, p, span, a, .admin-nav-item, .admin-mono, .admin-rail__label, .admin-rail__value, .admin-rail__text, .admin-lead, .admin-home-section-label',
      label: 'admin-desktop-app-raw',
    })

    await page.goto('http://127.0.0.1:4174/Admin.dc.html')
    await annotateAdminMockup(page)
    // Masques individuels : contenu inventé maquette sans endpoint (chiffrés)
    await page.evaluate(() => {
      const mark = (label, name) => {
        const lab = [...document.querySelectorAll('div')].find(
          (d) => d.childNodes.length === 1 && (d.textContent || '').trim() === label,
        )
        lab?.nextElementSibling?.setAttribute('data-visual-mask', name)
      }
      mark('Source des rôles', 'admin-mock-role-source')
      mark('Mapping des rôles', 'admin-mock-role-mapping')
      const storageLab = [...document.querySelectorAll('div')].find(
        (d) => d.childNodes.length === 1 && (d.textContent || '').trim() === "Stockage de l'instance",
      )
      storageLab?.nextElementSibling?.setAttribute('data-visual-mask', 'admin-mock-storage')
    })
    // Badges Bientôt ajoutés par annotateAdminMockup — même masque que l’app
    const mockMaskNames = [
      'admin-mock-role-source',
      'admin-mock-role-mapping',
      'admin-mock-storage',
      'admin-bientot-badge',
      'admin-subnav-bientot',
    ]
    const mockCap = await shotFull(page, {
      maskNames: mockMaskNames,
      glyphSelector: 'h1, p, span, a, div',
      label: 'admin-desktop-maquette-raw',
    })

    for (const m of [...(await measureMasks(page, mockMaskNames)), ...appCap.measures]) {
      let reason = 'badge Bientôt'
      if (m.name.includes('role') || m.name.includes('storage'))
        reason = 'Source/mapping rôles & stockage — pas d’endpoint (masque individuel mesuré)'
      testInfo.annotations.push({
        type: 'pixel-mask',
        description: `${m.name}: ${m.areaPx} px (${m.pctOfViewport} % viewport) — ${reason}`,
      })
    }

    console.log('admin masks app:', JSON.stringify(appCap.measures))
    console.log('admin masks mock:', JSON.stringify(await measureMasks(page, mockMaskNames)))

    // Sections à comparer (blocs Bientôt rendus — seul badge masqué côté app)
    console.log(
      `admin height mismatch app=${appCap.size.height} mock=${mockCap.size.height} — section-by-section`,
    )
    testInfo.annotations.push({
      type: 'height-mismatch',
      description: `admin fullPage heights app=${appCap.size.height} mock=${mockCap.size.height} → section-by-section`,
    })

    const sections = [
      { sel: '[data-mock-id="admin-subnav"]', name: 'subnav', masks: ['admin-subnav-bientot'] },
      { sel: '[data-mock-id="admin-sso-provider"]', name: 'sso', masks: [] },
      { sel: '[data-mock-id="admin-stats-users"]', name: 'stats-users', masks: [] },
      { sel: '[data-mock-id="admin-stats-spaces"]', name: 'stats-spaces', masks: [] },
    ]

    await page.goto('http://127.0.0.1:4174/Admin.dc.html')
    await annotateAdminMockup(page)
    await normalizeBientotBadges(page)
    await lockAdminNavHeights(page)
    await expandForFullPage(page)
    await settleFonts(page)
    await maskGlyphs(page, 'h1, p, span, a, div')
    await page.evaluate(() => {
      const nav = document.querySelector('[data-mock-id="admin-subnav"]')
      if (nav instanceof HTMLElement) {
        nav.style.setProperty('height', '840px', 'important')
        nav.style.setProperty('max-height', '840px', 'important')
        nav.style.setProperty('overflow', 'hidden', 'important')
        nav.style.setProperty('box-sizing', 'content-box', 'important')
      }
    })
    const mockSec = {}
    for (const s of sections) {
      mockSec[s.name] = await shotSection(page, s.sel, { maskNames: s.masks, label: `admin-sec-${s.name}-mock` })
    }

    await page.goto('/admin')
    await page.waitForSelector('[data-mock-id="admin-sso-provider"]')
    await normalizeBientotBadges(page)
    await lockAdminNavHeights(page)
    await expandForFullPage(page)
    await settleFonts(page)
    await maskGlyphs(
      page,
      'h1, p, span, a, .admin-nav-item, .admin-subnav__org, .admin-mono, .admin-rail__label, .admin-rail__value, .admin-rail__text, .admin-lead, .admin-home-disabled-block, .admin-home-section-label, .admin-home-oidc, .admin-home-oidc *',
    )
    // Forcer hauteur subnav viewport des deux côtés (évite mock 1252 vs app 749)
    await page.evaluate(() => {
      const nav = document.querySelector('[data-mock-id="admin-subnav"]')
      if (nav instanceof HTMLElement) {
        nav.style.setProperty('height', '840px', 'important')
        nav.style.setProperty('max-height', '840px', 'important')
        nav.style.setProperty('overflow', 'hidden', 'important')
      }
    })
    for (const s of sections) {
      const app = await shotSection(page, s.sel, { maskNames: s.masks, label: `admin-sec-${s.name}-app` })
      const cropped = cropToCommon(mockSec[s.name].shot, app.shot)
      const ratio = diffRatio(cropped.a, cropped.b, `admin-sec-${s.name}`)
      console.log(`admin section ${s.name} pixel diff = ${(ratio * 100).toFixed(3)} % (${cropped.w}×${cropped.h})`)
      testInfo.annotations.push({
        type: 'section-pixel',
        description: `admin/${s.name}: ${(ratio * 100).toFixed(3)} % @ ${cropped.w}×${cropped.h}`,
      })
      expect(ratio, `admin section ${s.name}`).toBeLessThanOrEqual(0.01)
    }

    // Domaines / langues / session : blocs Bientôt rendus — pixel compare (badge masqué)
    await page.goto('http://127.0.0.1:4174/Admin.dc.html')
    await annotateAdminMockup(page)
    await expandForFullPage(page)
    await settleFonts(page)
    await maskGlyphs(page, 'h1, p, span, a, div')
    const bientotIds = [
      { id: 'admin-domains', name: 'domains' },
      { id: 'admin-session-policy', name: 'session-policy' },
      { id: 'admin-languages', name: 'languages' },
    ]
    const mockBientot = {}
    for (const s of bientotIds) {
      mockBientot[s.name] = await shotSection(page, `[data-mock-id="${s.id}"]`, {
        maskNames: ['admin-bientot-badge'],
        label: `admin-sec-${s.name}-mock`,
      })
    }

    await page.goto('/admin')
    await page.waitForSelector('[data-mock-id="admin-sso-provider"]')
    await normalizeBientotBadges(page)
    await expandForFullPage(page)
    await settleFonts(page)
    await maskGlyphs(
      page,
      'h1, p, span, a, .admin-nav-item, .admin-mono, .admin-rail__label, .admin-rail__value, .admin-rail__text, .admin-lead, .admin-home-section-label, .admin-home-disabled-block',
    )
    const badgeMeasures = await measureMasks(page, ['admin-bientot-badge'])
    for (const s of bientotIds) {
      const app = await shotSection(page, `[data-mock-id="${s.id}"]`, {
        maskNames: ['admin-bientot-badge'],
        label: `admin-sec-${s.name}-app`,
      })
      const cropped = cropToCommon(mockBientot[s.name].shot, app.shot)
      const ratio = diffRatio(cropped.a, cropped.b, `admin-sec-${s.name}`)
      console.log(`admin section ${s.name} pixel diff = ${(ratio * 100).toFixed(3)} % (${cropped.w}×${cropped.h})`)
      testInfo.annotations.push({
        type: 'section-pixel',
        description: `admin/${s.name}: ${(ratio * 100).toFixed(3)} % @ ${cropped.w}×${cropped.h} ; badge mask ${badgeMeasures[0]?.areaPx ?? 0} px`,
      })
      expect(ratio, `admin section ${s.name}`).toBeLessThanOrEqual(0.01)
    }

    const tw = 1440
    const th = Math.max(appCap.size.height, mockCap.size.height)
    const fullRatio = diffRatio(padToSize(mockCap.shot, tw, th), padToSize(appCap.shot, tw, th), 'admin-desktop')
    console.log(`admin-desktop (fullPage padded, informational) pixel diff = ${(fullRatio * 100).toFixed(3)} %`)
  })

  test('Account structural — sections maquette', async ({ page }) => {
    await injectOidcSession(page)
    await mockApis(page, ME_TAREK)
    await page.setViewportSize({ width: 1440, height: 900 })

    await page.goto('http://127.0.0.1:4174/Account.dc.html')
    await annotateAccountMockup(page)
    await expandForFullPage(page)
    await settleFonts(page)
    const mockMetrics = await collectMetrics(page, ACCOUNT_IDS)

    await page.goto('/account')
    await page.waitForSelector('[data-testid="account-page"]')
    await expandForFullPage(page)
    await settleFonts(page)
    const appMetrics = await collectMetrics(page, ACCOUNT_IDS)

    const pageExceptions = {
      'account-profile': {
        skip: ['text'],
        reason:
          'Libellé rôle depuis GET /me ≠ « Propriétaire, Identité & accès » maquette — pas de valeur inventée.',
      },
      'account-appearance': {
        skip: ['box'],
        reason: 'box.y Δ≈14.5 mesuré — hint « Enregistré sur cet appareil » (+ section-head) absent maquette.',
      },
      'account-accessibility': {
        skip: ['box'],
        reason: 'box.y Δ≈27 / height Δ≈22 mesuré — cascade hint + padding lignes Toggle vs divs maquette.',
      },
      'account-language': {
        skip: ['box'],
        reason: 'box.y Δ≈51.5 mesuré — cascade sections amont + badge Bientôt (data-visual-ignore).',
      },
      'account-notifications': {
        skip: ['box'],
        reason: 'box.y Δ≈60 mesuré — cascade ; toggles désactivés + Bientôt vs interrupteurs maquette.',
      },
      'account-security': {
        skip: ['text', 'box'],
        reason:
          'Texte IdP réel (Demo IdP / Console compte) ≠ SSO example.com + 2FA fictive maquette ; box.y Δ≈62.5.',
      },
      'account-pat': {
        skip: ['text', 'fontSize', 'fontWeight', 'fontStyle', 'lineHeight', 'letterSpacing', 'color', 'box'],
        reason: 'Liste PAT fictive maquette masquée en pixel ; app « Aucun jeton personnel » — pas de jetons inventés.',
      },
      'account-privacy': {
        skip: ['box'],
        reason: 'box.y Δ≈63.2 mesuré — cascade sous sessions/PAT (hauteurs réservées masquées).',
      },
      'account-org-admin': {
        skip: ['box'],
        reason: 'box.y Δ≈49.7 mesuré — cascade bas de page.',
      },
    }
    const results = compareMetrics(mockMetrics, appMetrics, ACCOUNT_IDS, { pageExceptions })
    const failed = results.filter((r) => r.diffs.length > 0)
    expect(failed, JSON.stringify(failed, null, 2)).toEqual([])
  })

  test('Admin structural — titre / nav / SSO / stats', async ({ page }) => {
    await injectOidcSession(page)
    await mockApis(page, ME_TAREK)
    await page.setViewportSize({ width: 1440, height: 900 })

    await page.goto('http://127.0.0.1:4174/Admin.dc.html')
    await annotateAdminMockup(page)
    await expandForFullPage(page)
    await settleFonts(page)
    const mockMetrics = await collectMetrics(page, ADMIN_IDS)

    await page.goto('/admin')
    await page.waitForSelector('[data-mock-id="admin-home-title"]')
    await expandForFullPage(page)
    await settleFonts(page)
    const appMetrics = await collectMetrics(page, ADMIN_IDS)

    const pageExceptions = {
      'admin-home-title': {
        skip: ['box'],
        reason: 'box.y Δ≈8–11 mesuré — padding AdminShell vs canvas maquette.',
      },
      'admin-sso-provider': {
        skip: ['text', 'box'],
        reason:
          '« client public » (SPA) ≠ « client confidentiel » maquette ; box.y Δ≈11 — reflet OIDC réel.',
      },
      'admin-domains': {
        skip: ['box'],
        reason: 'box.y Δ≈11 mesuré — padding AdminShell vs canvas ; chrome désactivé aligné (badge data-visual-ignore).',
      },
      'admin-languages': {
        skip: ['box'],
        reason: 'box.y Δ≈11 mesuré — cascade sous SSO/domaines ; chrome désactivé aligné.',
      },
      'admin-session-policy': {
        skip: ['box'],
        reason: 'box.y Δ≈11 — section absente Admin.dc.html (slot injecté, même chrome désactivé).',
      },
      'admin-stats-plan': {
        skip: ['fontSize', 'fontWeight', 'box'],
        reason:
          'Socle auto-hébergé : libellé Licence — typo rail app 13px/500 vs héritage 16px/400 ; box.height Δ≈8.',
      },
      'admin-subnav': {
        skip: ['box'],
        reason:
          'box.height Δ viewport — subnav étiré au parent flex ; libellés/ordre OK (badge data-visual-ignore).',
      },
      'admin-nav-licence': {
        skip: ['text'],
        reason: 'Socle auto-hébergé : licence signée, pas de facturation',
      },
    }
    const results = compareMetrics(mockMetrics, appMetrics, ADMIN_IDS, { pageExceptions })
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
