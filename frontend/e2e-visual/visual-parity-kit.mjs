// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
/**
 * Règles : docs/visual-parity.md
 *
 * Kit partagé des specs « parité visuelle » (desktop 1440×900, pixel honnête par section).
 *
 * - Texte inclus : aucun maskGlyphs / color: transparent / crop silencieux / forçage DOM.
 * - Seule exclusion : option Playwright `mask` sur élément nommé (`data-visual-mask`) ou locator
 *   ciblé documenté ; surface chiffrée (px et % de page) et journalisée.
 * - Tailles PNG différentes → ÉCHEC, sauf entrée `SIZE_EXCEPTIONS` (Δw/Δh déclarés = mesurés),
 *   puis comparaison sur zone commune ancrée en haut à gauche (crop TL).
 * - Fonctionnalité absente → `NOT_IMPLEMENTED` (jamais comptée comme passée).
 *
 * Mode mesure : `VISUAL_BASELINE=1` n'échoue pas sur les écarts mais journalise et écrit
 * `test-results/<spec>-before.json` (sinon `<spec>-measure.json`).
 */
import { expect } from '@playwright/test'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { PNG } from 'pngjs'
import pixelmatch from 'pixelmatch'
import { assertFontsLoaded } from './structural-compare.mjs'
import { VISUAL_NOW } from './dashboard-fixtures.mjs'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
export const OUT_DIR = path.join(__dirname, 'test-results')

export const PAGE_AREA = 1440 * 900
export const MOCK_ORIGIN = 'http://127.0.0.1:4174'

export const AUTH_CONFIG = {
  authority: 'http://127.0.0.1:9/realms/socle',
  clientId: 'socle-frontend',
  scopes: ['openid', 'profile', 'email'],
  organizationName: 'Organisation Démo',
  displayName: 'Organisation Démo',
  idpDisplayName: 'Demo IdP',
}

export const json = (route, body, status = 200) =>
  route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })

/** `VISUAL_BASELINE=1` : mesure sans échec (écrit *-before.json). */
export function isBaselineRun() {
  return process.env.VISUAL_BASELINE === '1'
}

/** Session OIDC factice + horloge figée (`Date.now`). */
export async function injectOidcSession(page, now = VISUAL_NOW) {
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
    { authority: AUTH_CONFIG.authority, clientId: AUTH_CONFIG.clientId, now },
  )
}

export async function settleFonts(page) {
  await assertFontsLoaded(page)
}

/**
 * Routes communes du shell (config OIDC, branding, /me, cloche notifications, arbres d'espaces vides).
 * À appeler AVANT les routes propres à l'écran (Playwright applique la dernière enregistrée en premier).
 */
export async function mockBaseApis(page, { me, notifications, spaces = [] }) {
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
  await page.route('**/api/v1/notifications**', (route) => json(route, notifications))
  await page.route('**/api/v1/favorites**', (route) => json(route, []))
  await page.route('**/api/v1/search**', (route) =>
    json(route, { query: '', results: [], total: 0 }),
  )
  await page.route('**/api/v1/spaces', (route) => {
    if (route.request().method() !== 'GET') return route.continue()
    return json(route, spaces)
  })
  await page.route(/\/api\/v1\/spaces\/[^/]+\/tree/, (route) =>
    json(route, { spaceId: '', spaceName: '', folders: [], documents: [] }),
  )
}

export function diffRatio(a, b, label) {
  const img1 = PNG.sync.read(a)
  const img2 = PNG.sync.read(b)
  const { width, height } = img1
  if (img2.width !== width || img2.height !== height) {
    throw new Error(`size mismatch ${label}: ${width}x${height} vs ${img2.width}x${img2.height}`)
  }
  const diff = new PNG({ width, height })
  const n = pixelmatch(img1.data, img2.data, diff.data, width, height, { threshold: 0.1 })
  const ratio = n / (width * height)
  fs.mkdirSync(OUT_DIR, { recursive: true })
  fs.writeFileSync(path.join(OUT_DIR, `${label}-diff.png`), PNG.sync.write(diff))
  return ratio
}

/** Zone commune ancrée en haut à gauche (uniquement si SIZE_EXCEPTION déclarée). */
export function cropTopLeftCommon(aBuf, bBuf) {
  const a = PNG.sync.read(aBuf)
  const b = PNG.sync.read(bBuf)
  const w = Math.min(a.width, b.width)
  const h = Math.min(a.height, b.height)
  if (w < 1 || h < 1) {
    throw new Error(`cropTopLeftCommon empty: ${a.width}x${a.height} vs ${b.width}x${b.height}`)
  }
  const ca = new PNG({ width: w, height: h })
  const cb = new PNG({ width: w, height: h })
  PNG.bitblt(a, ca, 0, 0, w, h, 0, 0)
  PNG.bitblt(b, cb, 0, 0, w, h, 0, 0)
  return { a: PNG.sync.write(ca), b: PNG.sync.write(cb), w, h }
}

export function pngSize(buf) {
  const img = PNG.sync.read(buf)
  return { w: img.width, h: img.height }
}

export async function measureMaskName(page, name) {
  return page.evaluate(
    ({ maskName, pageArea }) => {
      let area = 0
      for (const el of document.querySelectorAll(`[data-visual-mask="${maskName}"]`)) {
        const r = el.getBoundingClientRect()
        area += Math.max(0, r.width) * Math.max(0, r.height)
      }
      return {
        name: maskName,
        areaPx: Math.round(area),
        pctOfPage: Number(((area / pageArea) * 100).toFixed(3)),
      }
    },
    { maskName: name, pageArea: PAGE_AREA },
  )
}

export async function measureSelector(page, selector) {
  return page.evaluate(
    ({ sel, pageArea }) => {
      let area = 0
      for (const el of document.querySelectorAll(sel)) {
        const r = el.getBoundingClientRect()
        area += Math.max(0, r.width) * Math.max(0, r.height)
      }
      return {
        areaPx: Math.round(area),
        pctOfPage: Number(((area / pageArea) * 100).toFixed(3)),
      }
    },
    { sel: selector, pageArea: PAGE_AREA },
  )
}

/**
 * Capture d'une section (élément) avec masques Playwright nommés.
 * Un masque `data-visual-mask="n"` peut être un descendant de la section ou la section elle-même.
 *
 * @param {import('@playwright/test').Page} page
 * @param {string} selector
 * @param {{ maskNames?: string[], targeted?: { name: string, selector: string, reason: string }[], label: string, maskColor?: string }} opts
 * `targeted` : masque par locator (un seul élément attendu), surface chiffrée comme les autres.
 */
export async function shotSection(
  page,
  selector,
  { maskNames = [], targeted = [], label, maskColor = '#FFFFFF' },
) {
  const loc = page.locator(selector).first()
  await loc.waitFor({ state: 'visible' })
  await loc.scrollIntoViewIfNeeded()
  await settleFonts(page)
  await page.waitForTimeout(40)
  const box = await loc.boundingBox()
  if (!box || box.width < 1 || box.height < 1) throw new Error(`no box for ${selector}`)

  const targetedLocators = []
  for (const t of targeted) {
    const tl = page.locator(t.selector)
    const count = await tl.count()
    if (count !== 1) {
      throw new Error(`${label}: masque ciblé « ${t.name} » — ${count} élément(s), 1 attendu`)
    }
    const tb = await tl.boundingBox()
    const areaPx = Math.round((tb?.width ?? 0) * (tb?.height ?? 0))
    console.log(
      `MASK ${label} ${t.name}: 1 él., ${areaPx} px (${((areaPx / PAGE_AREA) * 100).toFixed(3)} % page) — ${t.reason}`,
    )
    targetedLocators.push(tl)
  }

  const maskSel = (n) =>
    `${selector}[data-visual-mask="${n}"], ${selector} [data-visual-mask="${n}"]`
  const maskAreas = await page.evaluate(
    ({ sels, names, pageArea }) =>
      names.map((n, i) => {
        let area = 0
        let count = 0
        for (const el of document.querySelectorAll(sels[i])) {
          const r = el.getBoundingClientRect()
          area += Math.max(0, r.width) * Math.max(0, r.height)
          count++
        }
        return {
          name: n,
          count,
          areaPx: Math.round(area),
          pctOfPage: Number(((area / pageArea) * 100).toFixed(3)),
        }
      }),
    { sels: maskNames.map(maskSel), names: maskNames, pageArea: PAGE_AREA },
  )
  for (const m of maskAreas) {
    if (m.count === 0) {
      throw new Error(`${label}: masque « ${m.name} » sans élément — annotation cassée`)
    }
    console.log(`MASK ${label} ${m.name}: ${m.count} él., ${m.areaPx} px (${m.pctOfPage} % page)`)
  }

  const shot = await loc.screenshot({
    animations: 'disabled',
    caret: 'hide',
    mask: [...maskNames.map((n) => page.locator(maskSel(n))), ...targetedLocators],
    maskColor,
  })
  fs.mkdirSync(OUT_DIR, { recursive: true })
  fs.writeFileSync(path.join(OUT_DIR, `${label}.png`), shot)
  return { shot, maskAreas, box: { w: Math.round(box.width), h: Math.round(box.height) } }
}

/**
 * Fabrique de comparateur de section.
 * Tailles égales → ≤ 1 % ; sinon SIZE_EXCEPTION déclarée (Δ mesuré = déclaré) + crop TL.
 *
 * @param {Record<string, { dw: number, dh: number, reason: string }>} sizeExceptions
 * @returns {(name: string, mockShot: Buffer, appShot: Buffer, testInfo: import('@playwright/test').TestInfo) =>
 *   { name: string, pct: number, mock: {w:number,h:number}, app: {w:number,h:number}, ok: boolean, note: string }}
 */
export function compareSectionShots(sizeExceptions = {}) {
  return function compare(name, mockShot, appShot, testInfo) {
    const mock = pngSize(mockShot)
    const app = pngSize(appShot)
    const dw = app.w - mock.w
    const dh = app.h - mock.h
    const ex = sizeExceptions[name]

    let aBuf = mockShot
    let bBuf = appShot
    let note = `mock ${mock.w}×${mock.h} / app ${app.w}×${app.h}`

    if (dw !== 0 || dh !== 0) {
      if (!ex) {
        const msg = `${name}: SIZE MISMATCH ${note} (Δw=${dw}, Δh=${dh}) — aucune SIZE_EXCEPTION déclarée`
        console.log(msg)
        testInfo.annotations.push({ type: 'section-pixel', description: msg })
        return { name, pct: NaN, mock, app, ok: false, note: msg }
      }
      if (ex.dw !== dw || ex.dh !== dh) {
        const msg = `${name}: SIZE Δ déclaré dw=${ex.dw}/dh=${ex.dh} mais mesuré dw=${dw}/dh=${dh}`
        console.log(msg)
        testInfo.annotations.push({ type: 'section-pixel', description: msg })
        return { name, pct: NaN, mock, app, ok: false, note: msg }
      }
      const cropped = cropTopLeftCommon(mockShot, appShot)
      aBuf = cropped.a
      bBuf = cropped.b
      note = `${note} ; SIZE_EXCEPTION Δw=${dw} Δh=${dh} → crop TL ${cropped.w}×${cropped.h} (${ex.reason})`
    } else if (ex) {
      const msg = `${name}: SIZE_EXCEPTION déclarée (dw=${ex.dw}/dh=${ex.dh}) mais tailles identiques — retirer l'entrée`
      console.log(msg)
      testInfo.annotations.push({ type: 'section-pixel', description: msg })
      return { name, pct: NaN, mock, app, ok: false, note: msg }
    }

    const ratio = diffRatio(aBuf, bBuf, name)
    const pct = ratio * 100
    const ok = ratio <= 0.01
    const line = `${name}: ${pct.toFixed(3)} % — ${note}`
    console.log(line)
    testInfo.annotations.push({ type: 'section-pixel', description: line })
    return { name, pct, mock, app, ok, note }
  }
}

/**
 * @typedef {{
 *   id: string,
 *   page: string,
 *   reason: string,
 *   backlog: string,
 *   mockMask?: string,
 *   mockMeasureSelector?: string,
 *   appPlaceholderSelector?: string,
 *   appImplementedProbe?: string,
 * }} NotImplementedZone
 */

/**
 * Surface NOT_IMPLEMENTED mesurée côté maquette (px et % page) — jamais « passée ».
 * @param {import('@playwright/test').Page} page  page maquette annotée
 * @param {import('@playwright/test').TestInfo} testInfo
 * @param {NotImplementedZone[]} notImplemented
 * @param {string} [pageName]
 */
export async function reportNotImplemented(page, testInfo, notImplemented, pageName) {
  const rows = []
  for (const z of notImplemented.filter((x) => !pageName || x.page === pageName)) {
    let areaPx = 0
    let pctOfPage = 0
    if (z.mockMask) {
      const m = await measureMaskName(page, z.mockMask)
      areaPx = m.areaPx
      pctOfPage = m.pctOfPage
    } else if (z.mockMeasureSelector) {
      const m = await measureSelector(page, z.mockMeasureSelector)
      areaPx = m.areaPx
      pctOfPage = m.pctOfPage
    }
    rows.push({
      id: z.id,
      areaPx,
      pctOfPage,
      reason: z.reason,
      backlog: z.backlog,
    })
    const desc = `NOT_IMPLEMENTED ${z.id}: ${areaPx} px (${pctOfPage} % page) — ${z.reason} [${z.backlog}]`
    console.log(desc)
    testInfo.annotations.push({ type: 'not-implemented', description: desc })
  }
  console.log('NOT_IMPLEMENTED JSON:', JSON.stringify(rows))
  return rows
}

/**
 * Garde : placeholder app présent / feature livrée détectée (→ retirer l'entrée).
 * @param {import('@playwright/test').Page} page  page app
 * @param {NotImplementedZone[]} notImplemented
 * @param {string} [pageName]
 */
export async function assertNotImplementedGuards(page, notImplemented, pageName) {
  for (const z of notImplemented.filter((x) => !pageName || x.page === pageName)) {
    if (z.appPlaceholderSelector) {
      const n = await page.locator(z.appPlaceholderSelector).count()
      expect(
        n,
        `NOT_IMPLEMENTED ${z.id}: placeholder app absent — retirer de la liste si livré`,
      ).toBeGreaterThan(0)
    }
    if (z.appImplementedProbe) {
      const n = await page.locator(z.appImplementedProbe).count()
      expect(
        n,
        `NOT_IMPLEMENTED ${z.id}: feature détectée dans l'app (${z.appImplementedProbe}) — retirer de NOT_IMPLEMENTED`,
      ).toBe(0)
    }
  }
}

/**
 * Tableau final + JSON + assertion (sauf VISUAL_BASELINE=1).
 * @param {string} specName ex. `spaces`
 * @param {ReturnType<ReturnType<typeof compareSectionShots>>[]} results
 */
export function finishPixelResults(specName, results, extra = {}) {
  const table = results
    .map(
      (r) =>
        `${r.name}\t${Number.isFinite(r.pct) ? r.pct.toFixed(3) + '%' : 'SIZE_FAIL'}\tmock ${r.mock.w}×${r.mock.h}\tapp ${r.app.w}×${r.app.h}`,
    )
    .join('\n')
  console.log(`${specName.toUpperCase()} SECTION TABLE\n${table}`)
  fs.mkdirSync(OUT_DIR, { recursive: true })
  const file = path.join(
    OUT_DIR,
    isBaselineRun() ? `${specName}-before.json` : `${specName}-measure.json`,
  )
  fs.writeFileSync(
    file,
    JSON.stringify(
      {
        spec: specName,
        generatedAt: new Date().toISOString(),
        sections: results.map((r) => ({
          name: r.name,
          pct: Number.isFinite(r.pct) ? Number(r.pct.toFixed(4)) : null,
          mock: r.mock,
          app: r.app,
          ok: r.ok,
          note: r.note,
        })),
        ...extra,
      },
      null,
      2,
    ),
  )
  const failed = results.filter((r) => !r.ok)
  if (isBaselineRun()) {
    console.log(`BASELINE ${specName}: ${failed.length}/${results.length} section(s) > 1 % (non bloquant)`)
    return failed
  }
  expect(failed, JSON.stringify(failed, null, 2)).toEqual([])
  return failed
}

/**
 * Assertion structurelle compacte (diffs seulement, sans les m�triques compl�tes).
 * @param {{ id: string, diffs: string[] }[]} results  sortie de compareMetrics
 */
export function expectNoStructuralDiffs(results) {
  const failed = results.filter((r) => r.diffs.length > 0).map((r) => ({ id: r.id, diffs: r.diffs }))
  if (failed.length) console.log('STRUCTURAL DIFFS', JSON.stringify(failed, null, 1))
  expect(failed, JSON.stringify(failed, null, 1)).toEqual([])
}
