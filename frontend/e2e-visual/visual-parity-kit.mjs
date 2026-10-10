// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
/**
 * RÃ¨gles : docs/visual-parity.md
 *
 * Kit partagÃ© des specs Â« paritÃ© visuelle Â» (desktop 1440Ã900, pixel honnÃªte par section).
 *
 * - Texte inclus : aucun maskGlyphs / color: transparent / crop silencieux / forÃ§age DOM.
 * - Seule exclusion : option Playwright `mask` sur Ã©lÃ©ment nommÃ© (`data-visual-mask`) ou locator
 *   ciblÃ© documentÃ© ; surface chiffrÃ©e (px et % de page) et journalisÃ©e.
 * - Tailles PNG diffÃ©rentes â ÃCHEC, sauf entrÃ©e `SIZE_EXCEPTIONS` (Îw/Îh dÃ©clarÃ©s = mesurÃ©s),
 *   puis comparaison sur zone commune ancrÃ©e en haut Ã  gauche (crop TL).
 * - DÃ©calage sous-pixel hÃ©ritÃ© d'une zone NOT_IMPLEMENTED â `SUBPIXEL_EXCEPTIONS` encadrÃ©es
 *   (conditions mesurÃ©es par le test, sinon seuil normal 1 %).
 * - FonctionnalitÃ© absente â `NOT_IMPLEMENTED` (jamais comptÃ©e comme passÃ©e).
 *
 * Mode mesure : `VISUAL_BASELINE=1` n'Ã©choue pas sur les Ã©carts mais journalise et Ã©crit
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

/** Options de capture communes (maquette ET app, tous les specs visuels). */
export const SCREENSHOT_OPTIONS = Object.freeze({ animations: 'disabled', caret: 'hide' })
export const MOCK_ORIGIN = 'http://127.0.0.1:4174'

/** Surface de page pour % de masque (desktop 1440x900 par defaut ; mobile 390x844). */
export function pageAreaFor({ width = 1440, height = 900 } = {}) {
  return width * height
}

export const AUTH_CONFIG = {
  authority: 'http://127.0.0.1:9/realms/socle',
  clientId: 'socle-frontend',
  scopes: ['openid', 'profile', 'email'],
  organizationName: 'Organisation DÃ©mo',
  displayName: 'Organisation DÃ©mo',
  idpDisplayName: 'Demo IdP',
}

export const json = (route, body, status = 200) =>
  route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })

/** `VISUAL_BASELINE=1` : mesure sans Ã©chec (Ã©crit *-before.json). */
export function isBaselineRun() {
  return process.env.VISUAL_BASELINE === '1'
}

/** Session OIDC factice + horloge figÃ©e (`Date.now`). */
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
  // fonts.ready ne couvre que les chargements déjà déclenchés : forcer la mise en page d'abord.
  await page.evaluate(async () => {
    void document.body.offsetHeight
    await new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve)))
    await document.fonts.ready
  })
  await assertFontsLoaded(page)
}

/**
 * Routes communes du shell (config OIDC, branding, /me, cloche notifications, arbres d'espaces vides).
 * Ã appeler AVANT les routes propres Ã  l'Ã©cran (Playwright applique la derniÃ¨re enregistrÃ©e en premier).
 */
export async function mockBaseApis(page, { me, notifications, spaces = [] }) {
  await page.route('**/api/v1/public/auth-config', (route) => json(route, AUTH_CONFIG))
  await page.route('**/api/v1/public/branding', (route) =>
    json(route, {
      instanceName: 'Organisation DÃ©mo',
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

/** Zone commune ancrÃ©e en haut Ã  gauche (uniquement si SIZE_EXCEPTION dÃ©clarÃ©e). */
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

export async function measureMaskName(page, name, pageArea = PAGE_AREA) {
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
    { maskName: name, pageArea },
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
 * Capture d'une section (Ã©lÃ©ment) avec masques Playwright nommÃ©s.
 * Un masque `data-visual-mask="n"` peut Ãªtre un descendant de la section ou la section elle-mÃªme.
 *
 * @param {import('@playwright/test').Page} page
 * @param {string} selector
 * @param {{ maskNames?: string[], targeted?: { name: string, selector: string, reason: string }[], label: string, maskColor?: string }} opts
 * `targeted` : masque par locator (un seul Ã©lÃ©ment attendu), surface chiffrÃ©e comme les autres.
 */
export async function shotSection(
  page,
  selector,
  { maskNames = [], targeted = [], label, maskColor = '#FFFFFF', pageArea = PAGE_AREA },
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
      throw new Error(`${label}: masque ciblÃ© Â« ${t.name} Â» â ${count} Ã©lÃ©ment(s), 1 attendu`)
    }
    const tb = await tl.boundingBox()
    const areaPx = Math.round((tb?.width ?? 0) * (tb?.height ?? 0))
    console.log(
      `MASK ${label} ${t.name}: 1 Ã©l., ${areaPx} px (${((areaPx / pageArea) * 100).toFixed(3)} % page) â ${t.reason}`,
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
    { sels: maskNames.map(maskSel), names: maskNames, pageArea },
  )
  for (const m of maskAreas) {
    if (m.count === 0) {
      throw new Error(`${label}: masque Â« ${m.name} Â» sans Ã©lÃ©ment â annotation cassÃ©e`)
    }
    console.log(`MASK ${label} ${m.name}: ${m.count} Ã©l., ${m.areaPx} px (${m.pctOfPage} % page)`)
  }

  const geo = await measureSectionGeometry(loc)
  const shot = await loc.screenshot({
    ...SCREENSHOT_OPTIONS,
    mask: [...maskNames.map((n) => page.locator(maskSel(n))), ...targetedLocators],
    maskColor,
  })
  fs.mkdirSync(OUT_DIR, { recursive: true })
  fs.writeFileSync(path.join(OUT_DIR, `${label}.png`), shot)
  return { shot, maskAreas, geo, box: { w: Math.round(box.width), h: Math.round(box.height) } }
}

/**
 * GÃ©omÃ©trie et typographie d'une section (y de mise en page, y Ã©cran, boÃ®te, texte, police de
 * l'Ã©lÃ©ment porteur du texte) â base des conditions `SUBPIXEL_EXCEPTIONS`.
 * @param {import('@playwright/test').Locator} loc
 */
export async function measureSectionGeometry(loc) {
  return loc.evaluate((el) => {
    const r = el.getBoundingClientRect()
    let scroll = window.scrollY
    for (let p = el.parentElement; p && p !== document.body && p !== document.documentElement; p = p.parentElement) {
      scroll += p.scrollTop
    }
    const textHolder = (function find(n) {
      for (const c of n.childNodes) if (c.nodeType === Node.TEXT_NODE && c.textContent.trim()) return n
      for (const c of n.children) {
        const hit = find(c)
        if (hit) return hit
      }
      return null
    })(el) ?? el
    const cs = getComputedStyle(textHolder)
    return {
      y: r.top + scroll,
      yViewport: r.top,
      w: r.width,
      h: r.height,
      text: (el.textContent ?? '').replace(/\s+/g, ' ').trim(),
      fontFamily: cs.fontFamily.split(',')[0].replace(/["']/g, '').trim().toLowerCase(),
      fontSize: cs.fontSize,
      fontWeight: cs.fontWeight,
      lineHeight: cs.lineHeight,
    }
  })
}

/* ------------------------------------------------------------------ */
/* Exceptions sous-pixel encadrÃ©es                                     */
/* ------------------------------------------------------------------ */

/** Plafond de `maxPixel` (ratio) d'une SUBPIXEL_EXCEPTION. */
export const SUBPIXEL_MAX_PIXEL = 0.08
/** Ãcart minimal de fraction de pixel (y maquette / y app) pour appliquer l'exception. */
export const SUBPIXEL_MIN_FRAC_DELTA = 0.25

/**
 * @typedef {{ upstreamNotImplemented: string, maxPixel: number, reason: string }} SubpixelException
 */

const frac = (v) => v - Math.floor(v)

/** Distance entre fractions de pixel, circulaire (0,95 et 0,05 sont Ã  0,10 px). */
export function fracDistance(a, b) {
  const d = Math.abs(frac(a) - frac(b))
  return Math.min(d, 1 - d)
}

/**
 * CÃ´tÃ© maquette : position des zones NOT_IMPLEMENTED amont de chaque SUBPIXEL_EXCEPTION
 * (bas de chaque Ã©lÃ©ment, en y de mise en page â mÃªme repÃ¨re que `measureSectionGeometry`).
 * @param {import('@playwright/test').Page} page  page maquette annotÃ©e
 * @param {Record<string, SubpixelException>} subpixelExceptions
 * @param {NotImplementedZone[]} notImplemented
 */
export async function measureSubpixelUpstreams(page, subpixelExceptions, notImplemented) {
  const out = {}
  for (const [section, ex] of Object.entries(subpixelExceptions)) {
    const zone = notImplemented.find((z) => z.id === ex.upstreamNotImplemented)
    const sel = zone && (zone.mockMask ? `[data-visual-mask="${zone.mockMask}"]` : zone.mockMeasureSelector)
    if (!sel) {
      out[section] = { id: ex.upstreamNotImplemented, inList: !!zone, bottoms: [] }
      continue
    }
    const zones = await page.evaluate((selector) => {
      return [...document.querySelectorAll(selector)].map((el) => {
        const r = el.getBoundingClientRect()
        let scroll = window.scrollY
        for (let p = el.parentElement; p && p !== document.body && p !== document.documentElement; p = p.parentElement) {
          scroll += p.scrollTop
        }
        return { top: r.top + scroll, bottom: r.bottom + scroll, h: r.height }
      })
    }, sel)
    out[section] = { id: zone.id, inList: true, zones }
  }
  return out
}

/**
 * Conditions d'une SUBPIXEL_EXCEPTION, toutes mesurÃ©es :
 *  a) structurel identique (texte, police, taille, graisse, interligne, box.w, box.h) ;
 *  b) |frac(y maquette) â frac(y app)| â¥ 0,25 px ;
 *  c) la zone amont est dans NOT_IMPLEMENTED et au-dessus de la section (maquette).
 */
export function subpixelVerdict(ex, geo, upstream, notImplemented) {
  if (!notImplemented.some((z) => z.id === ex.upstreamNotImplemented)) {
    return { guard: `zone amont Â« ${ex.upstreamNotImplemented} Â» absente de NOT_IMPLEMENTED â supprimer l'exception` }
  }
  const reasons = []
  const { mock, app } = geo ?? {}
  if (!mock || !app) return { applied: false, reasons: ['gÃ©omÃ©trie non mesurÃ©e'] }
  for (const k of ['text', 'fontFamily', 'fontSize', 'fontWeight', 'lineHeight']) {
    if (mock[k] !== app[k]) reasons.push(`${k} â  (${JSON.stringify(mock[k])} / ${JSON.stringify(app[k])})`)
  }
  for (const k of ['w', 'h']) {
    if (Math.abs(mock[k] - app[k]) > 0.01) reasons.push(`box.${k} â  (${mock[k].toFixed(2)} / ${app[k].toFixed(2)})`)
  }
  const fracDelta = fracDistance(mock.yViewport, app.yViewport)
  if (fracDelta < SUBPIXEL_MIN_FRAC_DELTA) reasons.push(`|Îfrac| ${fracDelta.toFixed(2)} < ${SUBPIXEL_MIN_FRAC_DELTA} px`)
  const above = (upstream?.zones ?? []).filter((z) => z.bottom <= mock.y + 0.01)
  if (above.length === 0) reasons.push(`zone Â« ${ex.upstreamNotImplemented} Â» absente au-dessus de la section (maquette)`)
  const nearest = above.sort((a, b) => b.bottom - a.bottom)[0] ?? null
  return { applied: reasons.length === 0, reasons, fracDelta, nearest }
}

/**
 * Fabrique de comparateur de section.
 * Tailles Ã©gales â â¤ 1 % ; sinon SIZE_EXCEPTION dÃ©clarÃ©e (Î mesurÃ© = dÃ©clarÃ©) + crop TL.
 *
 * SUBPIXEL_EXCEPTIONS (opts.subpixel) : seuil `maxPixel` au lieu de 1 % UNIQUEMENT si les conditions de
 * `subpixelVerdict` sont mesurÃ©es vraies ; garde si la zone amont quitte NOT_IMPLEMENTED ; Ã©chec si la
 * section passe dÃ©jÃ  Ã  1 % (exception inutile).
 *
 * @param {Record<string, { dw: number, dh: number, reason: string }>} sizeExceptions
 * @param {{ subpixel?: Record<string, SubpixelException>, notImplemented?: NotImplementedZone[], upstreams?: Record<string, unknown> }} [opts]
 * @returns {(name: string, mockShot: Buffer, appShot: Buffer, testInfo: import('@playwright/test').TestInfo, geo?: { mock: object, app: object }) =>
 *   { name: string, pct: number, mock: {w:number,h:number}, app: {w:number,h:number}, ok: boolean, note: string }}
 */
export function compareSectionShots(sizeExceptions = {}, { subpixel = {}, notImplemented = [], upstreams = {} } = {}) {
  for (const [k, ex] of Object.entries(subpixel)) {
    if (!(ex.maxPixel > 0 && ex.maxPixel <= SUBPIXEL_MAX_PIXEL) || !ex.reason || !ex.upstreamNotImplemented) {
      throw new Error(`SUBPIXEL_EXCEPTIONS[${k}] invalide (maxPixel â¤ ${SUBPIXEL_MAX_PIXEL}, reason, upstreamNotImplemented requis)`)
    }
  }
  return function compare(name, mockShot, appShot, testInfo, geo) {
    const mock = pngSize(mockShot)
    const app = pngSize(appShot)
    const dw = app.w - mock.w
    const dh = app.h - mock.h
    const ex = sizeExceptions[name]

    let aBuf = mockShot
    let bBuf = appShot
    let note = `mock ${mock.w}Ã${mock.h} / app ${app.w}Ã${app.h}`

    if (dw !== 0 || dh !== 0) {
      if (!ex) {
        const msg = `${name}: SIZE MISMATCH ${note} (Îw=${dw}, Îh=${dh}) â aucune SIZE_EXCEPTION dÃ©clarÃ©e`
        console.log(msg)
        testInfo.annotations.push({ type: 'section-pixel', description: msg })
        return { name, pct: NaN, mock, app, ok: false, note: msg }
      }
      if (ex.dw !== dw || ex.dh !== dh) {
        const msg = `${name}: SIZE Î dÃ©clarÃ© dw=${ex.dw}/dh=${ex.dh} mais mesurÃ© dw=${dw}/dh=${dh}`
        console.log(msg)
        testInfo.annotations.push({ type: 'section-pixel', description: msg })
        return { name, pct: NaN, mock, app, ok: false, note: msg }
      }
      const cropped = cropTopLeftCommon(mockShot, appShot)
      aBuf = cropped.a
      bBuf = cropped.b
      note = `${note} ; SIZE_EXCEPTION Îw=${dw} Îh=${dh} â crop TL ${cropped.w}Ã${cropped.h} (${ex.reason})`
    } else if (ex) {
      const msg = `${name}: SIZE_EXCEPTION dÃ©clarÃ©e (dw=${ex.dw}/dh=${ex.dh}) mais tailles identiques â retirer l'entrÃ©e`
      console.log(msg)
      testInfo.annotations.push({ type: 'section-pixel', description: msg })
      return { name, pct: NaN, mock, app, ok: false, note: msg }
    }

    const ratio = diffRatio(aBuf, bBuf, name)
    const pct = ratio * 100
    const sp = subpixel[name]
    if (sp) {
      const v = subpixelVerdict(sp, geo, upstreams[name], notImplemented)
      const fail = (msg) => {
        console.log(msg)
        testInfo.annotations.push({ type: 'section-pixel', description: msg })
        return { name, pct, mock, app, ok: false, note: msg }
      }
      if (v.guard) return fail(`${name}: SUBPIXEL_EXCEPTION â ${v.guard}`)
      const g = geo ?? {}
      const fy = (s) => (s ? `${s.y.toFixed(2)} (frac ${frac(s.yViewport).toFixed(2)})` : 'n/a')
      const up = v.nearest ? ` ; amont ${sp.upstreamNotImplemented} bas ${v.nearest.bottom.toFixed(2)} (h ${v.nearest.h.toFixed(2)})` : ''
      const geoNote = `y maquette ${fy(g.mock)} / app ${fy(g.app)} ; |Îfrac| ${v.fracDelta?.toFixed(2) ?? 'n/a'} px${up}`
      if (ratio <= 0.01) {
        return fail(`${name}: ${pct.toFixed(3)} % â¤ 1 % â SUBPIXEL_EXCEPTION inutile, retirer l'entrÃ©e (${geoNote})`)
      }
      const limit = v.applied ? sp.maxPixel : 0.01
      const ok = ratio <= limit
      const verdict = v.applied
        ? `SUBPIXEL appliquÃ©e (seuil ${(sp.maxPixel * 100).toFixed(0)} %, ${sp.reason})`
        : `SUBPIXEL NON appliquÃ©e (${v.reasons.join(' ; ')}) â seuil 1 %`
      const line = `${name}: ${pct.toFixed(3)} % â ${verdict} â ${geoNote} â ${note}`
      console.log(line)
      testInfo.annotations.push({ type: 'section-pixel', description: line })
      return { name, pct, mock, app, ok, note: line, subpixel: { applied: v.applied, limit } }
    }
    const ok = ratio <= 0.01
    const line = `${name}: ${pct.toFixed(3)} % â ${note}`
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
 * Surface NOT_IMPLEMENTED mesurÃ©e cÃ´tÃ© maquette (px et % page) â jamais Â« passÃ©e Â».
 * @param {import('@playwright/test').Page} page  page maquette annotÃ©e
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
    const desc = `NOT_IMPLEMENTED ${z.id}: ${areaPx} px (${pctOfPage} % page) â ${z.reason} [${z.backlog}]`
    console.log(desc)
    testInfo.annotations.push({ type: 'not-implemented', description: desc })
  }
  console.log('NOT_IMPLEMENTED JSON:', JSON.stringify(rows))
  return rows
}

/**
 * Garde : placeholder app prÃ©sent / feature livrÃ©e dÃ©tectÃ©e (â retirer l'entrÃ©e).
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
        `NOT_IMPLEMENTED ${z.id}: placeholder app absent â retirer de la liste si livrÃ©`,
      ).toBeGreaterThan(0)
    }
    if (z.appImplementedProbe) {
      const n = await page.locator(z.appImplementedProbe).count()
      expect(
        n,
        `NOT_IMPLEMENTED ${z.id}: feature dÃ©tectÃ©e dans l'app (${z.appImplementedProbe}) â retirer de NOT_IMPLEMENTED`,
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
        `${r.name}\t${Number.isFinite(r.pct) ? r.pct.toFixed(3) + '%' : 'SIZE_FAIL'}\tmock ${r.mock.w}Ã${r.mock.h}\tapp ${r.app.w}Ã${r.app.h}`,
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
 * Assertion structurelle compacte (diffs seulement, sans les mÃ©triques complÃ¨tes).
 * @param {{ id: string, diffs: string[] }[]} results  sortie de compareMetrics
 */
export function expectNoStructuralDiffs(results) {
  const failed = results.filter((r) => r.diffs.length > 0).map((r) => ({ id: r.id, diffs: r.diffs }))
  if (failed.length) console.log('STRUCTURAL DIFFS', JSON.stringify(failed, null, 1))
  expect(failed, JSON.stringify(failed, null, 1)).toEqual([])
}
