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
  reportNotImplemented,
  settleFonts,
  shotSection,
} from './visual-parity-kit.mjs'

export const NOT_IMPLEMENTED = []

const SIZE_EXCEPTIONS = {}

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
    await probeCompositingReasons(
      page,
      ['[data-mock-id="create-modal"]', '[data-mock-id="create-title"]', '[data-mock-id="create-submit"]', '[data-mock-id="create-cancel"]'],
      'create-space-mock',
    )
    await probeAncestorStack(page, '[data-mock-id="create-modal"]', 'create-space-mock')
    await probeAncestorStack(page, '[data-mock-id="create-title"]', 'create-space-mock')
    await probeAncestorStack(page, '[data-mock-id="create-submit"]', 'create-space-mock')
    await probeAncestorStack(page, '[data-mock-id="create-cancel"]', 'create-space-mock')
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
    await probeCompositingReasons(
      page,
      ['[data-mock-id="create-modal"]', '[data-mock-id="create-title"]', '[data-mock-id="create-submit"]', '[data-mock-id="create-cancel"]'],
      'create-space-app',
    )
    await probeAncestorStack(page, '[data-mock-id="create-modal"]', 'create-space-app')
    await probeAncestorStack(page, '[data-mock-id="create-title"]', 'create-space-app')
    await probeAncestorStack(page, '[data-mock-id="create-submit"]', 'create-space-app')
    await probeAncestorStack(page, '[data-mock-id="create-cancel"]', 'create-space-app')
    await assertNotImplementedGuards(page, NOT_IMPLEMENTED, 'create-space')

    const compare = compareSectionShots(SIZE_EXCEPTIONS)
    const results = []
    for (const s of PIXEL_SECTIONS) {
      const app = await shotSection(page, `[data-mock-id="${s.id}"]`, {
        maskNames: s.masks,
        label: `create-space-sec-${s.name}-app`,
      })
      results.push(compare(`create-space-${s.name}`, mockSec[s.name].shot, app.shot, testInfo))
    }
    finishPixelResults('create-space', results)
  })
})
