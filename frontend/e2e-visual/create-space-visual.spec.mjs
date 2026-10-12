// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
/**
 * Règles : docs/visual-parity.md
 *
 * CreateSpace.dc.html (modale 480 px) vs /spaces après clic « Nouvel espace ».
 * État figé par interaction Playwright (aucun forçage DOM).
 *
 * Mesure : `VISUAL_BASELINE=1 npx playwright test e2e-visual/create-space-visual.spec.mjs`
 */
import { test } from '@playwright/test'
import { VISUAL_NOW } from './dashboard-fixtures.mjs'
import { annotateCreateSpaceMockup, mockParityApis } from './parity-screens-kit.mjs'
import {
  MOCK_ORIGIN,
  assertNotImplementedGuards,
  compareSectionShots,
  finishPixelResults,
  injectOidcSession,
  probeAncestorStack,
  probeCompositionLayers,
  probeCompositingReasons,
  probeRasterProps,
  probeTextBox,
  reportNotImplemented,
  settleFonts,
  shotSection,
} from './visual-parity-kit.mjs'

export const NOT_IMPLEMENTED = []

const SIZE_EXCEPTIONS = {}

/** LCD maquette vs gris app à géométrie identique — docs/visual-parity.md § RASTER_EXCEPTIONS. */
const RASTER_EXCEPTIONS = {
  'create-space-cancel': {
    maxPixel: 0.05,
    reason: 'lissage LCD (<a> maquette) vs gris (<button> app), géométrie identique',
  },
  'create-space-submit': {
    maxPixel: 0.05,
    reason: 'lissage LCD (<a> maquette) vs gris (<button> app), géométrie identique',
  },
}

const PIXEL_SECTIONS = [
  { id: 'create-title', name: 'title', masks: [] },
  { id: 'create-close', name: 'close', masks: [] },
  { id: 'create-name-label', name: 'name-label', masks: [] },
  { id: 'create-desc-label', name: 'desc-label', masks: [] },
  { id: 'create-color-label', name: 'color-label', masks: [] },
  { id: 'create-vis-label', name: 'vis-label', masks: [] },
  { id: 'create-submit', name: 'submit', masks: [] },
  { id: 'create-cancel', name: 'cancel', masks: [] },
]

test.describe('create-space visual', () => {
  test.use({ timezoneId: 'Europe/Paris' })

  test('desktop CreateSpace — sections pixel ≤ 1 % (texte inclus)', async ({ page }, testInfo) => {
    await injectOidcSession(page, VISUAL_NOW)
    await mockParityApis(page)
    await page.setViewportSize({ width: 1440, height: 900 })

    await page.goto(`${MOCK_ORIGIN}/CreateSpace.dc.html`)
    await annotateCreateSpaceMockup(page)
    await settleFonts(page)
    await probeCompositionLayers(
      page,
      ['[data-mock-id="create-title"]', '[data-mock-id="create-submit"]', '[data-mock-id="create-cancel"]'],
      'create-space-mock',
    )
    await probeRasterProps(
      page,
      ['[data-mock-id="create-title"]', '[data-mock-id="create-submit"]', '[data-mock-id="create-cancel"]', '[data-mock-id="create-modal"]'],
      'create-space-mock',
    )
    const mockLayers = await probeCompositingReasons(
      page,
      ['[data-mock-id="create-modal"]', '[data-mock-id="create-title"]', '[data-mock-id="create-submit"]', '[data-mock-id="create-cancel"]'],
      'create-space-mock',
    )
    await probeAncestorStack(page, '[data-mock-id="create-modal"]', 'create-space-mock')
    await probeAncestorStack(page, '[data-mock-id="create-title"]', 'create-space-mock')
    await probeAncestorStack(page, '[data-mock-id="create-submit"]', 'create-space-mock')
    await probeAncestorStack(page, '[data-mock-id="create-cancel"]', 'create-space-mock')
    const mockSubmitTb = await probeTextBox(page, '[data-mock-id="create-submit"]', 'create-space-mock')
    const mockCancelTb = await probeTextBox(page, '[data-mock-id="create-cancel"]', 'create-space-mock')
    await reportNotImplemented(page, testInfo, NOT_IMPLEMENTED, 'create-space')

    const mockSec = {}
    for (const s of PIXEL_SECTIONS) {
      mockSec[s.name] = await shotSection(page, `[data-mock-id="${s.id}"]`, {
        maskNames: s.masks,
        label: `create-space-sec-${s.name}-mock`,
      })
    }

    await page.goto('/spaces')
    await page.waitForSelector('[data-mock-id="spaces-new-card"]')
    await page.locator('[data-mock-id="spaces-new-card"]').click()
    await page.waitForSelector('[data-mock-id="create-submit"]')
    await page.mouse.move(1, 1)
    await settleFonts(page)
    await probeCompositionLayers(
      page,
      ['[data-mock-id="create-title"]', '[data-mock-id="create-submit"]', '[data-mock-id="create-cancel"]'],
      'create-space-app',
    )
    await probeRasterProps(
      page,
      ['[data-mock-id="create-title"]', '[data-mock-id="create-submit"]', '[data-mock-id="create-cancel"]', '[data-mock-id="create-modal"]'],
      'create-space-app',
    )
    const appLayers = await probeCompositingReasons(
      page,
      ['[data-mock-id="create-modal"]', '[data-mock-id="create-title"]', '[data-mock-id="create-submit"]', '[data-mock-id="create-cancel"]'],
      'create-space-app',
    )
    await probeAncestorStack(page, '[data-mock-id="create-modal"]', 'create-space-app')
    await probeAncestorStack(page, '[data-mock-id="create-title"]', 'create-space-app')
    await probeAncestorStack(page, '[data-mock-id="create-submit"]', 'create-space-app')
    await probeAncestorStack(page, '[data-mock-id="create-cancel"]', 'create-space-app')
    const appSubmitTb = await probeTextBox(page, '[data-mock-id="create-submit"]', 'create-space-app')
    const appCancelTb = await probeTextBox(page, '[data-mock-id="create-cancel"]', 'create-space-app')
    await assertNotImplementedGuards(page, NOT_IMPLEMENTED, 'create-space')

    const rasterGeo = (sel, mockTb, appTb) => ({
      mock: mockTb,
      app: appTb,
      mockOwnLayers: mockLayers[sel]?.ownLayers ?? null,
      appOwnLayers: appLayers[sel]?.ownLayers ?? null,
    })
    const geos = {
      cancel: rasterGeo('[data-mock-id="create-cancel"]', mockCancelTb, appCancelTb),
      submit: rasterGeo('[data-mock-id="create-submit"]', mockSubmitTb, appSubmitTb),
    }
    const compare = compareSectionShots(SIZE_EXCEPTIONS, { raster: RASTER_EXCEPTIONS })
    const results = []
    for (const s of PIXEL_SECTIONS) {
      const app = await shotSection(page, `[data-mock-id="${s.id}"]`, {
        maskNames: s.masks,
        label: `create-space-sec-${s.name}-app`,
      })
      results.push(
        compare(
          `create-space-${s.name}`,
          mockSec[s.name].shot,
          app.shot,
          testInfo,
          geos[s.name],
        ),
      )
    }
    finishPixelResults('create-space', results)
  })
})
