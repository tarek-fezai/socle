// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
/**
 * /account et /admin vs Account.dc.html & Admin.dc.html @ 1440×900.
 *
 * Pixel honnête : texte inclus, aucun maskGlyphs / crop silencieux / forçage DOM.
 * Seul exclu : option `mask` Playwright sur élément nommé (surface chiffrée).
 * Annotation maquette : data-* uniquement.
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

/**
 * Zones maquette sans fonctionnalité produit — NON comparées, jamais « passées ».
 * Garde : si l’app expose le sélecteur `appImplementedProbe`, le test échoue
 * (retirer l’entrée quand la feature est livrée).
 *
 * @typedef {{
 *   id: string,
 *   page: 'account' | 'admin',
 *   reason: string,
 *   backlog: string,
 *   mockMask?: string,
 *   mockMeasureSelector?: string,
 *   appPlaceholderSelector?: string,
 *   appImplementedProbe?: string,
 * }} NotImplementedZone
 */
/** @type {NotImplementedZone[]} */
export const NOT_IMPLEMENTED = [
  {
    id: 'account-security-2fa',
    page: 'account',
    reason: 'UI 2FA / clés de sécurité maquette — gérée hors Socle (IdP)',
    backlog: 'ACCOUNT-2FA',
    mockMask: 'account-security-2fa',
    appPlaceholderSelector: '[data-visual-mask="account-security-2fa"]',
    appImplementedProbe: 'text=Clé de sécurité',
  },
  {
    id: 'account-sessions-list',
    page: 'account',
    reason: 'Liste sessions actives — pas d’API sessions produit',
    backlog: 'ACCOUNT-SESSIONS',
    mockMask: 'account-sessions-list',
    appPlaceholderSelector: '[data-visual-mask="account-sessions-list"]',
    appImplementedProbe: 'text=Chrome · macOS',
  },
  {
    id: 'account-pat-list',
    page: 'account',
    reason: 'Liste PAT fictive — jusqu’à la PR PAT',
    backlog: 'ACCOUNT-PAT',
    mockMask: 'account-pat-list',
    appPlaceholderSelector: '[data-visual-mask="account-pat-list"]',
    appImplementedProbe: 'text=pat_••••',
  },
  {
    id: 'account-export-history',
    page: 'account',
    reason: 'Historique des exports RGPD — non implémenté',
    backlog: 'ACCOUNT-EXPORT-HISTORY',
    mockMask: 'account-export-history',
    appImplementedProbe: 'text=Demandé le',
  },
  {
    id: 'account-pat-generate',
    page: 'account',
    reason: 'Action « + Générer un jeton » — jusqu’à la PR PAT',
    backlog: 'ACCOUNT-PAT',
    mockMask: 'account-pat-generate',
    appPlaceholderSelector: '[data-visual-mask="account-pat-generate"] .account-soon',
    appImplementedProbe: '[data-mock-id="account-pat"] >> a:has-text("Générer un jeton")',
  },
  {
    id: 'account-notifications-toggles',
    page: 'account',
    reason: 'Interrupteurs préférences de notification — pas d’API préférences',
    backlog: 'ACCOUNT-NOTIF-PREFS',
    mockMask: 'account-notifications-toggles',
    appPlaceholderSelector:
      '[data-mock-id="account-notifications"] [data-visual-mask="account-notifications-toggles"]:disabled',
    appImplementedProbe:
      '[data-mock-id="account-notifications"] [data-visual-mask="account-notifications-toggles"]:not(:disabled)',
  },
  {
    id: 'admin-domains',
    page: 'admin',
    reason: 'Domaines autorisés — bloc Bientôt, pas d’endpoint',
    backlog: 'ADMIN-DOMAINS',
    mockMeasureSelector: '[data-mock-id="admin-domains"]',
    appPlaceholderSelector: '[data-mock-id="admin-domains"] .account-soon',
    appImplementedProbe: 'text=Bloquer les connexions hors domaine',
  },
  {
    id: 'admin-session-policy',
    page: 'admin',
    reason: 'Politique de session — absente maquette complète / Bientôt app',
    backlog: 'ADMIN-SESSION-POLICY',
    appPlaceholderSelector: '[data-mock-id="admin-session-policy"] .account-soon',
    appImplementedProbe: '[data-mock-id="admin-session-policy"] >> text=minutes',
  },
  {
    id: 'admin-languages',
    page: 'admin',
    reason: 'Langues organisation — bloc Bientôt, pas d’endpoint',
    backlog: 'ADMIN-ORG-LANG',
    mockMeasureSelector: '[data-mock-id="admin-languages"]',
    appPlaceholderSelector: '[data-mock-id="admin-languages"] .account-soon',
    appImplementedProbe: 'text=Langues activées pour la documentation',
  },
  {
    id: 'admin-revoke-sessions',
    page: 'admin',
    reason: 'Révoquer toutes les sessions — Bientôt',
    backlog: 'ADMIN-REVOKE-SESSIONS',
    appPlaceholderSelector: '[data-mock-id="admin-revoke-sessions"] .account-soon',
    appImplementedProbe: '[data-mock-id="admin-revoke-sessions"] >> role=button',
  },
  {
    id: 'admin-role-source',
    page: 'admin',
    reason: 'Source des rôles — pas d’endpoint',
    backlog: 'ADMIN-ROLE-SOURCE',
    mockMask: 'admin-role-source',
    mockMeasureSelector: '[data-visual-mask="admin-role-source"]',
    appImplementedProbe: 'text=Source des rôles',
  },
  {
    id: 'admin-role-mapping',
    page: 'admin',
    reason: 'Mapping des rôles — pas d’endpoint',
    backlog: 'ADMIN-ROLE-MAPPING',
    mockMask: 'admin-role-mapping',
    mockMeasureSelector: '[data-visual-mask="admin-role-mapping"]',
    appImplementedProbe: 'text=Mapping des rôles',
  },
  {
    id: 'admin-storage',
    page: 'admin',
    reason: 'Stockage de l’instance — pas d’endpoint',
    backlog: 'ADMIN-STORAGE',
    mockMask: 'admin-storage',
    mockMeasureSelector: '[data-visual-mask="admin-storage"]',
    appImplementedProbe: "text=Stockage de l'instance",
  },
]

/**
 * Exceptions de taille déclarées (Δw/Δh mesurés) — sinon tailles différentes = ÉCHEC.
 * Comparaison alors sur zone commune ancrée en haut à gauche, texte inclus, ≤ 1 %.
 */
const SIZE_EXCEPTIONS = {
  'account-security': {
    dw: 0,
    dh: -7,
    reason:
      'Ligne 2FA = NOT_IMPLEMENTED account-security-2fa : maquette badges « Clé de sécurité » (≈70 px) vs ligne « Gérée par le fournisseur d’identité » app (≈63 px) ; mesuré mock 640×138 / app 640×131.',
  },
}

const ACCOUNT_STRUCTURAL_IDS = [
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

const ADMIN_STRUCTURAL_IDS = [
  'admin-home-title',
  'admin-nav-identity',
  'admin-nav-licence',
  'admin-subnav',
  'admin-sso-provider',
  'admin-stats-users',
  'admin-stats-spaces',
  'admin-stats-plan',
]

/** Sections pixel account (texte inclus). */
const ACCOUNT_PIXEL_SECTIONS = [
  { id: 'account-appearance', name: 'appearance', masks: [] },
  { id: 'account-accessibility', name: 'accessibility', masks: [] },
  { id: 'account-language', name: 'language', masks: [] },
  { id: 'account-notifications', name: 'notifications', masks: ['account-notifications-toggles'] },
  {
    id: 'account-security',
    name: 'security',
    masks: ['account-security-2fa', 'account-security-idp'],
  },
  { id: 'account-pat', name: 'pat', masks: ['account-pat-generate'] },
  { id: 'account-privacy', name: 'privacy', masks: [] },
  { id: 'account-org-admin', name: 'org-admin', masks: [] },
]

/** Sections pixel admin (texte inclus). Domaines / langues / etc. → NOT_IMPLEMENTED. */
const ADMIN_PIXEL_SECTIONS = [
  // Badges « Bientôt » : app uniquement (absents de la maquette)
  { id: 'admin-subnav', name: 'subnav', masks: [], appOnlyMasks: ['admin-subnav-bientot'] },
  { id: 'admin-sso-provider', name: 'sso', masks: [] },
  { id: 'admin-stats-users', name: 'stats-users', masks: [] },
  { id: 'admin-stats-spaces', name: 'stats-spaces', masks: [] },
  { id: 'admin-stats-plan', name: 'stats-plan', masks: [] },
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

/** Zone commune ancrée en haut à gauche (uniquement si SIZE_EXCEPTION déclarée). */
function cropTopLeftCommon(aBuf, bBuf) {
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

function pngSize(buf) {
  const img = PNG.sync.read(buf)
  return { w: img.width, h: img.height }
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

async function measureMaskName(page, name) {
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

async function measureSelector(page, selector) {
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

/** Annotation Account.dc.html — data-* uniquement. */
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
    const notifications = byExact('Notifications')?.nextElementSibling
    notifications?.setAttribute('data-mock-id', 'account-notifications')
    for (const row of [...(notifications?.children ?? [])]) {
      row.children[1]?.setAttribute('data-visual-mask', 'account-notifications-toggles')
    }

    const security = byExact('Sécurité')?.nextElementSibling
    security?.setAttribute('data-mock-id', 'account-security')
    const authRow = security?.children?.[0]
    authRow?.children?.[0]?.children?.[1]?.setAttribute('data-visual-mask', 'account-security-idp')
    authRow?.children?.[1]?.setAttribute('data-visual-mask', 'account-security-idp')
    const twoFa = security?.children?.[1]
    if (twoFa) {
      twoFa.setAttribute('data-visual-mask', 'account-security-2fa')
      twoFa.setAttribute('data-visual-ignore', '')
    }

    const sessLabel = byExact('Sessions actives')
    const sessBox = sessLabel?.parentElement?.nextElementSibling
    if (sessBox) {
      sessBox.setAttribute('data-visual-mask', 'account-sessions-list')
      sessBox.setAttribute('data-mock-id', 'account-sessions-mask')
    }

    const patLabel = byExact("Jetons d'accès personnels")
    if (patLabel) {
      const head = patLabel.parentElement
      head?.setAttribute('data-mock-id', 'account-pat')
      head
        ?.querySelector('a[href="GeneratePersonalToken.dc.html"]')
        ?.setAttribute('data-visual-mask', 'account-pat-generate')
      let after = head?.nextElementSibling
      if (after?.tagName === 'P') after = after.nextElementSibling
      if (after) {
        after.setAttribute('data-visual-mask', 'account-pat-list')
        after.setAttribute('data-mock-id', 'account-pat-list')
      }
    }

    const exportCta = [...document.querySelectorAll('a, button')].find((a) =>
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

/** Annotation Admin.dc.html — data-* uniquement (pas de remove / replace / style). */
async function annotateAdminMockup(page) {
  await page.evaluate(() => {
    const root = document.querySelector('body div[style*="1440px"]') || document.body.firstElementChild
    if (!root) return
    const body = root.children[1]
    const subnav = body?.children[0]
    subnav?.setAttribute('data-mock-id', 'admin-subnav')

    for (const a of [...(subnav?.querySelectorAll('a, .nav-item') ?? [])]) {
      const t = (a.textContent || '').replace(/\s+/g, ' ').trim()
      if (t === 'Licence') a.setAttribute('data-mock-id', 'admin-nav-licence')
      if (t === 'Identité & SSO') a.setAttribute('data-mock-id', 'admin-nav-identity')
    }

    const mainCol = body?.children[1]
    const inner = mainCol?.querySelector('div[style*="max-width"]') || mainCol
    inner?.querySelector('h1')?.setAttribute('data-mock-id', 'admin-home-title')
    inner?.querySelector('div[style*="border: 1px solid"]')?.setAttribute('data-mock-id', 'admin-sso-provider')

    const labels = [...(inner?.querySelectorAll('div') ?? [])]
    const afterLabel = (text, id, mask) => {
      const lab = labels.find(
        (d) => d.childNodes.length === 1 && (d.textContent || '').trim() === text,
      )
      const next = lab?.nextElementSibling
      if (!next) return
      next.setAttribute('data-mock-id', id)
      if (mask) next.setAttribute('data-visual-mask', mask)
    }
    afterLabel('Domaines autorisés', 'admin-domains')
    afterLabel("Langues de l'organisation", 'admin-languages')
    afterLabel('Source des rôles', 'admin-role-source', 'admin-role-source')
    afterLabel('Mapping des rôles', 'admin-role-mapping', 'admin-role-mapping')

    const storageLab = labels.find(
      (d) =>
        d.childNodes.length === 1 &&
        (d.textContent || '').trim() === "Stockage de l'instance",
    )
    // Libellé est dans la carte stockage (pas un label séparé + sibling)
    const storageCard =
      storageLab?.closest('div[style*="border"]') || storageLab?.parentElement?.parentElement
    if (storageCard) {
      storageCard.setAttribute('data-mock-id', 'admin-storage')
      storageCard.setAttribute('data-visual-mask', 'admin-storage')
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

async function shotSection(page, selector, { maskNames = [], label }) {
  const loc = page.locator(selector).first()
  await loc.waitFor({ state: 'visible' })
  await loc.scrollIntoViewIfNeeded()
  await settleFonts(page)
  await page.waitForTimeout(40)
  const box = await loc.boundingBox()
  if (!box || box.width < 1 || box.height < 1) throw new Error(`no box for ${selector}`)
  const maskAreas = await page.evaluate(
    ({ sel, names, pageArea }) =>
      names.map((n) => {
        let area = 0
        let count = 0
        for (const el of document.querySelectorAll(`${sel} [data-visual-mask="${n}"]`)) {
          const r = el.getBoundingClientRect()
          area += Math.max(0, r.width) * Math.max(0, r.height)
          count++
        }
        return { name: n, count, areaPx: Math.round(area), pctOfPage: Number(((area / pageArea) * 100).toFixed(3)) }
      }),
    { sel: selector, names: maskNames, pageArea: PAGE_AREA },
  )
  for (const m of maskAreas) {
    if (m.count === 0) throw new Error(`${label}: masque « ${m.name} » sans élément — annotation cassée`)
    console.log(`MASK ${label} ${m.name}: ${m.count} él., ${m.areaPx} px (${m.pctOfPage} % page)`)
  }
  const shot = await loc.screenshot({
    animations: 'disabled',
    mask: maskNames.map((n) => page.locator(`${selector} [data-visual-mask="${n}"]`)),
    // Fond des sections (#FFFFFF) : une zone masquée compte comme fond vide des deux côtés,
    // y compris quand l'élément n'existe que d'un côté (badges Bientôt app).
    maskColor: '#FFFFFF',
  })
  fs.mkdirSync(outDir, { recursive: true })
  fs.writeFileSync(path.join(outDir, `${label}.png`), shot)
  return { shot, maskAreas, box: { w: Math.round(box.width), h: Math.round(box.height) } }
}

/**
 * Compare une section : tailles égales → ≤1 % ; sinon exception SIZE déclarée + crop TL.
 * @returns {{ name: string, pct: number, mock: {w:number,h:number}, app: {w:number,h:number}, ok: boolean, note: string }}
 */
function compareSectionShots(name, mockShot, appShot, testInfo) {
  const mock = pngSize(mockShot)
  const app = pngSize(appShot)
  const dw = app.w - mock.w
  const dh = app.h - mock.h
  const ex = SIZE_EXCEPTIONS[name]

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
  }

  const ratio = diffRatio(aBuf, bBuf, name)
  const pct = ratio * 100
  const ok = ratio <= 0.01
  const line = `${name}: ${pct.toFixed(3)} % — ${note}`
  console.log(line)
  testInfo.annotations.push({ type: 'section-pixel', description: line })
  return { name, pct, mock, app, ok, note }
}

async function reportNotImplemented(page, testInfo, pageName) {
  const rows = []
  for (const z of NOT_IMPLEMENTED.filter((x) => x.page === pageName)) {
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
    const row = {
      id: z.id,
      areaPx,
      pctOfPage,
      reason: z.reason,
      backlog: z.backlog,
    }
    rows.push(row)
    const desc = `NOT_IMPLEMENTED ${z.id}: ${areaPx} px (${pctOfPage} % page) — ${z.reason} [${z.backlog}]`
    console.log(desc)
    testInfo.annotations.push({ type: 'not-implemented', description: desc })
  }
  console.log('NOT_IMPLEMENTED JSON:', JSON.stringify(rows))
  return rows
}

async function assertNotImplementedGuards(page, pageName) {
  for (const z of NOT_IMPLEMENTED.filter((x) => x.page === pageName)) {
    if (z.appPlaceholderSelector) {
      const n = await page.locator(z.appPlaceholderSelector).count()
      expect(n, `NOT_IMPLEMENTED ${z.id}: placeholder app absent — retirer de la liste si livré`).toBeGreaterThan(0)
    }
    if (z.appImplementedProbe) {
      const n = await page.locator(z.appImplementedProbe).count()
      expect(
        n,
        `NOT_IMPLEMENTED ${z.id}: feature détectée dans l’app (${z.appImplementedProbe}) — retirer de NOT_IMPLEMENTED`,
      ).toBe(0)
    }
  }
}

test.describe('account admin visual', () => {
  test('desktop Account — sections pixel ≤ 1 % (texte inclus)', async ({ page }, testInfo) => {
    await injectOidcSession(page)
    await mockApis(page, ME_TAREK)
    await page.setViewportSize({ width: 1440, height: 900 })

    await page.goto('http://127.0.0.1:4174/Account.dc.html')
    await annotateAccountMockup(page)
    await settleFonts(page)
    await reportNotImplemented(page, testInfo, 'account')

    const mockSec = {}
    for (const s of ACCOUNT_PIXEL_SECTIONS) {
      mockSec[s.name] = await shotSection(page, `[data-mock-id="${s.id}"]`, {
        maskNames: s.masks,
        label: `account-sec-${s.name}-mock`,
      })
    }

    await page.goto('/account')
    await page.waitForSelector('[data-testid="account-page"]')
    await settleFonts(page)
    await assertNotImplementedGuards(page, 'account')

    const results = []
    for (const s of ACCOUNT_PIXEL_SECTIONS) {
      const app = await shotSection(page, `[data-mock-id="${s.id}"]`, {
        maskNames: s.masks,
        label: `account-sec-${s.name}-app`,
      })
      results.push(
        compareSectionShots(`account-${s.name}`, mockSec[s.name].shot, app.shot, testInfo),
      )
    }

    console.log(
      'ACCOUNT SECTION TABLE\n' +
        results
          .map(
            (r) =>
              `${r.name}\t${Number.isFinite(r.pct) ? r.pct.toFixed(3) + '%' : 'SIZE_FAIL'}\tmock ${r.mock.w}×${r.mock.h}\tapp ${r.app.w}×${r.app.h}`,
          )
          .join('\n'),
    )

    const failed = results.filter((r) => !r.ok)
    expect(failed, JSON.stringify(failed, null, 2)).toEqual([])
  })

  test('desktop Admin home — sections pixel ≤ 1 % (texte inclus)', async ({ page }, testInfo) => {
    await injectOidcSession(page)
    await mockApis(page, ME_TAREK)
    await page.setViewportSize({ width: 1440, height: 900 })

    await page.goto('http://127.0.0.1:4174/Admin.dc.html')
    await annotateAdminMockup(page)
    await settleFonts(page)
    await reportNotImplemented(page, testInfo, 'admin')

    const mockSec = {}
    for (const s of ADMIN_PIXEL_SECTIONS) {
      mockSec[s.name] = await shotSection(page, `[data-mock-id="${s.id}"]`, {
        maskNames: s.masks,
        label: `admin-sec-${s.name}-mock`,
      })
    }

    await page.goto('/admin')
    await page.waitForSelector('[data-mock-id="admin-sso-provider"]')
    await settleFonts(page)
    await assertNotImplementedGuards(page, 'admin')

    const results = []
    for (const s of ADMIN_PIXEL_SECTIONS) {
      const app = await shotSection(page, `[data-mock-id="${s.id}"]`, {
        maskNames: [...s.masks, ...(s.appOnlyMasks ?? [])],
        label: `admin-sec-${s.name}-app`,
      })
      results.push(compareSectionShots(`admin-${s.name}`, mockSec[s.name].shot, app.shot, testInfo))
    }

    console.log(
      'ADMIN SECTION TABLE\n' +
        results
          .map(
            (r) =>
              `${r.name}\t${Number.isFinite(r.pct) ? r.pct.toFixed(3) + '%' : 'SIZE_FAIL'}\tmock ${r.mock.w}×${r.mock.h}\tapp ${r.app.w}×${r.app.h}`,
          )
          .join('\n'),
    )

    const failed = results.filter((r) => !r.ok)
    expect(failed, JSON.stringify(failed, null, 2)).toEqual([])
  })

  test('Account structural — sections maquette', async ({ page }) => {
    await injectOidcSession(page)
    await mockApis(page, ME_TAREK)
    await page.setViewportSize({ width: 1440, height: 900 })

    await page.goto('http://127.0.0.1:4174/Account.dc.html')
    await annotateAccountMockup(page)
    await settleFonts(page)
    const mockMetrics = await collectMetrics(page, ACCOUNT_STRUCTURAL_IDS)

    await page.goto('/account')
    await page.waitForSelector('[data-testid="account-page"]')
    await settleFonts(page)
    const appMetrics = await collectMetrics(page, ACCOUNT_STRUCTURAL_IDS)

    const pageExceptions = {
      'account-profile': {
        skip: ['text'],
        reason: 'Libellé rôle depuis GET /me ≠ « Propriétaire, Identité & accès » maquette.',
      },
      'account-appearance': {
        skip: ['box.y'],
        reason: 'box.y cascade — hint « Enregistré sur cet appareil » (+ section-head) absent maquette.',
      },
      'account-accessibility': {
        skip: ['box.y'],
        reason: 'box.y cascade — hint amont ; x/w/h comparés.',
      },
      'account-language': {
        skip: ['box.y'],
        reason: 'box.y cascade sections amont ; x/w/h comparés.',
      },
      'account-notifications': {
        skip: ['box.y'],
        reason: 'box.y cascade ; x/w/h comparés.',
      },
      'account-security': {
        skip: ['text', 'box.y', 'box.height'],
        reason:
          'Texte IdP réel (Demo IdP / Console compte) ≠ SSO example.com maquette ; box.y cascade ; box.height Δ6.8 mesuré = ligne 2FA NOT_IMPLEMENTED (data-visual-ignore) ; x/w comparés.',
      },
      'account-pat': {
        skip: ['box.y'],
        reason: 'En-tête PAT (titre + Générer) ; liste en NOT_IMPLEMENTED ; box.y cascade ; x/w/h comparés.',
      },
      'account-privacy': {
        skip: ['box.y'],
        reason: 'box.y cascade sous sessions/PAT ; x/w/h comparés.',
      },
      'account-org-admin': {
        skip: ['box.y'],
        reason: 'box.y cascade bas de page ; x/w/h comparés.',
      },
    }
    const results = compareMetrics(mockMetrics, appMetrics, ACCOUNT_STRUCTURAL_IDS, {
      pageExceptions,
    })
    const failed = results.filter((r) => r.diffs.length > 0)
    expect(failed, JSON.stringify(failed, null, 2)).toEqual([])
  })

  test('Admin structural — titre / nav / SSO / stats', async ({ page }) => {
    await injectOidcSession(page)
    await mockApis(page, ME_TAREK)
    await page.setViewportSize({ width: 1440, height: 900 })

    await page.goto('http://127.0.0.1:4174/Admin.dc.html')
    await annotateAdminMockup(page)
    await settleFonts(page)
    const mockMetrics = await collectMetrics(page, ADMIN_STRUCTURAL_IDS)

    await page.goto('/admin')
    await page.waitForSelector('[data-mock-id="admin-home-title"]')
    await settleFonts(page)
    const appMetrics = await collectMetrics(page, ADMIN_STRUCTURAL_IDS)

    const pageExceptions = {
      'admin-home-title': {
        skip: ['box.y'],
        reason: 'box.y cascade — padding AdminShell vs canvas maquette ; x/w/h comparés.',
      },
      'admin-sso-provider': {
        skip: ['text', 'box.y'],
        reason:
          '« client public » (SPA) ≠ « client confidentiel » maquette — OIDC réel ; box.y cascade.',
      },
      'admin-stats-plan': {
        skip: ['text'],
        reason: 'Libellé Licence depuis overview réel (édition / échéance) vs copie maquette.',
      },
    }
    const results = compareMetrics(mockMetrics, appMetrics, ADMIN_STRUCTURAL_IDS, {
      pageExceptions,
    })
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
