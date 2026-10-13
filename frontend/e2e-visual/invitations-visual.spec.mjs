// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
/**
 * Règles : docs/visual-parity.md
 * TeamInvitations.dc.html vs /admin/team onglet Invitations.
 * Onglet, cadre, en-tête et les six lignes (dont Renvoyer / Annuler) sont comparés.
 */
import { test } from '@playwright/test'
import { ME_TAREK, NOTIFICATIONS_SEED, VISUAL_NOW } from './dashboard-fixtures.mjs'
import { TEAM_ADMIN_USERS } from './team-fixtures.mjs'
import { INVITATIONS_SEED } from './invitations-fixtures.mjs'
import { annotateTeamInvitationsMockup } from './parity-screens-kit.mjs'
import {
  MOCK_ORIGIN,
  assertNotImplementedGuards,
  compareSectionShots,
  diffRatio,
  finishPixelResults,
  injectOidcSession,
  json,
  mockBaseApis,
  probeCompositingReasons,
  probeTextBox,
  rasterVerdict,
  reportAppExtras,
  reportNotImplemented,
  settleFonts,
  shotSection,
} from './visual-parity-kit.mjs'

export const APP_EXTRA = []
export const NOT_IMPLEMENTED = []

const SIZE_EXCEPTIONS = {}

const PIXEL_SECTIONS = [
  { id: 'team-tab-invitations', name: 'tab-invitations', masks: [] },
  { id: 'invitations-table', name: 'table-frame', masks: [] },
  { id: 'invitations-head', name: 'head', masks: [] },
  ...[0, 1, 2, 3, 4, 5].map((i) => ({ id: `invitations-row-${i}`, name: `row-${i}`, masks: [] })),
]

async function logFontSmoothingOff(page, sections, mockSec) {
  const before = await page.evaluate(() => getComputedStyle(document.documentElement).webkitFontSmoothing)
  await page.addStyleTag({
    content: ':root, html, body, #root { -webkit-font-smoothing: auto !important; }',
  })
  const after = await page.evaluate(() => getComputedStyle(document.documentElement).webkitFontSmoothing)
  for (const s of sections) {
    const app = await shotSection(page, `[data-mock-id="${s.id}"]`, {
      maskNames: s.masks,
      label: `invitations-sec-${s.name}-smooth-off`,
    })
    const pct = diffRatio(mockSec[s.name].shot, app.shot, `smooth-off-${s.name}`) * 100
    console.log(
      `font-smoothing invitations-${s.name}: off ${pct.toFixed(3)} % ; webkitFontSmoothing ${before || 'n/a'} → ${after || 'n/a'}`,
    )
  }
}

test.describe('invitations visual', () => {
  test.use({ timezoneId: 'Europe/Paris' })

  test('desktop TeamInvitations — sections pixel ≤ 1 %', async ({ page }, testInfo) => {
    await injectOidcSession(page, VISUAL_NOW)
    await mockBaseApis(page, { me: ME_TAREK, notifications: NOTIFICATIONS_SEED })
    await page.route('**/api/v1/admin/users**', (route) => {
      if (route.request().method() !== 'GET') return route.continue()
      return json(route, TEAM_ADMIN_USERS)
    })
    await page.route('**/api/v1/admin/invitations**', (route) => {
      if (route.request().method() !== 'GET') return route.continue()
      return json(route, INVITATIONS_SEED)
    })
    await page.route('**/api/v1/groups**', (route) => {
      if (route.request().method() !== 'GET') return route.continue()
      return json(route, [])
    })
    await page.setViewportSize({ width: 1440, height: 900 })

    await page.goto(`${MOCK_ORIGIN}/TeamInvitations.dc.html`)
    await annotateTeamInvitationsMockup(page)
    await settleFonts(page)
    await reportNotImplemented(page, testInfo, NOT_IMPLEMENTED, 'invitations')

    const mockSec = {}
    for (const s of PIXEL_SECTIONS) {
      mockSec[s.name] = await shotSection(page, `[data-mock-id="${s.id}"]`, {
        maskNames: s.masks,
        label: `invitations-sec-${s.name}-mock`,
      })
    }
    const rowSel = '[data-mock-id="invitations-row-0"]'
    const mockTb = await probeTextBox(page, rowSel, 'invitations-mock')
    const mockLayers = await probeCompositingReasons(page, [rowSel], 'invitations-mock')

    await page.goto('/admin/team')
    await page.waitForSelector('[data-mock-id="team-title"]')
    await page.getByRole('tab', { name: /Invitations/ }).click()
    await page.waitForSelector('[data-mock-id="invitations-head"]')
    await page.locator('[data-mock-id="team-tab-invitations"]').evaluate((el) => el.blur())
    await page.mouse.move(1, 1)
    await settleFonts(page)
    await assertNotImplementedGuards(page, NOT_IMPLEMENTED, 'invitations')
    await reportAppExtras(page, testInfo, APP_EXTRA, 'invitations')

    const appTb = await probeTextBox(page, rowSel, 'invitations-app')
    const appLayers = await probeCompositingReasons(page, [rowSel], 'invitations-app')
    const rowGeo = {
      mock: mockTb,
      app: appTb,
      mockOwnLayers: mockLayers[rowSel]?.ownLayers ?? null,
      appOwnLayers: appLayers[rowSel]?.ownLayers ?? null,
    }

    const compare = compareSectionShots(SIZE_EXCEPTIONS)
    const results = []
    for (const s of PIXEL_SECTIONS) {
      const app = await shotSection(page, `[data-mock-id="${s.id}"]`, {
        maskNames: s.masks,
        label: `invitations-sec-${s.name}-app`,
      })
      const row = compare(`invitations-${s.name}`, mockSec[s.name].shot, app.shot, testInfo, s.name === 'row-0' ? rowGeo : undefined)
      if (s.name === 'row-0') {
        const v = rasterVerdict(rowGeo)
        console.log(
          `RASTER probe invitations-row-0: pct=${Number.isFinite(row.pct) ? row.pct.toFixed(3) : 'n/a'} applied=${v.applied}${v.reasons.length ? ` (${v.reasons.join(' ; ')})` : ''}`,
        )
      }
      results.push(row)
    }
    await logFontSmoothingOff(page, PIXEL_SECTIONS.filter((s) => s.name === 'row-0'), mockSec)
    finishPixelResults('invitations', results)
  })
})
