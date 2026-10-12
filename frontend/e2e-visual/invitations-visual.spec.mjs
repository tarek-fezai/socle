// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
/**
 * Règles : docs/visual-parity.md
 * TeamInvitations.dc.html vs /admin/team onglet Invitations.
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
  finishPixelResults,
  injectOidcSession,
  json,
  mockBaseApis,
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
  { id: 'invitations-head', name: 'head', masks: [] },
]

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

    await page.goto('/admin/team')
    await page.waitForSelector('[data-mock-id="team-title"]')
    await page.getByRole('tab', { name: /Invitations/ }).click()
    await page.waitForSelector('[data-mock-id="invitations-head"]')
    await page.locator('[data-mock-id="team-tab-invitations"]').evaluate((el) => el.blur())
    await page.mouse.move(1, 1)
    await settleFonts(page)
    await assertNotImplementedGuards(page, NOT_IMPLEMENTED, 'invitations')
    await reportAppExtras(page, testInfo, APP_EXTRA, 'invitations')

    const compare = compareSectionShots(SIZE_EXCEPTIONS)
    const results = []
    for (const s of PIXEL_SECTIONS) {
      const app = await shotSection(page, `[data-mock-id="${s.id}"]`, {
        maskNames: s.masks,
        label: `invitations-sec-${s.name}-app`,
      })
      results.push(compare(`invitations-${s.name}`, mockSec[s.name].shot, app.shot, testInfo))
    }
    finishPixelResults('invitations', results)
  })
})
