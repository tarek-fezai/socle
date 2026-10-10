// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
/**
 * Règles : docs/visual-parity.md
 *
 * /account/tokens/new vs GeneratePersonalToken.dc.html @ 1440×900.
 *
 * Pixel honnête : texte inclus, aucun maskGlyphs / crop silencieux / forçage DOM de l'app.
 * Seul exclu : option `mask` Playwright sur élément nommé (surface chiffrée).
 *
 * Exceptions (reprises dans la PR) :
 *  - G1  Préréglages d'expiration : maquette « 90 jours | 1 an | Sans expiration », app
 *        « 7 | 30 | 60 | 90 jours » — décision produit : expiration obligatoire ≤ 90 jours.
 *        Ligne masquée des deux côtés (élément nommé), surface chiffrée.
 *  - G2  Fond : la maquette dessine un faux squelette de page sous le voile ; l'app a la vraie page
 *        /account. Comparaison par sections de la carte (précédent RestoreVersion), surface hors carte chiffrée.
 */
import { test, expect } from '@playwright/test'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { PNG } from 'pngjs'
import pixelmatch from 'pixelmatch'
import { assertFontsLoaded, collectMetrics, compareMetrics } from './structural-compare.mjs'
import { ME_TAREK } from './dashboard-fixtures.mjs'
import { ACCOUNT_VISUAL_NOW, PAT_TOKENS_SEED } from './account-admin-fixtures.mjs'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const outDir = path.join(__dirname, 'test-results')
const PAGE_AREA = 1440 * 900
const MOCK = 'http://127.0.0.1:4174'

const AUTH_CONFIG = {
  authority: 'http://127.0.0.1:9/realms/socle',
  clientId: 'socle-frontend',
  scopes: ['openid', 'profile', 'email'],
  organizationName: 'Organisation Démo',
  displayName: 'Organisation Démo',
  idpDisplayName: 'Demo IdP',
}

const EXPIRY_MASK = {
  name: 'pat-generate-expiry-presets',
  reason: 'décision produit : expiration obligatoire ≤ 90 jours',
}

/** Sections de la carte — même ordre et mêmes éléments des deux côtés. */
const SECTIONS = [
  { id: 'pat-generate-title', masks: [] },
  { id: 'pat-generate-desc', masks: [] },
  { id: 'pat-generate-name-label', masks: [] },
  { id: 'pat-generate-name-input', masks: [] },
  { id: 'pat-generate-scope-label', masks: [] },
  { id: 'pat-generate-scopes', masks: [] },
  { id: 'pat-generate-expiry-label', masks: [] },
  { id: 'pat-generate-expiry', masks: [EXPIRY_MASK.name] },
  { id: 'pat-generate-actions', masks: [] },
  { id: 'pat-generate-modal', masks: [EXPIRY_MASK.name] },
]

const STRUCTURAL_IDS = SECTIONS.map((s) => s.id)

const PLAINTEXT = 'pat_Vis0Fixture1_' + 'A1b2C3d4E5'.repeat(4) + 'xyz'

const json = (route, body, status = 200) =>
  route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })

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
    { authority: AUTH_CONFIG.authority, clientId: AUTH_CONFIG.clientId, now: ACCOUNT_VISUAL_NOW },
  )
}

async function mockApis(page, created) {
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
  await page.route('**/api/v1/me', (route) => json(route, ME_TAREK))
  await page.route('**/api/v1/me/tokens', (route) => {
    if (route.request().method() === 'POST') {
      created.calls.push(route.request().postDataJSON())
      return json(
        route,
        {
          plaintext: PLAINTEXT,
          token: {
            id: '9f9f0000-0000-4000-8000-000000000009',
            name: 'Jeton — sans nom',
            last4: PLAINTEXT.slice(-4),
            scope: 'read',
            createdAt: new Date(ACCOUNT_VISUAL_NOW).toISOString(),
            expiresAt: new Date(ACCOUNT_VISUAL_NOW + 90 * 86_400_000).toISOString(),
            lastUsedAt: null,
            status: 'active',
          },
        },
        201,
      )
    }
    return json(route, PAT_TOKENS_SEED)
  })
}

/** Annotation GeneratePersonalToken.dc.html — data-* uniquement. */
async function annotateMockup(page) {
  await page.evaluate(() => {
    const card = [...document.querySelectorAll('div')].find((d) =>
      (d.getAttribute('style') || '').includes('width: 480px'),
    )
    if (!card) throw new Error('carte 480px introuvable')
    card.setAttribute('data-mock-id', 'pat-generate-modal')
    const ids = [
      'pat-generate-title',
      'pat-generate-desc',
      'pat-generate-name-label',
      'pat-generate-name-input',
      'pat-generate-scope-label',
      'pat-generate-scopes',
      'pat-generate-expiry-label',
      'pat-generate-expiry',
      'pat-generate-actions',
    ]
    ;[...card.children].forEach((el, i) => {
      if (ids[i]) el.setAttribute('data-mock-id', ids[i])
    })
    card.querySelector('[data-mock-id="pat-generate-expiry"]')?.setAttribute(
      'data-visual-mask',
      'pat-generate-expiry-presets',
    )
  })
}

async function openApp(page, created = { calls: [] }) {
  await injectOidcSession(page)
  await mockApis(page, created)
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto('/account/tokens/new')
  await page.waitForSelector('[data-testid="pat-generate-modal"]')
  await page.waitForSelector('[data-testid="pat-list"]')
  await assertFontsLoaded(page)
  return created
}

async function measure(page, selector) {
  return page.evaluate(
    ({ sel, pageArea }) => {
      let area = 0
      let count = 0
      for (const el of document.querySelectorAll(sel)) {
        const r = el.getBoundingClientRect()
        area += Math.max(0, r.width) * Math.max(0, r.height)
        count++
      }
      return { count, areaPx: Math.round(area), pctOfPage: Number(((area / pageArea) * 100).toFixed(3)) }
    },
    { sel: selector, pageArea: PAGE_AREA },
  )
}

async function shot(page, id, maskNames, label) {
  const sel = `[data-mock-id="${id}"]`
  const loc = page.locator(sel).first()
  await loc.waitFor({ state: 'visible' })
  for (const n of maskNames) {
    const m = await measure(page, `[data-visual-mask="${n}"]`)
    if (m.count === 0) throw new Error(`${label}: masque « ${n} » sans élément — annotation cassée`)
    console.log(`MASK ${label} ${n}: ${m.count} él., ${m.areaPx} px (${m.pctOfPage} % page) — ${EXPIRY_MASK.reason}`)
  }
  const buf = await loc.screenshot({
    animations: 'disabled',
    mask: maskNames.map((n) => page.locator(`[data-visual-mask="${n}"]`)),
    maskColor: '#FFFFFF',
  })
  fs.mkdirSync(outDir, { recursive: true })
  fs.writeFileSync(path.join(outDir, `${label}.png`), buf)
  return buf
}

function compare(name, mockBuf, appBuf, testInfo) {
  const a = PNG.sync.read(mockBuf)
  const b = PNG.sync.read(appBuf)
  if (a.width !== b.width || a.height !== b.height) {
    const msg = `${name}: SIZE MISMATCH mock ${a.width}×${a.height} / app ${b.width}×${b.height}`
    console.log(msg)
    testInfo.annotations.push({ type: 'section-pixel', description: msg })
    return { name, pct: NaN, ok: false, note: msg }
  }
  const diff = new PNG({ width: a.width, height: a.height })
  const n = pixelmatch(a.data, b.data, diff.data, a.width, a.height, { threshold: 0.1 })
  fs.writeFileSync(path.join(outDir, `${name}-diff.png`), PNG.sync.write(diff))
  const pct = (n / (a.width * a.height)) * 100
  const line = `${name}: ${pct.toFixed(3)} % — ${a.width}×${a.height}`
  console.log(line)
  testInfo.annotations.push({ type: 'section-pixel', description: line })
  return { name, pct, ok: pct <= 1, note: line }
}

test.describe('Générer un jeton — parité visuelle', () => {
  test('sections de la carte ≤ 1 % (texte inclus)', async ({ page }, testInfo) => {
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto(`${MOCK}/GeneratePersonalToken.dc.html`)
    await annotateMockup(page)
    await assertFontsLoaded(page)
    const card = await measure(page, '[data-mock-id="pat-generate-modal"]')
    const outside = PAGE_AREA - card.areaPx
    const g2 = `EXCLU G2 fond hors carte : ${outside} px (${((outside / PAGE_AREA) * 100).toFixed(3)} % page) — squelette maquette vs page /account réelle`
    console.log(g2)
    testInfo.annotations.push({ type: 'exclusion', description: g2 })

    const mockShots = {}
    for (const s of SECTIONS) mockShots[s.id] = await shot(page, s.id, s.masks, `${s.id}-mock`)

    await openApp(page)
    await page.evaluate(() => document.activeElement instanceof HTMLElement && document.activeElement.blur())
    const results = []
    for (const s of SECTIONS) {
      const appBuf = await shot(page, s.id, s.masks, `${s.id}-app`)
      results.push(compare(s.id, mockShots[s.id], appBuf, testInfo))
    }
    console.log(
      'GENERATE SECTION TABLE\n' +
        results.map((r) => `${r.name}\t${Number.isFinite(r.pct) ? r.pct.toFixed(3) + '%' : 'SIZE_FAIL'}`).join('\n'),
    )
    expect(results.filter((r) => !r.ok), JSON.stringify(results, null, 2)).toEqual([])
  })

  test('structure de la carte', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto(`${MOCK}/GeneratePersonalToken.dc.html`)
    await annotateMockup(page)
    await assertFontsLoaded(page)
    const mockMetrics = await collectMetrics(page, STRUCTURAL_IDS)

    await openApp(page)
    const appMetrics = await collectMetrics(page, STRUCTURAL_IDS)
    const pageExceptions = {
      'pat-generate-modal': { skip: ['text'], reason: 'Conteneur — textes comparés sur les enfants annotés' },
      'pat-generate-expiry': {
        skip: ['text'],
        reason: `G1 ${EXPIRY_MASK.reason} : « 7 | 30 | 60 | 90 jours » vs « 90 jours | 1 an | Sans expiration »`,
      },
    }
    const results = compareMetrics(mockMetrics, appMetrics, STRUCTURAL_IDS, { pageExceptions })
    const failed = results.filter((r) => r.diffs.length > 0)
    expect(failed, JSON.stringify(failed, null, 2)).toEqual([])
  })
})

test.describe('Générer un jeton — comportement', () => {
  test('préréglages 7/30/60/90, jeton affiché une seule fois, Copier', async ({ page, context }) => {
    await context.grantPermissions(['clipboard-read', 'clipboard-write'])
    const created = await openApp(page)
    fs.mkdirSync(outDir, { recursive: true })
    await page.screenshot({ path: path.join(outDir, 'pat-generate-screen.png') })

    const presets = page.getByRole('radio', { name: /jours/ })
    await expect(presets).toHaveText(['7 jours', '30 jours', '60 jours', '90 jours'])
    await expect(page.getByText(/sans expiration/i)).toHaveCount(0)

    await page.getByRole('button', { name: 'Générer le jeton' }).click()
    await expect(page.getByTestId('pat-plaintext')).toHaveText(PLAINTEXT)
    await expect(page.getByText('Copiez ce jeton maintenant : il ne sera plus affiché.')).toBeVisible()
    expect(created.calls).toEqual([{ name: 'Jeton — sans nom', scope: 'read', expiresInDays: 90 }])
    await page.screenshot({ path: path.join(outDir, 'pat-token-once.png') })

    await page.getByRole('button', { name: 'Copier' }).click()
    await expect(page.getByRole('button', { name: 'Copié' })).toBeVisible()
    expect(await page.evaluate(() => navigator.clipboard.readText())).toBe(PLAINTEXT)

    await page.getByRole('button', { name: 'Terminé' }).click()
    await expect(page).toHaveURL(/\/account$/)
    await expect(page.getByTestId('pat-plaintext')).toHaveCount(0)
    await expect(page.getByText(PLAINTEXT)).toHaveCount(0)
    await page.locator('[data-mock-id="account-pat"]').evaluate((el) => el.scrollIntoView({ block: 'start' }))
    await page.screenshot({ path: path.join(outDir, 'pat-account-list.png') })
  })
})
