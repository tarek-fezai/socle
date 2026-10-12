// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
/**
 * Règles : docs/visual-parity.md
 *
 * /trash (globale) vs Trash.dc.html @ 1440×900.
 * Vue corbeille d’espace : NOT_IMPLEMENTED TRASH-PER-SPACE (GET /api/v1/trash
 * n’accepte que resourceType / offset / limit — pas de spaceId).
 *
 * Mesure : `VISUAL_BASELINE=1 npx playwright test e2e-visual/trash-visual.spec.mjs`
 */
import { test } from '@playwright/test'
import { VISUAL_NOW } from './dashboard-fixtures.mjs'
import { annotateTrashMockup, mockParityApis } from './parity-screens-kit.mjs'
import {
  MOCK_ORIGIN,
  assertNotImplementedGuards,
  compareSectionShots,
  finishPixelResults,
  injectOidcSession,
  probeCompositionLayers,
  probeRasterProps,
  reportAppExtras,
  reportNotImplemented,
  settleFonts,
  shotSection,
} from './visual-parity-kit.mjs'

const DECISION = 'DP-2026-10-11-tf — filtres de type sur /trash globale (hors maquette)'

export const APP_EXTRA = [
  {
    id: 'trash-type-filters',
    page: 'trash',
    appMask: 'trash-type-filters',
    reason: 'filtres document / dossier / espace',
    decision: DECISION,
  },
]

export const NOT_IMPLEMENTED = [
  {
    id: 'trash-space-chrome',
    page: 'trash',
    reason:
      'Maquette corbeille d’espace (Espaces → Identité → Corbeille + Retour aux paramètres) ; GET /api/v1/trash sans spaceId',
    backlog: 'TRASH-PER-SPACE',
    mockMask: 'trash-space-chrome',
  },
]

const PIXEL_SECTIONS = [
  { id: 'trash-title', name: 'title', masks: [] },
  { id: 'trash-lead', name: 'lead', masks: [] },
  ...[0, 1, 2].flatMap((i) => [
    { id: `trash-row-${i}-title`, name: `row-${i}-title`, masks: [] },
    { id: `trash-row-${i}-meta`, name: `row-${i}-meta`, masks: [] },
    { id: `trash-row-${i}-purge`, name: `row-${i}-purge`, masks: [] },
    { id: `trash-row-${i}-restore`, name: `row-${i}-restore`, masks: [] },
    { id: `trash-row-${i}-delete`, name: `row-${i}-delete`, masks: [] },
  ]),
  { id: 'trash-footer', name: 'footer', masks: [] },
]

test.describe('trash visual', () => {
  test.use({ timezoneId: 'Europe/Paris' })

  test('desktop Trash — sections pixel ≤ 1 % (texte inclus)', async ({ page }, testInfo) => {
    await injectOidcSession(page, VISUAL_NOW)
    await mockParityApis(page)
    await page.setViewportSize({ width: 1440, height: 900 })

    await page.goto(`${MOCK_ORIGIN}/Trash.dc.html`)
    await annotateTrashMockup(page)
    await settleFonts(page)
    await probeCompositionLayers(page, ['[data-mock-id="trash-row-0-delete"]'], 'trash-mock')
    await probeRasterProps(page, ['[data-mock-id="trash-row-0-delete"]'], 'trash-mock')
    await reportNotImplemented(page, testInfo, NOT_IMPLEMENTED, 'trash')

    const mockSec = {}
    for (const s of PIXEL_SECTIONS) {
      mockSec[s.name] = await shotSection(page, `[data-mock-id="${s.id}"]`, {
        maskNames: s.masks,
        label: `trash-sec-${s.name}-mock`,
      })
    }

    await page.goto('/trash')
    await page.waitForSelector('[data-mock-id="trash-row-2-title"]')
    await page.mouse.move(1, 1)
    await settleFonts(page)
    await probeCompositionLayers(page, ['[data-mock-id="trash-row-0-delete"]'], 'trash-app')
    await probeRasterProps(page, ['[data-mock-id="trash-row-0-delete"]'], 'trash-app')
    await assertNotImplementedGuards(page, NOT_IMPLEMENTED, 'trash')
    await reportAppExtras(page, testInfo, APP_EXTRA, 'trash')

    const compare = compareSectionShots({})
    const results = []
    for (const s of PIXEL_SECTIONS) {
      const app = await shotSection(page, `[data-mock-id="${s.id}"]`, {
        maskNames: s.masks,
        label: `trash-sec-${s.name}-app`,
      })
      results.push(compare(`trash-${s.name}`, mockSec[s.name].shot, app.shot, testInfo))
    }
    finishPixelResults('trash', results)
  })
})
