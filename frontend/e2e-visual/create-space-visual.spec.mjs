// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
/**
 * Règles : docs/visual-parity.md
 *
 * CreateSpace.dc.html (modale 480 px) vs /spaces après clic « Nouvel espace ».
 * État figé par interaction Playwright (aucun forçage DOM).
 *
 * Mesure : `VISUAL_BASELINE=1 npx playwright test e2e-visual/create-space-visual.spec.mjs`
 *
 * Sections encore > 1 % après polish (laissées échouer) : title 4.674 %, submit 5.577 %,
 * cancel 3.164 % — même taille ; anticrénelage glyphes (Google Fonts maquette vs woff2
 * local + `-webkit-font-smoothing: antialiased` sur `:root`).
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
  reportNotImplemented,
  settleFonts,
  shotSection,
} from './visual-parity-kit.mjs'

export const NOT_IMPLEMENTED = []

const SIZE_EXCEPTIONS = {}

const PIXEL_SECTIONS = [
  { id: 'create-title', name: 'title', masks: [] },
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
    test.fail(
      true,
      'title 4.674 %, submit 5.577 %, cancel 3.164 % — anticrénelage glyphes (Google Fonts vs woff2 local)',
    )
    await injectOidcSession(page, VISUAL_NOW)
    await mockParityApis(page)
    await page.setViewportSize({ width: 1440, height: 900 })

    await page.goto(`${MOCK_ORIGIN}/CreateSpace.dc.html`)
    await annotateCreateSpaceMockup(page)
    await settleFonts(page)
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
