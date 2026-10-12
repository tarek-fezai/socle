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
 * - Décalage sous-pixel hérité d'une zone NOT_IMPLEMENTED → `SUBPIXEL_EXCEPTIONS` encadrées
 *   (conditions mesurées par le test, sinon seuil normal 1 %).
 * - Lissage LCD vs gris à géométrie identique → `RASTER_EXCEPTIONS` encadrées (seuil ≤ 5 %,
 *   conditions a/b/c mesurées ; échec si la section ≤ 1 %).
 * - Fonctionnalité absente → `NOT_IMPLEMENTED` (jamais comptée comme passée).
 * - Élément présent dans l'app, absent de la maquette par décision produit → `APP_EXTRA`
 *   (masque côté app uniquement, surface chiffrée, reason + décision ; échec s'il disparaît).
 * - Masque de texte partiel → `rangeMasks` : rectangles mesurés (Range sur un élément existant,
 *   lecture seule, aucune mutation du DOM) peints dans le PNG après capture, surface journalisée.
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
export async function injectOidcSession(page, now = VISUAL_NOW, { profile = {}, expandedSpaces = null } = {}) {
  await page.addInitScript(
    ({ authority, clientId, now, profile, expandedSpaces }) => {
      Date.now = () => now
      sessionStorage.setItem(
        `oidc.user:${authority}:${clientId}`,
        JSON.stringify({
          access_token: 'visual-access-token',
          token_type: 'Bearer',
          expires_at: Math.floor(now / 1000) + 3600,
          profile: { sub: 'tarek-visual', name: 'Tarek Fezai', ...profile },
        }),
      )
      if (expandedSpaces) localStorage.setItem('socle.shell.expandedSpaces', JSON.stringify(expandedSpaces))
    },
    { authority: AUTH_CONFIG.authority, clientId: AUTH_CONFIG.clientId, now, profile, expandedSpaces },
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
 * Calques de composition (élément + ancêtres) : transform, opacity&lt;1, will-change, filter,
 * backdrop-filter, animation. Journalisé pour le rapport AA — ne mute pas le DOM.
 * @param {import('@playwright/test').Page} page
 * @param {string[]} selectors
 * @param {string} side
 */
export async function probeCompositionLayers(page, selectors, side) {
  const found = await page.evaluate((sels) => {
    const hitsOf = (cs) => {
      const hits = []
      if (cs.transform && cs.transform !== 'none') hits.push(`transform=${cs.transform}`)
      if (cs.opacity && cs.opacity !== '1') hits.push(`opacity=${cs.opacity}`)
      if (cs.willChange && cs.willChange !== 'auto') hits.push(`will-change=${cs.willChange}`)
      if (cs.filter && cs.filter !== 'none') hits.push(`filter=${cs.filter}`)
      const bf = cs.backdropFilter || cs.webkitBackdropFilter
      if (bf && bf !== 'none') hits.push(`backdrop-filter=${bf}`)
      if (cs.animationName && cs.animationName !== 'none') hits.push(`animation=${cs.animationName}`)
      return hits
    }
    const walk = (el) => {
      const layers = []
      let n = el
      let depth = 0
      while (n && n.nodeType === 1 && depth < 24) {
        const hits = hitsOf(getComputedStyle(n))
        if (hits.length) {
          layers.push({
            depth,
            tag: n.tagName.toLowerCase(),
            mockId: n.getAttribute('data-mock-id') || '',
            cls: String(n.className || '').slice(0, 96),
            hits,
          })
        }
        n = n.parentElement
        depth++
      }
      return layers
    }
    const out = {}
    for (const s of sels) {
      const el = document.querySelector(s)
      out[s] = el ? walk(el) : 'MISSING'
    }
    return out
  }, selectors)
  const line = `COMPOSITION ${side}: ${JSON.stringify(found)}`
  console.log(line)
  return found
}

/**
 * Propriétés de rastérisation (élément) + largeur colonne au 1/100 px.
 * @param {import('@playwright/test').Page} page
 * @param {string[]} selectors
 * @param {string} side
 */
export async function probeRasterProps(page, selectors, side) {
  const found = await page.evaluate((sels) => {
    const out = {}
    for (const s of sels) {
      const el = document.querySelector(s)
      if (!el) {
        out[s] = 'MISSING'
        continue
      }
      const cs = getComputedStyle(el)
      const r = el.getBoundingClientRect()
      out[s] = {
        x: Number(r.x.toFixed(2)),
        y: Number(r.y.toFixed(2)),
        w: Number(r.width.toFixed(2)),
        h: Number(r.height.toFixed(2)),
        transform: cs.transform,
        willChange: cs.willChange,
        fontSmoothing: cs.webkitFontSmoothing || cs.fontSmoothing || '',
        textRendering: cs.textRendering,
      }
    }
    return out
  }, selectors)
  console.log(`RASTER ${side}: ${JSON.stringify(found)}`)
  return found
}

/**
 * CDP LayerTree : calques et raisons de composition pour chaque sélecteur.
 * @param {import('@playwright/test').Page} page
 * @param {string[]} selectors
 * @param {string} side
 */
export async function probeCompositingReasons(page, selectors, side) {
  const client = await page.context().newCDPSession(page)
  await client.send('DOM.enable')
  let layers = []
  const onTree = (e) => {
    if (Array.isArray(e.layers)) layers = e.layers
  }
  client.on('LayerTree.layerTreeDidChange', onTree)
  await client.send('LayerTree.enable')
  await page.evaluate(() => document.body.getBoundingClientRect())
  await page.waitForTimeout(80)

  const { root } = await client.send('DOM.getDocument', { depth: 0, pierce: true })
  const out = {}
  for (const sel of selectors) {
    const { nodeId } = await client.send('DOM.querySelector', {
      nodeId: root.nodeId,
      selector: sel,
    })
    if (!nodeId) {
      out[sel] = 'MISSING'
      continue
    }
    const { node } = await client.send('DOM.describeNode', { nodeId })
    const backendNodeId = node.backendNodeId
    const own = layers.filter((l) => l.backendNodeId === backendNodeId)
    const ownLayers = []
    for (const l of own) {
      const r = await client.send('LayerTree.compositingReasons', { layerId: l.layerId })
      ownLayers.push({
        layerId: l.layerId,
        parentLayerId: l.parentLayerId || '',
        w: l.width,
        h: l.height,
        reasons: r.compositingReasons,
        ids: r.compositingReasonIds,
      })
    }
    let paintsInto = null
    if (!ownLayers.length) {
      const elBox = await page.locator(sel).first().boundingBox()
      const hit =
        elBox &&
        layers.find(
          (l) =>
            elBox.x >= l.offsetX - 1 &&
            elBox.y >= l.offsetY - 1 &&
            elBox.x + elBox.width <= l.offsetX + l.width + 1 &&
            elBox.y + elBox.height <= l.offsetY + l.height + 1 &&
            l.backendNodeId,
        )
      if (hit) {
        const r = await client.send('LayerTree.compositingReasons', { layerId: hit.layerId })
        paintsInto = {
          layerId: hit.layerId,
          backendNodeId: hit.backendNodeId,
          w: hit.width,
          h: hit.height,
          reasons: r.compositingReasons,
          ids: r.compositingReasonIds,
        }
      }
    }
    out[sel] = { backendNodeId, ownLayers, paintsInto }
  }
  console.log(`CDP_LAYERS ${side}: ${JSON.stringify(out)}`)
  client.off('LayerTree.layerTreeDidChange', onTree)
  await client.send('LayerTree.disable')
  await client.detach()
  return out
}

/**
 * Chaîne d'ancêtres : position / transform / opacity / filter / will-change / contain /
 * isolation / overflow + getBoundingClientRect au 1/100 px.
 * @param {import('@playwright/test').Page} page
 * @param {string} selector
 * @param {string} side
 */
export async function probeAncestorStack(page, selector, side) {
  const stack = await page.evaluate((sel) => {
    const el = document.querySelector(sel)
    if (!el) return 'MISSING'
    const rows = []
    let n = el
    let depth = 0
    while (n && n.nodeType === 1 && depth < 28) {
      const cs = getComputedStyle(n)
      const r = n.getBoundingClientRect()
      rows.push({
        depth,
        tag: n.tagName.toLowerCase(),
        mockId: n.getAttribute('data-mock-id') || '',
        position: cs.position,
        transform: cs.transform,
        opacity: cs.opacity,
        filter: cs.filter,
        backdropFilter: cs.backdropFilter || cs.webkitBackdropFilter || 'none',
        willChange: cs.willChange,
        contain: cs.contain,
        isolation: cs.isolation,
        overflow: `${cs.overflowX}/${cs.overflowY}`,
        x: Number(r.x.toFixed(2)),
        y: Number(r.y.toFixed(2)),
      })
      n = n.parentElement
      depth++
    }
    return rows
  }, selector)
  console.log(`ANCESTORS ${side} ${selector}: ${JSON.stringify(stack)}`)
  return stack
}

/**
 * Boîte + Range du nœud texte + styles de placement (1/100 px).
 * @param {import('@playwright/test').Page} page
 * @param {string} selector
 * @param {string} side
 */
export async function probeTextBox(page, selector, side) {
  const found = await page.evaluate((sel) => {
    const el = document.querySelector(sel)
    if (!el) return 'MISSING'
    const r = el.getBoundingClientRect()
    const cs = getComputedStyle(el)
    const walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT)
    let text = walker.nextNode()
    while (text && !(text.textContent || '').trim()) text = walker.nextNode()
    let tr = null
    if (text) {
      const range = document.createRange()
      range.selectNodeContents(text)
      const br = range.getBoundingClientRect()
      tr = {
        x: Number(br.x.toFixed(2)),
        y: Number(br.y.toFixed(2)),
        w: Number(br.width.toFixed(2)),
        h: Number(br.height.toFixed(2)),
      }
    }
    return {
      box: {
        x: Number(r.x.toFixed(2)),
        y: Number(r.y.toFixed(2)),
        w: Number(r.width.toFixed(2)),
        h: Number(r.height.toFixed(2)),
      },
      text: tr,
      display: cs.display,
      lineHeight: cs.lineHeight,
      padding: cs.padding,
      verticalAlign: cs.verticalAlign,
      boxSizing: cs.boxSizing,
      fontFamily: cs.fontFamily,
      fontSize: cs.fontSize,
      fontWeight: cs.fontWeight,
      letterSpacing: cs.letterSpacing,
      color: cs.color,
    }
  }, selector)
  console.log(`TEXTBOX ${side} ${selector}: ${JSON.stringify(found)}`)
  return found
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

/**
 * Rectangles écran d'une sous-chaîne de texte dans un élément existant (Range, lecture seule).
 * Espaces normalisés (suite d'espaces = un espace). `found: false` si le texte est absent.
 * @param {import('@playwright/test').Page} page
 * @param {string} selector  premier élément correspondant
 * @param {string} text
 */
export async function measureTextRange(page, selector, text) {
  return page.evaluate(
    ({ sel, needle }) => {
      const el = document.querySelector(sel)
      if (!el) return { found: false, rects: [], areaPx: 0 }
      const chars = []
      const walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT)
      let prevSpace = true
      for (let n = walker.nextNode(); n; n = walker.nextNode()) {
        const v = n.nodeValue ?? ''
        for (let i = 0; i < v.length; i++) {
          const space = /\s/.test(v[i])
          if (space && prevSpace) continue
          chars.push({ c: space ? ' ' : v[i], node: n, offset: i })
          prevSpace = space
        }
      }
      const hay = chars.map((x) => x.c).join('')
      const want = needle.replace(/\s+/g, ' ').trim()
      const at = hay.indexOf(want)
      if (at < 0 || !want) return { found: false, rects: [], areaPx: 0 }
      const first = chars[at]
      const last = chars[at + want.length - 1]
      const range = document.createRange()
      range.setStart(first.node, first.offset)
      range.setEnd(last.node, last.offset + 1)
      const rects = [...range.getClientRects()]
        .filter((r) => r.width > 0 && r.height > 0)
        .map((r) => ({ x: r.x, y: r.y, w: r.width, h: r.height }))
      const areaPx = Math.round(rects.reduce((a, r) => a + r.w * r.h, 0))
      return { found: true, rects, areaPx }
    },
    { sel: selector, needle: text },
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
  { maskNames = [], targeted = [], rangeMasks = [], label, maskColor = '#FFFFFF', pageArea = PAGE_AREA },
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
      `MASK ${label} ${t.name}: 1 él., ${areaPx} px (${((areaPx / pageArea) * 100).toFixed(3)} % page) — ${t.reason}`,
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
      throw new Error(`${label}: masque « ${m.name} » sans élément — annotation cassée`)
    }
    console.log(`MASK ${label} ${m.name}: ${m.count} él., ${m.areaPx} px (${m.pctOfPage} % page)`)
  }

  const rangeRects = []
  for (const rm of rangeMasks) {
    const within = rm.within ? `${selector} ${rm.within}` : selector
    const m = await measureTextRange(page, within, rm.text)
    if (!m.found) {
      throw new Error(`${label}: masque de texte « ${rm.name} » — « ${rm.text} » introuvable dans ${within}`)
    }
    const sec = await loc.evaluate((el) => {
      const r = el.getBoundingClientRect()
      return { x: r.x, y: r.y }
    })
    for (const r of m.rects) rangeRects.push({ x: r.x - sec.x, y: r.y - sec.y, w: r.w, h: r.h })
    console.log(
      `MASK ${label} ${rm.name}: texte « ${rm.text} », ${m.areaPx} px (${((m.areaPx / pageArea) * 100).toFixed(3)} % page)`,
    )
  }

  const geo = await measureSectionGeometry(loc)
  let shot = await loc.screenshot({
    ...SCREENSHOT_OPTIONS,
    mask: [...maskNames.map((n) => page.locator(maskSel(n))), ...targetedLocators],
    maskColor,
  })
  if (rangeRects.length) shot = paintRects(shot, rangeRects, maskColor)
  fs.mkdirSync(OUT_DIR, { recursive: true })
  fs.writeFileSync(path.join(OUT_DIR, `${label}.png`), shot)
  return { shot, maskAreas, geo, box: { w: Math.round(box.width), h: Math.round(box.height) } }
}

/**
 * Géométrie et typographie d'une section (y de mise en page, y écran, boîte, texte, police de
 * l'élément porteur du texte) — base des conditions `SUBPIXEL_EXCEPTIONS`.
 * @param {import('@playwright/test').Locator} loc
 */
function paintRects(buf, rects, color) {
  const img = PNG.sync.read(buf)
  const hex = color.replace('#', '')
  const rgb = [0, 2, 4].map((i) => parseInt(hex.slice(i, i + 2), 16))
  for (const r of rects) {
    const x0 = Math.max(0, Math.floor(r.x))
    const y0 = Math.max(0, Math.floor(r.y))
    const x1 = Math.min(img.width, Math.ceil(r.x + r.w))
    const y1 = Math.min(img.height, Math.ceil(r.y + r.h))
    for (let y = y0; y < y1; y++) {
      for (let x = x0; x < x1; x++) {
        const i = (y * img.width + x) * 4
        img.data[i] = rgb[0]
        img.data[i + 1] = rgb[1]
        img.data[i + 2] = rgb[2]
        img.data[i + 3] = 255
      }
    }
  }
  return PNG.sync.write(img)
}

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
/* Exceptions sous-pixel encadrées                                     */
/* ------------------------------------------------------------------ */

/** Plafond de `maxPixel` (ratio) d'une SUBPIXEL_EXCEPTION. */
export const SUBPIXEL_MAX_PIXEL = 0.08
/** Écart minimal de fraction de pixel (y maquette / y app) pour appliquer l'exception. */
export const SUBPIXEL_MIN_FRAC_DELTA = 0.25
/** Plafond de `maxPixel` (ratio) d'une RASTER_EXCEPTION (LCD vs gris). */
export const RASTER_MAX_PIXEL = 0.05

/**
 * @typedef {{ upstreamNotImplemented: string, maxPixel: number, reason: string }} SubpixelException
 */

const frac = (v) => v - Math.floor(v)

/** Distance entre fractions de pixel, circulaire (0,95 et 0,05 sont à 0,10 px). */
export function fracDistance(a, b) {
  const d = Math.abs(frac(a) - frac(b))
  return Math.min(d, 1 - d)
}

/**
 * Côté maquette : position des zones NOT_IMPLEMENTED amont de chaque SUBPIXEL_EXCEPTION
 * (bas de chaque élément, en y de mise en page — même repère que `measureSectionGeometry`).
 * @param {import('@playwright/test').Page} page  page maquette annotée
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
 * Conditions d'une SUBPIXEL_EXCEPTION, toutes mesurées :
 *  a) structurel identique (texte, police, taille, graisse, interligne, box.w, box.h) ;
 *  b) |frac(y maquette) − frac(y app)| ≥ 0,25 px ;
 *  c) la zone amont est dans NOT_IMPLEMENTED et au-dessus de la section (maquette).
 */
export function subpixelVerdict(ex, geo, upstream, notImplemented) {
  if (!notImplemented.some((z) => z.id === ex.upstreamNotImplemented)) {
    return { guard: `zone amont « ${ex.upstreamNotImplemented} » absente de NOT_IMPLEMENTED — supprimer l'exception` }
  }
  const reasons = []
  const { mock, app } = geo ?? {}
  if (!mock || !app) return { applied: false, reasons: ['géométrie non mesurée'] }
  for (const k of ['text', 'fontFamily', 'fontSize', 'fontWeight', 'lineHeight']) {
    if (mock[k] !== app[k]) reasons.push(`${k} ≠ (${JSON.stringify(mock[k])} / ${JSON.stringify(app[k])})`)
  }
  for (const k of ['w', 'h']) {
    if (Math.abs(mock[k] - app[k]) > 0.01) reasons.push(`box.${k} ≠ (${mock[k].toFixed(2)} / ${app[k].toFixed(2)})`)
  }
  const fracDelta = fracDistance(mock.yViewport, app.yViewport)
  if (fracDelta < SUBPIXEL_MIN_FRAC_DELTA) reasons.push(`|Δfrac| ${fracDelta.toFixed(2)} < ${SUBPIXEL_MIN_FRAC_DELTA} px`)
  const above = (upstream?.zones ?? []).filter((z) => z.bottom <= mock.y + 0.01)
  if (above.length === 0) reasons.push(`zone « ${ex.upstreamNotImplemented} » absente au-dessus de la section (maquette)`)
  const nearest = above.sort((a, b) => b.bottom - a.bottom)[0] ?? null
  return { applied: reasons.length === 0, reasons, fracDelta, nearest }
}

/**
 * @typedef {{ maxPixel: number, reason: string }} RasterException
 */

function sameRect01(a, b) {
  if (!a || !b) return false
  return ['x', 'y', 'w', 'h'].every((k) => Math.abs(Number(a[k]) - Number(b[k])) <= 0.01)
}

function normFontFamily(s) {
  const first = String(s || '').split(',')[0] || ''
  return first.replace(/['"]/g, '').replace(/\s+/g, ' ').trim().toLowerCase()
}

/**
 * RASTER_EXCEPTION : LCD vs gris à boîte/texte/police identiques, sans calque propre.
 * @param {{ mock?: object, app?: object, mockOwnLayers?: unknown[], appOwnLayers?: unknown[] } | undefined} geo
 */
export function rasterVerdict(geo) {
  const reasons = []
  const { mock, app } = geo ?? {}
  if (!mock || !app || mock === 'MISSING' || app === 'MISSING') {
    return { applied: false, reasons: ['preuves boîte/texte non mesurées'] }
  }
  if (!sameRect01(mock.box, app.box)) {
    reasons.push(
      `boîte ≠ (${JSON.stringify(mock.box)} / ${JSON.stringify(app.box)})`,
    )
  }
  if (!sameRect01(mock.text, app.text)) {
    reasons.push(
      `texte Range ≠ (${JSON.stringify(mock.text)} / ${JSON.stringify(app.text)})`,
    )
  }
  const mockFam = normFontFamily(mock.fontFamily)
  const appFam = normFontFamily(app.fontFamily)
  if (mockFam !== appFam) reasons.push(`fontFamily ≠ (${mock.fontFamily} / ${app.fontFamily})`)
  for (const k of ['fontSize', 'fontWeight', 'letterSpacing', 'lineHeight', 'color']) {
    if (mock[k] !== app[k]) reasons.push(`${k} ≠ (${JSON.stringify(mock[k])} / ${JSON.stringify(app[k])})`)
  }
  const mL = Array.isArray(geo.mockOwnLayers) ? geo.mockOwnLayers : null
  const aL = Array.isArray(geo.appOwnLayers) ? geo.appOwnLayers : null
  if (!mL || !aL) reasons.push('LayerTree non mesuré')
  else {
    if (mL.length) reasons.push(`calque propre maquette (${mL.length})`)
    if (aL.length) reasons.push(`calque propre app (${aL.length})`)
  }
  return { applied: reasons.length === 0, reasons }
}

/**
 * Fabrique de comparateur de section.
 * Tailles égales → ≤ 1 % ; sinon SIZE_EXCEPTION déclarée (Δ mesuré = déclaré) + crop TL.
 *
 * SUBPIXEL_EXCEPTIONS (opts.subpixel) : seuil `maxPixel` au lieu de 1 % UNIQUEMENT si les conditions de
 * `subpixelVerdict` sont mesurées vraies ; garde si la zone amont quitte NOT_IMPLEMENTED ; échec si la
 * section passe déjà à 1 % (exception inutile).
 *
 * RASTER_EXCEPTIONS (opts.raster) : seuil `maxPixel` ≤ 5 % si boîte + Range texte identiques (1/100 px),
 * police calculée identique, aucun calque propre LayerTree ; ≤ 1 % = succès (inutilisée sur ce rasterizer).
 *
 * @param {Record<string, { dw: number, dh: number, reason: string }>} sizeExceptions
 * @param {{ subpixel?: Record<string, SubpixelException>, raster?: Record<string, RasterException>, notImplemented?: NotImplementedZone[], upstreams?: Record<string, unknown> }} [opts]
 * @returns {(name: string, mockShot: Buffer, appShot: Buffer, testInfo: import('@playwright/test').TestInfo, geo?: { mock: object, app: object }) =>
 *   { name: string, pct: number, mock: {w:number,h:number}, app: {w:number,h:number}, ok: boolean, note: string }}
 */
export function compareSectionShots(sizeExceptions = {}, { subpixel = {}, raster = {}, notImplemented = [], upstreams = {} } = {}) {
  for (const [k, ex] of Object.entries(subpixel)) {
    if (!(ex.maxPixel > 0 && ex.maxPixel <= SUBPIXEL_MAX_PIXEL) || !ex.reason || !ex.upstreamNotImplemented) {
      throw new Error(`SUBPIXEL_EXCEPTIONS[${k}] invalide (maxPixel ≤ ${SUBPIXEL_MAX_PIXEL}, reason, upstreamNotImplemented requis)`)
    }
  }
  for (const [k, ex] of Object.entries(raster)) {
    if (!(ex.maxPixel > 0 && ex.maxPixel <= RASTER_MAX_PIXEL) || !ex.reason) {
      throw new Error(`RASTER_EXCEPTIONS[${k}] invalide (maxPixel ≤ ${RASTER_MAX_PIXEL}, reason requis)`)
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
    let note = `mock ${mock.w}×${mock.h} / app ${app.w}×${app.h}`

    if (dw !== 0 || dh !== 0) {
      if (!ex) {
        // Information seulement (jamais un succès) : % sur la zone commune haut-gauche.
        const c = cropTopLeftCommon(mockShot, appShot)
        const cropPct = diffRatio(c.a, c.b, name) * 100
        const msg = `${name}: SIZE MISMATCH ${note} (Δw=${dw}, Δh=${dh}) — aucune SIZE_EXCEPTION déclarée (info : ${cropPct.toFixed(3)} % sur zone commune)`
        console.log(msg)
        testInfo.annotations.push({ type: 'section-pixel', description: msg })
        return { name, pct: NaN, cropPct, mock, app, ok: false, note: msg }
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
    const sp = subpixel[name]
    if (sp) {
      const v = subpixelVerdict(sp, geo, upstreams[name], notImplemented)
      const fail = (msg) => {
        console.log(msg)
        testInfo.annotations.push({ type: 'section-pixel', description: msg })
        return { name, pct, mock, app, ok: false, note: msg }
      }
      if (v.guard) return fail(`${name}: SUBPIXEL_EXCEPTION — ${v.guard}`)
      const g = geo ?? {}
      const fy = (s) => (s ? `${s.y.toFixed(2)} (frac ${frac(s.yViewport).toFixed(2)})` : 'n/a')
      const up = v.nearest ? ` ; amont ${sp.upstreamNotImplemented} bas ${v.nearest.bottom.toFixed(2)} (h ${v.nearest.h.toFixed(2)})` : ''
      const geoNote = `y maquette ${fy(g.mock)} / app ${fy(g.app)} ; |Δfrac| ${v.fracDelta?.toFixed(2) ?? 'n/a'} px${up}`
      if (ratio <= 0.01) {
        return fail(`${name}: ${pct.toFixed(3)} % ≤ 1 % — SUBPIXEL_EXCEPTION inutile, retirer l'entrée (${geoNote})`)
      }
      const limit = v.applied ? sp.maxPixel : 0.01
      const ok = ratio <= limit
      const verdict = v.applied
        ? `SUBPIXEL appliquée (seuil ${(sp.maxPixel * 100).toFixed(0)} %, ${sp.reason})`
        : `SUBPIXEL NON appliquée (${v.reasons.join(' ; ')}) → seuil 1 %`
      const line = `${name}: ${pct.toFixed(3)} % — ${verdict} — ${geoNote} — ${note}`
      console.log(line)
      testInfo.annotations.push({ type: 'section-pixel', description: line })
      return { name, pct, mock, app, ok, note: line, subpixel: { applied: v.applied, limit } }
    }
    const rst = raster[name]
    if (rst) {
      const v = rasterVerdict(geo)
      const fail = (msg) => {
        console.log(msg)
        testInfo.annotations.push({ type: 'section-pixel', description: msg })
        return { name, pct, mock, app, ok: false, note: msg }
      }
      if (ratio <= 0.01) {
        const line = `${name}: ${pct.toFixed(3)} % ≤ 1 % — RASTER_EXCEPTION inutilisée sur ce rasterizer (garder si un autre OS > 1 %)`
        console.log(line)
        testInfo.annotations.push({ type: 'section-pixel', description: line })
        return { name, pct, mock, app, ok: true, note: line, raster: { applied: false, unused: true } }
      }
      const limit = v.applied ? rst.maxPixel : 0.01
      const ok = ratio <= limit
      const verdict = v.applied
        ? `RASTER appliquée (seuil ${(rst.maxPixel * 100).toFixed(0)} %, ${rst.reason})`
        : `RASTER NON appliquée (${v.reasons.join(' ; ')}) → seuil 1 %`
      const line = `${name}: ${pct.toFixed(3)} % — ${verdict} — ${note}`
      console.log(line)
      testInfo.annotations.push({ type: 'section-pixel', description: line })
      return { name, pct, mock, app, ok, note: line, raster: { applied: v.applied, limit, reasons: v.reasons } }
    }
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
 *   mockRange?: { selector: string, text: string },
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
    } else if (z.mockRange) {
      const m = await measureTextRange(page, z.mockRange.selector, z.mockRange.text)
      if (!m.found) throw new Error(`NOT_IMPLEMENTED ${z.id}: texte maquette « ${z.mockRange.text} » introuvable`)
      areaPx = m.areaPx
      pctOfPage = Number(((m.areaPx / PAGE_AREA) * 100).toFixed(3))
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
 * @typedef {{
 *   id: string,
 *   page: string,
 *   reason: string,
 *   decision: string,
 *   appMask?: string,
 *   appRange?: { selector: string, text: string },
 * }} AppExtraZone
 */

/**
 * APP_EXTRA : élément présent dans l'app, absent de la maquette par décision produit.
 * Surface mesurée côté app (px et % page) ; garde : échec si l'élément a disparu de l'app
 * (retirer l'entrée et le masque).
 * @param {import('@playwright/test').Page} page  page app
 * @param {import('@playwright/test').TestInfo} testInfo
 * @param {AppExtraZone[]} appExtras
 * @param {string} [pageName]
 */
export async function reportAppExtras(page, testInfo, appExtras, pageName) {
  const rows = []
  for (const z of appExtras.filter((x) => !pageName || x.page === pageName)) {
    if (!z.reason || !z.decision || (!z.appMask && !z.appRange)) {
      throw new Error(`APP_EXTRA ${z.id}: reason, decision et appMask ou appRange requis`)
    }
    let areaPx = 0
    let count = 0
    if (z.appMask) {
      const m = await measureMaskName(page, z.appMask)
      count = await page.locator(`[data-visual-mask="${z.appMask}"]`).count()
      areaPx = m.areaPx
    } else {
      const m = await measureTextRange(page, z.appRange.selector, z.appRange.text)
      count = m.found ? 1 : 0
      areaPx = m.areaPx
    }
    expect(
      count > 0 && areaPx > 0,
      `APP_EXTRA ${z.id}: élément absent de l'app — retirer l'entrée APP_EXTRA et son masque`,
    ).toBe(true)
    const pctOfPage = Number(((areaPx / PAGE_AREA) * 100).toFixed(3))
    rows.push({ id: z.id, count, areaPx, pctOfPage, reason: z.reason, decision: z.decision })
    const desc = `APP_EXTRA ${z.id}: ${count} él., ${areaPx} px (${pctOfPage} % page) — ${z.reason} [${z.decision}]`
    console.log(desc)
    testInfo.annotations.push({ type: 'app-extra', description: desc })
  }
  console.log('APP_EXTRA JSON:', JSON.stringify(rows))
  return rows
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
          ...(Number.isFinite(r.cropPct) ? { cropPct: Number(r.cropPct.toFixed(4)) } : {}),
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
 * Assertion structurelle compacte (diffs seulement, sans les métriques complètes).
 * @param {{ id: string, diffs: string[] }[]} results  sortie de compareMetrics
 */
export function expectNoStructuralDiffs(results) {
  const failed = results.filter((r) => r.diffs.length > 0).map((r) => ({ id: r.id, diffs: r.diffs }))
  if (failed.length) console.log('STRUCTURAL DIFFS', JSON.stringify(failed, null, 1))
  expect(failed, JSON.stringify(failed, null, 1)).toEqual([])
}
