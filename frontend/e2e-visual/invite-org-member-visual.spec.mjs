// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
/**
 * Règles : docs/visual-parity.md
 * InviteOrgMember.dc.html vs modal « Inviter un membre ».
 * Titre / Annuler / Envoyer : lissage LCD vs :root antialiased (8 % / 3–4 %),
 * hors seuil raster 5 % pour le titre — libellés comparés (0 % CI).
 */
import { test } from '@playwright/test'
import { ME_TAREK, NOTIFICATIONS_SEED, VISUAL_NOW } from './dashboard-fixtures.mjs'
import { TEAM_ADMIN_USERS } from './team-fixtures.mjs'
import { INVITE_TEAMS, LICENCE_OK } from './invitations-fixtures.mjs'
import { annotateInviteOrgMemberMockup } from './parity-screens-kit.mjs'
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
  { id: 'invite-email-label', name: 'email-label', masks: [] },
  { id: 'invite-team-label', name: 'team-label', masks: [] },
  { id: 'invite-team-role-label', name: 'team-role-label', masks: [] },
  { id: 'invite-global-label', name: 'global-label', masks: [] },
]

test.describe('invite-org-member visual', () => {
  test.use({ timezoneId: 'Europe/Paris' })

  test('desktop InviteOrgMember — sections pixel ≤ 1 %', async ({ page }, testInfo) => {
    await injectOidcSession(page, VISUAL_NOW)
    await mockBaseApis(page, { me: ME_TAREK, notifications: NOTIFICATIONS_SEED })
    await page.route('**/api/v1/admin/users**', (route) => {
      if (route.request().method() !== 'GET') return route.continue()
      return json(route, TEAM_ADMIN_USERS)
    })
    await page.route('**/api/v1/admin/invitations**', (route) => {
      if (route.request().method() !== 'GET') return route.continue()
      return json(route, [])
    })
    await page.route('**/api/v1/admin/licence**', (route) => {
      if (route.request().method() !== 'GET') return route.continue()
      return json(route, LICENCE_OK)
    })
    await page.route('**/api/v1/groups**', (route) => {
      if (route.request().method() !== 'GET') return route.continue()
      return json(route, INVITE_TEAMS)
    })
    await page.route('**/api/v1/public/branding**', (route) => {
      if (route.request().method() !== 'GET') return route.continue()
      return json(route, { instanceName: 'Organisation Démo' })
    })
    await page.setViewportSize({ width: 1440, height: 900 })

    await page.goto(`${MOCK_ORIGIN}/InviteOrgMember.dc.html`)
    await annotateInviteOrgMemberMockup(page)
    await settleFonts(page)
    await reportNotImplemented(page, testInfo, NOT_IMPLEMENTED, 'invite-org-member')

    const mockSec = {}
    for (const s of PIXEL_SECTIONS) {
      mockSec[s.name] = await shotSection(page, `[data-mock-id="${s.id}"]`, {
        maskNames: s.masks,
        label: `invite-sec-${s.name}-mock`,
      })
    }

    await page.goto('/admin/team')
    await page.waitForSelector('[data-mock-id="team-invite-cta"]')
    await page.getByRole('button', { name: /Inviter un membre/ }).click()
    await page.waitForSelector('[data-mock-id="invite-title"]')
    await page.mouse.move(1, 1)
    await settleFonts(page)
    await assertNotImplementedGuards(page, NOT_IMPLEMENTED, 'invite-org-member')
    await reportAppExtras(page, testInfo, APP_EXTRA, 'invite-org-member')

    const compare = compareSectionShots(SIZE_EXCEPTIONS)
    const results = []
    for (const s of PIXEL_SECTIONS) {
      const app = await shotSection(page, `[data-mock-id="${s.id}"]`, {
        maskNames: s.masks,
        label: `invite-sec-${s.name}-app`,
      })
      results.push(compare(`invite-${s.name}`, mockSec[s.name].shot, app.shot, testInfo))
    }
    finishPixelResults('invite-org-member', results)
  })
})
