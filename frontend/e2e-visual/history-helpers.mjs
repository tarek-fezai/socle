// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * Utilitaires partagés par les specs Historique / Comparaison / Restauration / Mobile
 * (mêmes principes que page-visual.spec.mjs : session OIDC injectée, API mockée, pixel-diff sans glyphes).
 */
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { PNG } from 'pngjs'
import pixelmatch from 'pixelmatch'
import { FAVORITES_SEED, NOTIFICATIONS_SEED, SPACE_INFRA, TREE_INFRA } from './dashboard-fixtures.mjs'
import { ME_TAREK, PAGE_COMMENTS, SPACE_IDENTITE, TREE_IDENTITE, VISUAL_NOW } from './page-fixtures.mjs'
import {
  COMPARE_11_12,
  HIST_DOC_ID,
  HIST_TOTAL_DESKTOP,
  HIST_VERSIONS_DESKTOP,
  historyDocument,
  versionPage,
} from './history-fixtures.mjs'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
export const outDir = path.join(__dirname, 'test-results')
export const MOCK = 'http://127.0.0.1:4174'

export const AUTH_CONFIG = {
  authority: 'http://127.0.0.1:9/realms/socle',
  clientId: 'socle-frontend',
  scopes: ['openid', 'profile', 'email'],
  organizationName: 'Organisation Démo',
  displayName: 'Organisation Démo',
  supportContact: 'identite@example.com',
  passkeyAcrValues: 'phr',
  idpDisplayName: 'Demo IdP',
}

export const json = (route, body, status = 200) =>
  route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })

export async function injectOidcSession(page) {
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
      localStorage.setItem(
        'socle.shell.expandedSpaces',
        JSON.stringify(['s0000001-0000-4000-8000-000000000001', 's0000001-0000-4000-8000-000000000002']),
      )
    },
    { authority: AUTH_CONFIG.authority, clientId: AUTH_CONFIG.clientId, now: VISUAL_NOW },
  )
}

/**
 * @param {import('@playwright/test').Page} page
 * @param {{
 *   editor?: boolean,
 *   status?: string,
 *   versions?: object[],
 *   total?: number,
 *   compare?: object,
 *   restored?: { calls: string[] },
 * }} [opts]
 */
export async function mockHistoryApis(page, opts = {}) {
  const {
    editor = true,
    status = 'valide',
    versions = HIST_VERSIONS_DESKTOP,
    total = HIST_TOTAL_DESKTOP,
    compare = COMPARE_11_12,
    restored,
  } = opts
  const doc = historyDocument({ editor, status })
  const base = `**/api/v1/documents/${HIST_DOC_ID}`

  await page.route('**/api/v1/public/auth-config', (route) => json(route, AUTH_CONFIG))
  await page.route('**/api/v1/me', (route) => json(route, ME_TAREK))
  await page.route(base, (route) => json(route, doc))
  await page.route(`${base}/resolved`, (route) => json(route, doc))
  await page.route(`${base}/comments**`, (route) => json(route, PAGE_COMMENTS))
  await page.route(`${base}/versions?**`, (route) => json(route, versionPage(versions, total, route.request().url())))
  await page.route(`${base}/versions/*/compare/*?**`, (route) => json(route, compare))
  await page.route(`${base}/versions/*/restore?**`, (route) => {
    restored?.calls.push(route.request().url())
    return json(route, { ...doc, currentVersionNo: (doc.currentVersionNo ?? 0) + 1, status: 'en_revue' })
  })
  await page.route('**/api/v1/favorites/**', (route) => json(route, { favorited: false }))
  await page.route('**/api/v1/favorites', (route) => json(route, FAVORITES_SEED))
  await page.route('**/api/v1/notifications**', (route) => json(route, NOTIFICATIONS_SEED))
  await page.route('**/api/v1/spaces', (route) => {
    if (route.request().method() !== 'GET') return route.continue()
    return json(route, [SPACE_IDENTITE, SPACE_INFRA])
  })
  await page.route(`**/api/v1/spaces/${SPACE_IDENTITE.id}`, (route) => json(route, SPACE_IDENTITE))
  await page.route(`**/api/v1/spaces/${SPACE_IDENTITE.id}/owners`, (route) =>
    json(route, {
      spaceId: SPACE_IDENTITE.id,
      owners: [{ userId: ME_TAREK.id, email: ME_TAREK.email, displayName: ME_TAREK.displayName, responsible: true }],
    }),
  )
  await page.route(`**/api/v1/spaces/${SPACE_IDENTITE.id}/tree**`, (route) => json(route, TREE_IDENTITE))
  await page.route(`**/api/v1/spaces/${SPACE_INFRA.id}/tree**`, (route) => json(route, TREE_INFRA))
  await page.route('**/api/v1/search**', (route) => json(route, { query: '', results: [], total: 0 }))
}

export async function prep(page, opts) {
  await injectOidcSession(page)
  await mockHistoryApis(page, opts)
}

/**
 * Seuil pixelmatch : 0.02 (les autres specs utilisent 0.2). À 0.2, les aplats très pâles de ces écrans
 * (filet #F5F5F7, fonds de diff #FCEEEA / #DFF3E6, pastilles #F1EFEA…) sont indiscernables du blanc
 * (distance YIQ sous le seuil) : un bloc entier manquant donnait 0 % d'écart. 0.02 les détecte.
 */
const PIXEL_THRESHOLD = 0.02

export function diffRatio(a, b, label) {
  const imgA = PNG.sync.read(a)
  const imgB = PNG.sync.read(b)
  if (imgA.width !== imgB.width || imgA.height !== imgB.height) {
    throw new Error(`${label}: size mismatch ${imgA.width}x${imgA.height} vs ${imgB.width}x${imgB.height}`)
  }
  const diff = new PNG({ width: imgA.width, height: imgA.height })
  const mismatched = pixelmatch(imgA.data, imgB.data, diff.data, imgA.width, imgA.height, {
    threshold: PIXEL_THRESHOLD,
    includeAA: false,
  })
  fs.mkdirSync(outDir, { recursive: true })
  fs.writeFileSync(path.join(outDir, `${label}-maquette.png`), a)
  fs.writeFileSync(path.join(outDir, `${label}-app.png`), b)
  fs.writeFileSync(path.join(outDir, `${label}-diff.png`), PNG.sync.write(diff))
  const ratio = mismatched / (imgA.width * imgA.height)
  // Rapport lisible dans la sortie CI : un ratio par écran.
  console.log(`[visual-ratio] ${label} = ${(ratio * 100).toFixed(3)} %`)
  return ratio
}

export async function settleFonts(page) {
  await page.evaluate(() => document.fonts.ready)
}

/** Glyphes transparents : le pixel-diff compare la géométrie / les aplats, pas le rendu des polices. */
export async function maskGlyphs(page) {
  await page.addStyleTag({
    content: `* { color: transparent !important; -webkit-text-fill-color: transparent !important; text-shadow: none !important; caret-color: transparent !important; }
      svg text { fill: transparent !important; }
      ::selection { background: transparent; }`,
  })
}

export function writeStructural(name, results) {
  fs.mkdirSync(outDir, { recursive: true })
  fs.writeFileSync(path.join(outDir, name), JSON.stringify(results, null, 2))
}
