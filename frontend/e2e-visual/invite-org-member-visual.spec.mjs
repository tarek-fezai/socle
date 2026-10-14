// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
/**
 * Règles : docs/visual-parity.md
 * InviteOrgMember.dc.html vs modal « Inviter un membre ».
 * Titre, Annuler, Envoyer et libellés sont comparés (pas d'exclusion).
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
  { id: 'invite-title', name: 'title', masks: [] },
  { id: 'invite-cancel', name: 'cancel', masks: [] },
  { id: 'invite-submit', name: 'submit', masks: [] },
  { id: 'invite-email-label', name: 'email-label', masks: [] },
  { id: 'invite-team-label', name: 'team-label', masks: [] },
  { id: 'invite-team-role-label', name: 'team-role-label', masks: [] },
  { id: 'invite-global-label', name: 'global-label', masks: [] },
]

const RASTER_PROBE = ['title', 'cancel', 'submit']

async function logFontSmoothingOff(page, sections, mockSec) {
  const before = await page.evaluate(() => getComputedStyle(document.documentElement).webkitFontSmoothing)
  await page.addStyleTag({
    content: ':root, html, body, #root { -webkit-font-smoothing: auto !important; }',
  })
  const after = await page.evaluate(() => getComputedStyle(document.documentElement).webkitFontSmoothing)
  for (const s of sections) {
    const app = await shotSection(page, `[data-mock-id="${s.id}"]`, {
      maskNames: s.masks,
      label: `invite-sec-${s.name}-smooth-off`,
    })
    const pct = diffRatio(mockSec[s.name].shot, app.shot, `smooth-off-${s.name}`) * 100
    console.log(
      `font-smoothing invite-${s.name}: off ${pct.toFixed(3)} % ; webkitFontSmoothing ${before || 'n/a'} → ${after || 'n/a'}`,
    )
  }
}

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
    const probeSel = RASTER_PROBE.map((name) => `[data-mock-id="invite-${name}"]`)
    const mockTb = {}
    for (const name of RASTER_PROBE) {
      mockTb[name] = await probeTextBox(page, `[data-mock-id="invite-${name}"]`, 'invite-mock')
    }
    const mockLayers = await probeCompositingReasons(page, probeSel, 'invite-mock')

    await page.goto('/admin/team')
    await page.waitForSelector('[data-mock-id="team-invite-cta"]')
    await page.getByRole('button', { name: /Inviter un membre/ }).click()
    await page.waitForSelector('[data-mock-id="invite-title"]')
    await page.mouse.move(1, 1)
    await settleFonts(page)
    await assertNotImplementedGuards(page, NOT_IMPLEMENTED, 'invite-org-member')
    await reportAppExtras(page, testInfo, APP_EXTRA, 'invite-org-member')

    const appTb = {}
    for (const name of RASTER_PROBE) {
      appTb[name] = await probeTextBox(page, `[data-mock-id="invite-${name}"]`, 'invite-app')
    }
    const appLayers = await probeCompositingReasons(page, probeSel, 'invite-app')
    const geos = {}
    for (const name of RASTER_PROBE) {
      const sel = `[data-mock-id="invite-${name}"]`
      geos[name] = {
        mock: mockTb[name],
        app: appTb[name],
        mockOwnLayers: mockLayers[sel]?.ownLayers ?? null,
        appOwnLayers: appLayers[sel]?.ownLayers ?? null,
      }
    }

    const compare = compareSectionShots(SIZE_EXCEPTIONS)
    const results = []
    for (const s of PIXEL_SECTIONS) {
      const app = await shotSection(page, `[data-mock-id="${s.id}"]`, {
        maskNames: s.masks,
        label: `invite-sec-${s.name}-app`,
      })
      const row = compare(`invite-${s.name}`, mockSec[s.name].shot, app.shot, testInfo, geos[s.name])
      if (geos[s.name]) {
        const v = rasterVerdict(geos[s.name])
        console.log(
          `RASTER probe invite-${s.name}: pct=${Number.isFinite(row.pct) ? row.pct.toFixed(3) : 'n/a'} applied=${v.applied}${v.reasons.length ? ` (${v.reasons.join(' ; ')})` : ''}`,
        )
      }
      results.push(row)
    }
    await logFontSmoothingOff(
      page,
      PIXEL_SECTIONS.filter((s) => RASTER_PROBE.includes(s.name)),
      mockSec,
    )
    finishPixelResults('invite-org-member', results)
  })
})
