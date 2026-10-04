// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * Utilitaires des specs Approbation / Comparaison d'approbation / Mobile : session OIDC injectée
 * (horloge figée à APPR_NOW), API mockée. Pixel-diff et glyphes masqués : voir history-helpers.mjs.
 */
import { FAVORITES_SEED, NOTIFICATIONS_SEED, SPACE_INFRA, TREE_INFRA } from './dashboard-fixtures.mjs'
import { ME_TAREK, PAGE_COMMENTS, SPACE_IDENTITE, TREE_IDENTITE, pageDocument } from './page-fixtures.mjs'
import { versionPage } from './history-fixtures.mjs'
import { AUTH_CONFIG, json } from './history-helpers.mjs'
import {
  APPR_COMPARE_12_13,
  APPR_DETAIL_DESKTOP,
  APPR_DOC_ID,
  APPR_ITEM_DESKTOP,
  APPR_NOW,
  APPR_REQUEST_ID,
  APPR_VERSIONS_DESKTOP,
  APPR_WORKFLOW_DESKTOP,
} from './approval-fixtures.mjs'

export { MOCK, diffRatio, maskGlyphs, settleFonts, writeStructural } from './history-helpers.mjs'

async function injectSession(page) {
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
    { authority: AUTH_CONFIG.authority, clientId: AUTH_CONFIG.clientId, now: APPR_NOW },
  )
}

/**
 * @param {import('@playwright/test').Page} page
 * @param {{
 *   items?: object[],
 *   detail?: object | null,
 *   detailStatus?: number,
 *   workflow?: object | null,
 *   versions?: object[],
 *   compare?: object,
 *   decisions?: { calls: { url: string, body: unknown }[] },
 *   decideStatus?: number,
 *   decideBody?: object,
 * }} [opts]
 */
export async function mockApprovalApis(page, opts = {}) {
  const {
    items = [APPR_ITEM_DESKTOP],
    detail = APPR_DETAIL_DESKTOP,
    detailStatus = 200,
    workflow = APPR_WORKFLOW_DESKTOP,
    versions = APPR_VERSIONS_DESKTOP,
    compare = APPR_COMPARE_12_13,
    decisions,
    decideStatus = 200,
    decideBody,
  } = opts
  const doc = pageDocument('desktop')
  const base = `**/api/v1/documents/${APPR_DOC_ID}`
  const detailId = detail?.approvalRequestId ?? APPR_REQUEST_ID

  await page.route('**/api/v1/public/auth-config', (route) => json(route, AUTH_CONFIG))
  await page.route('**/api/v1/me', (route) => json(route, ME_TAREK))
  await page.route('**/api/v1/approvals/mine', (route) => json(route, items))
  await page.route(`**/api/v1/approvals/${detailId}`, (route) => {
    if (route.request().method() !== 'GET') return route.continue()
    if (detailStatus !== 200 || detail == null) {
      return json(route, { error: 'not_found' }, detailStatus === 200 ? 404 : detailStatus)
    }
    return json(route, detail)
  })
  await page.route(`${base}/approvals/applicable-workflow`, (route) =>
    workflow ? json(route, workflow) : json(route, { error: 'forbidden' }, 403),
  )
  await page.route(`${base}/approvals/*/decide`, (route) => {
    decisions?.calls.push({ url: route.request().url(), body: route.request().postDataJSON() })
    return json(
      route,
      decideBody ?? { approvalRequestId: detailId, status: 'approuve' },
      decideStatus,
    )
  })
  await page.route(base, (route) => json(route, doc))
  await page.route(`${base}/resolved`, (route) => json(route, doc))
  await page.route(`${base}/comments**`, (route) => json(route, PAGE_COMMENTS))
  await page.route(`${base}/versions?**`, (route) =>
    json(route, versionPage(versions, versions.length, route.request().url())),
  )
  await page.route(`${base}/versions/*/compare/*?**`, (route) => json(route, compare))
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

export async function prepApproval(page, opts) {
  await injectSession(page)
  await mockApprovalApis(page, opts)
}

/** Normalise une maquette 1440 px sur la colonne principale de l'app (x ≥ 268, largeur 1172). */
export async function normalizeMockupWidth(page) {
  await page.evaluate(() => {
    const root = document.querySelector('body div[style*="1440px"]')
    if (!root) return
    root.style.width = '1172px'
    root.style.marginLeft = '268px'
  })
}
