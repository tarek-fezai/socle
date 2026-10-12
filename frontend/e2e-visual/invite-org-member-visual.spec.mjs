// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
/**
 * Règles : docs/visual-parity.md
 * InviteOrgMember.dc.html vs modal « Inviter un membre ».
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
  probeAncestorStack,
  probeCompositionLayers,
  probeCompositingReasons,
  probeRasterProps,
  probeTextBox,
  reportAppExtras,
  reportNotImplemented,
  settleFonts,
  shotSection,
} from './visual-parity-kit.mjs'

export const APP_EXTRA = []
export const NOT_IMPLEMENTED = []

const SIZE_EXCEPTIONS = {}

/** LCD maquette vs gris app à géométrie identique — docs/visual-parity.md § RASTER_EXCEPTIONS. */
const RASTER_EXCEPTIONS = {
  'invite-cancel': {
    maxPixel: 0.05,
    reason: 'lissage LCD (<a> maquette) vs gris (app :root antialiased), géométrie identique',
  },
  'invite-submit': {
    maxPixel: 0.05,
    reason: 'lissage LCD (<a> maquette) vs gris (app :root antialiased), géométrie identique',
  },
}

const PIXEL_SECTIONS = [
  { id: 'invite-title', name: 'title', masks: [] },
  { id: 'invite-email-label', name: 'email-label', masks: [] },
  { id: 'invite-team-label', name: 'team-label', masks: [] },
  { id: 'invite-team-role-label', name: 'team-role-label', masks: [] },
  { id: 'invite-global-label', name: 'global-label', masks: [] },
  { id: 'invite-submit', name: 'submit', masks: [] },
  { id: 'invite-cancel', name: 'cancel', masks: [] },
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
    await probeCompositionLayers(
      page,
      ['[data-mock-id="invite-title"]', '[data-mock-id="invite-submit"]', '[data-mock-id="invite-cancel"]'],
      'invite-org-member-mock',
    )
    await probeRasterProps(
      page,
      ['[data-mock-id="invite-title"]', '[data-mock-id="invite-submit"]', '[data-mock-id="invite-cancel"]', '[data-mock-id="invite-modal"]'],
      'invite-org-member-mock',
    )
    const mockLayers = await probeCompositingReasons(
      page,
      ['[data-mock-id="invite-modal"]', '[data-mock-id="invite-title"]', '[data-mock-id="invite-submit"]', '[data-mock-id="invite-cancel"]'],
      'invite-org-member-mock',
    )
    await probeAncestorStack(page, '[data-mock-id="invite-modal"]', 'invite-org-member-mock')
    await probeAncestorStack(page, '[data-mock-id="invite-title"]', 'invite-org-member-mock')
    await probeAncestorStack(page, '[data-mock-id="invite-submit"]', 'invite-org-member-mock')
    await probeAncestorStack(page, '[data-mock-id="invite-cancel"]', 'invite-org-member-mock')
    const mockSubmitTb = await probeTextBox(page, '[data-mock-id="invite-submit"]', 'invite-org-member-mock')
    const mockCancelTb = await probeTextBox(page, '[data-mock-id="invite-cancel"]', 'invite-org-member-mock')
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
    await probeCompositionLayers(
      page,
      ['[data-mock-id="invite-title"]', '[data-mock-id="invite-submit"]', '[data-mock-id="invite-cancel"]'],
      'invite-org-member-app',
    )
    await probeRasterProps(
      page,
      ['[data-mock-id="invite-title"]', '[data-mock-id="invite-submit"]', '[data-mock-id="invite-cancel"]', '[data-mock-id="invite-modal"]'],
      'invite-org-member-app',
    )
    const appLayers = await probeCompositingReasons(
      page,
      ['[data-mock-id="invite-modal"]', '[data-mock-id="invite-title"]', '[data-mock-id="invite-submit"]', '[data-mock-id="invite-cancel"]'],
      'invite-org-member-app',
    )
    await probeAncestorStack(page, '[data-mock-id="invite-modal"]', 'invite-org-member-app')
    await probeAncestorStack(page, '[data-mock-id="invite-title"]', 'invite-org-member-app')
    await probeAncestorStack(page, '[data-mock-id="invite-submit"]', 'invite-org-member-app')
    await probeAncestorStack(page, '[data-mock-id="invite-cancel"]', 'invite-org-member-app')
    const appSubmitTb = await probeTextBox(page, '[data-mock-id="invite-submit"]', 'invite-org-member-app')
    const appCancelTb = await probeTextBox(page, '[data-mock-id="invite-cancel"]', 'invite-org-member-app')
    await assertNotImplementedGuards(page, NOT_IMPLEMENTED, 'invite-org-member')
    await reportAppExtras(page, testInfo, APP_EXTRA, 'invite-org-member')

    const rasterGeo = (sel, mockTb, appTb) => ({
      mock: mockTb,
      app: appTb,
      mockOwnLayers: mockLayers[sel]?.ownLayers ?? null,
      appOwnLayers: appLayers[sel]?.ownLayers ?? null,
    })
    const geos = {
      cancel: rasterGeo('[data-mock-id="invite-cancel"]', mockCancelTb, appCancelTb),
      submit: rasterGeo('[data-mock-id="invite-submit"]', mockSubmitTb, appSubmitTb),
    }
    const compare = compareSectionShots(SIZE_EXCEPTIONS, { raster: RASTER_EXCEPTIONS })
    const results = []
    for (const s of PIXEL_SECTIONS) {
      const app = await shotSection(page, `[data-mock-id="${s.id}"]`, {
        maskNames: s.masks,
        label: `invite-sec-${s.name}-app`,
      })
      results.push(
        compare(`invite-${s.name}`, mockSec[s.name].shot, app.shot, testInfo, geos[s.name]),
      )
    }
    finishPixelResults('invite-org-member', results)
  })
})
