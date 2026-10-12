// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
/**
 * Règles : docs/visual-parity.md
 * Team.dc.html (onglet Membres) vs /admin/team.
 */
import { test } from '@playwright/test'
import { ME_TAREK, NOTIFICATIONS_SEED, VISUAL_NOW } from './dashboard-fixtures.mjs'
import { TEAM_ADMIN_USERS } from './team-fixtures.mjs'
import { annotateTeamMockup } from './parity-screens-kit.mjs'
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

export const NOT_IMPLEMENTED = [
  {
    id: 'team-invite-cta',
    page: 'team',
    reason: 'Invitations e-mail — chantier B',
    backlog: 'TEAM-INVITATIONS',
    mockMask: 'team-invite-cta',
    appPlaceholderSelector: '[data-mock-id="team-invite-cta"]',
  },
  {
    id: 'team-tab-invitations',
    page: 'team',
    reason: 'Onglet Invitations — chantier B',
    backlog: 'TEAM-INVITATIONS',
    mockMask: 'team-tab-invitations',
    appPlaceholderSelector: '[data-mock-id="team-tab-invitations"]',
  },
]

const SIZE_EXCEPTIONS = {}

const PIXEL_SECTIONS = [
  { id: 'team-title', name: 'title', masks: [] },
  { id: 'team-tab-membres', name: 'tab-membres', masks: [] },
  { id: 'team-tab-equipes', name: 'tab-equipes', masks: [] },
  { id: 'team-members-head', name: 'members-head', masks: [] },
]

test.describe('team visual', () => {
  test.use({ timezoneId: 'Europe/Paris' })

  test('desktop Team Membres — sections pixel ≤ 1 %', async ({ page }, testInfo) => {
    await injectOidcSession(page, VISUAL_NOW)
    await mockBaseApis(page, { me: ME_TAREK, notifications: NOTIFICATIONS_SEED })
    await page.route('**/api/v1/admin/users**', (route) => {
      if (route.request().method() !== 'GET') return route.continue()
      return json(route, TEAM_ADMIN_USERS)
    })
    await page.route('**/api/v1/groups**', (route) => {
      if (route.request().method() !== 'GET') return route.continue()
      return json(route, [])
    })
    await page.setViewportSize({ width: 1440, height: 900 })

    await page.goto(`${MOCK_ORIGIN}/Team.dc.html`)
    await annotateTeamMockup(page)
    await settleFonts(page)
    await reportNotImplemented(page, testInfo, NOT_IMPLEMENTED, 'team')

    const mockSec = {}
    for (const s of PIXEL_SECTIONS) {
      mockSec[s.name] = await shotSection(page, `[data-mock-id="${s.id}"]`, {
        maskNames: s.masks,
        label: `team-sec-${s.name}-mock`,
      })
    }

    await page.goto('/admin/team')
    await page.waitForSelector('[data-mock-id="team-title"]')
    await page.mouse.move(1, 1)
    await settleFonts(page)
    await assertNotImplementedGuards(page, NOT_IMPLEMENTED, 'team')
    await reportAppExtras(page, testInfo, APP_EXTRA, 'team')

    const compare = compareSectionShots(SIZE_EXCEPTIONS)
    const results = []
    for (const s of PIXEL_SECTIONS) {
      const app = await shotSection(page, `[data-mock-id="${s.id}"]`, {
        maskNames: s.masks,
        label: `team-sec-${s.name}-app`,
      })
      results.push(compare(`team-${s.name}`, mockSec[s.name].shot, app.shot, testInfo))
    }
    finishPixelResults('team', results)
  })
})
