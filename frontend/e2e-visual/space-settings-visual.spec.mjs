// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
/**
 * Règles : docs/visual-parity.md
 *
 * SpaceSettings.dc.html = arbre 420 px + panneau paramètres 620 px.
 * Route app : /spaces/:id (même mise en page).
 *
 * Mesure : `VISUAL_BASELINE=1 npx playwright test e2e-visual/space-settings-visual.spec.mjs`
 */
import { test } from '@playwright/test'
import { SPACE_IDENTITE, VISUAL_NOW } from './dashboard-fixtures.mjs'
import { annotateSettingsMockup, mockParityApis } from './parity-screens-kit.mjs'
import {
  MOCK_ORIGIN,
  assertNotImplementedGuards,
  compareSectionShots,
  finishPixelResults,
  injectOidcSession,
  probeCompositionLayers,
  reportAppExtras,
  reportNotImplemented,
  settleFonts,
  shotSection,
} from './visual-parity-kit.mjs'

const DECISION =
  'DP-2026-10-11-tf — description, politique de commentaires, propriétaires, Enregistrer (relecteur)'

export const APP_EXTRA = [
  {
    id: 'settings-description',
    page: 'space-settings',
    appMask: 'settings-description',
    reason: 'champ description (API, hors maquette paramètres comparée)',
    decision: DECISION,
  },
  {
    id: 'settings-comment-policy',
    page: 'space-settings',
    appMask: 'settings-comment-policy',
    reason: 'politique de commentaires',
    decision: DECISION,
  },
  {
    id: 'settings-save',
    page: 'space-settings',
    appMask: 'settings-save',
    reason: 'bouton Enregistrer',
    decision: DECISION,
  },
  {
    id: 'settings-owners',
    page: 'space-settings',
    appMask: 'settings-owners',
    reason: 'owners / responsible (pas le bloc Membres maquette)',
    decision: DECISION,
  },
]

export const NOT_IMPLEMENTED = [
  {
    id: 'settings-members',
    page: 'space-settings',
    reason:
      'Membres maquette (TF Propriétaire, CD Approbatrice) : l’API owners n’a pas le rôle Approbatrice ni les initiales',
    backlog: 'SETTINGS-MEMBERS',
    mockMask: 'settings-members',
  },
  {
    id: 'settings-archive',
    page: 'space-settings',
    reason: '« Archiver cet espace » : pas d’endpoint d’archivage',
    backlog: 'SETTINGS-ARCHIVE',
    mockMask: 'settings-archive',
    appImplementedProbe: '[data-mock-id="settings-archive"]',
  },
]

const SIZE_EXCEPTIONS = Object.fromEntries(
  ['title', 'name', 'color', 'visibility', 'audit', 'trash'].map((name) => [
    `space-settings-${name}`,
    {
      dw: 1,
      dh: 0,
      reason:
        'Colonne maquette mesurée 621 px (CSS 620, centrage sous-pixel) ; app width 621 mesurée 622',
    },
  ]),
)

const PIXEL_SECTIONS = [
  { id: 'settings-title', name: 'title', masks: [] },
  { id: 'settings-name', name: 'name', masks: [] },
  { id: 'settings-color', name: 'color', masks: [] },
  { id: 'settings-visibility', name: 'visibility', masks: [] },
  { id: 'settings-audit', name: 'audit', masks: [] },
  { id: 'settings-trash', name: 'trash', masks: [] },
]

test.describe('space-settings visual', () => {
  test.use({ timezoneId: 'Europe/Paris' })

  test('desktop SpaceSettings — sections pixel ≤ 1 % (texte inclus)', async ({ page }, testInfo) => {
    await injectOidcSession(page, VISUAL_NOW)
    await mockParityApis(page)
    await page.setViewportSize({ width: 1440, height: 900 })

    await page.goto(`${MOCK_ORIGIN}/SpaceSettings.dc.html`)
    await annotateSettingsMockup(page)
    await settleFonts(page)
    await probeCompositionLayers(
      page,
      ['[data-mock-id="settings-audit"]', '[data-mock-id="settings-trash"]'],
      'space-settings-mock',
    )
    await reportNotImplemented(page, testInfo, NOT_IMPLEMENTED, 'space-settings')

    const mockSec = {}
    for (const s of PIXEL_SECTIONS) {
      mockSec[s.name] = await shotSection(page, `[data-mock-id="${s.id}"]`, {
        maskNames: s.masks,
        label: `space-settings-sec-${s.name}-mock`,
      })
    }

    await page.goto(`/spaces/${SPACE_IDENTITE.id}`)
    await page.waitForSelector('[data-mock-id="settings-title"]')
    await page.mouse.move(1, 1)
    await settleFonts(page)
    await probeCompositionLayers(
      page,
      ['[data-mock-id="settings-audit"]', '[data-mock-id="settings-trash"]'],
      'space-settings-app',
    )
    await assertNotImplementedGuards(page, NOT_IMPLEMENTED, 'space-settings')
    await reportAppExtras(page, testInfo, APP_EXTRA, 'space-settings')

    const compare = compareSectionShots(SIZE_EXCEPTIONS)
    const results = []
    for (const s of PIXEL_SECTIONS) {
      const app = await shotSection(page, `[data-mock-id="${s.id}"]`, {
        maskNames: s.masks,
        label: `space-settings-sec-${s.name}-app`,
      })
      results.push(compare(`space-settings-${s.name}`, mockSec[s.name].shot, app.shot, testInfo))
    }
    finishPixelResults('space-settings', results)
  })
})
