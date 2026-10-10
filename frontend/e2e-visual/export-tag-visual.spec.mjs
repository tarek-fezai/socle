// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
/**
 * Règles : docs/visual-parity.md
 *
 * ExportTag.dc.html vs /tags/:id/export @ 1440×900.
 * Mesure : `VISUAL_BASELINE=1 npx playwright test e2e-visual/export-tag-visual.spec.mjs`
 *
 * Écart de conception (laissé échouer, pas de mensonge ZIP) : l’API exportTag
 * renvoie un PDF fusionné ; la maquette affiche « ZIP de PDF » / « Télécharger
 * l’archive (.zip) ». download 5.664 %, format-pdf 4.144 %.
 */
import { test } from '@playwright/test'
import { VISUAL_NOW } from './dashboard-fixtures.mjs'
import { EXPORT_TAG_ID } from './parity-screens-fixtures.mjs'
import { annotateExportMockup, mockParityApis } from './parity-screens-kit.mjs'
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

export const NOT_IMPLEMENTED = [
  {
    id: 'export-preview',
    page: 'export-tag',
    reason: 'Liste des documents étiquetés dans l’aperçu : pas d’API de prévisualisation d’export',
    backlog: 'EXPORT-TAG-PREVIEW',
    mockMask: 'export-preview',
    appImplementedProbe: '[data-mock-id="export-preview"]',
  },
]

const PIXEL_SECTIONS = [
  { id: 'export-close', name: 'close', masks: [] },
  { id: 'export-download', name: 'download', masks: [] },
  { id: 'export-format-pdf', name: 'format-pdf', masks: [] },
]

test.describe('export-tag visual', () => {
  test.use({ timezoneId: 'Europe/Paris' })

  test('desktop ExportTag — sections pixel ≤ 1 % (texte inclus)', async ({ page }, testInfo) => {
    test.fail(
      true,
      'download 5.664 %, format-pdf 4.144 % — ZIP maquette vs PDF fusionné API (pas de mensonge ZIP)',
    )
    await injectOidcSession(page, VISUAL_NOW)
    await mockParityApis(page)
    await page.setViewportSize({ width: 1440, height: 900 })

    await page.goto(`${MOCK_ORIGIN}/ExportTag.dc.html`)
    await annotateExportMockup(page, { crumbCurrent: 'Exporter le tag' })
    await settleFonts(page)
    await reportNotImplemented(page, testInfo, NOT_IMPLEMENTED, 'export-tag')

    const mockSec = {}
    for (const s of PIXEL_SECTIONS) {
      mockSec[s.name] = await shotSection(page, `[data-mock-id="${s.id}"]`, {
        maskNames: s.masks,
        label: `export-tag-sec-${s.name}-mock`,
      })
    }

    await page.goto(`/tags/${EXPORT_TAG_ID}/export`)
    await page.waitForSelector('[data-mock-id="export-download"]')
    await page.mouse.move(1, 1)
    await settleFonts(page)
    await assertNotImplementedGuards(page, NOT_IMPLEMENTED, 'export-tag')

    const compare = compareSectionShots({})
    const results = []
    for (const s of PIXEL_SECTIONS) {
      const app = await shotSection(page, `[data-mock-id="${s.id}"]`, {
        maskNames: s.masks,
        label: `export-tag-sec-${s.name}-app`,
      })
      results.push(compare(`export-tag-${s.name}`, mockSec[s.name].shot, app.shot, testInfo))
    }
    finishPixelResults('export-tag', results)
  })
})
