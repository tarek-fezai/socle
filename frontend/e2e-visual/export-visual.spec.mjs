// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
/**
 * Règles : docs/visual-parity.md
 *
 * Export.dc.html vs /docs/:id/export @ 1440×900.
 * Mesure : `VISUAL_BASELINE=1 npx playwright test e2e-visual/export-visual.spec.mjs`
 */
import { test } from '@playwright/test'
import { VISUAL_NOW } from './dashboard-fixtures.mjs'
import { EXPORT_DOC_ID } from './parity-screens-fixtures.mjs'
import { annotateExportMockup, mockParityApis } from './parity-screens-kit.mjs'
import {
  MOCK_ORIGIN,
  assertNotImplementedGuards,
  compareSectionShots,
  finishPixelResults,
  injectOidcSession,
  reportAppExtras,
  reportNotImplemented,
  settleFonts,
  shotSection,
} from './visual-parity-kit.mjs'

const DECISION = 'DP-2026-10-11-tf — Word / Markdown hors ZIP (lignes app, pas de téléchargement)'

export const APP_EXTRA = [
  {
    id: 'export-format-word',
    page: 'export',
    appMask: 'export-format-word',
    reason: 'ligne Word (pas d’API)',
    decision: DECISION,
  },
  {
    id: 'export-format-markdown',
    page: 'export',
    appMask: 'export-format-markdown',
    reason: 'ligne Markdown (pas d’API)',
    decision: DECISION,
  },
]

export const NOT_IMPLEMENTED = [
  {
    id: 'export-preview',
    page: 'export',
    reason: 'Aperçu PDF 560×792 : l’API d’export renvoie un blob à télécharger, pas une prévisualisation',
    backlog: 'EXPORT-PREVIEW',
    mockMask: 'export-preview',
    appImplementedProbe: '[data-mock-id="export-preview"]',
  },
]

const SIZE_EXCEPTIONS = {}

const PIXEL_SECTIONS = [
  { id: 'export-close', name: 'close', masks: [] },
  { id: 'export-download', name: 'download', masks: [] },
  { id: 'export-format-pdf', name: 'format-pdf', masks: [] },
]

test.describe('export visual', () => {
  test.use({ timezoneId: 'Europe/Paris' })

  test('desktop Export document — sections pixel ≤ 1 % (texte inclus)', async ({ page }, testInfo) => {
    await injectOidcSession(page, VISUAL_NOW)
    await mockParityApis(page)
    await page.setViewportSize({ width: 1440, height: 900 })

    await page.goto(`${MOCK_ORIGIN}/Export.dc.html`)
    await annotateExportMockup(page, { crumbCurrent: 'Exporter' })
    await settleFonts(page)
    await reportNotImplemented(page, testInfo, NOT_IMPLEMENTED, 'export')

    const mockSec = {}
    for (const s of PIXEL_SECTIONS) {
      mockSec[s.name] = await shotSection(page, `[data-mock-id="${s.id}"]`, {
        maskNames: s.masks,
        label: `export-sec-${s.name}-mock`,
      })
    }

    await page.goto(`/docs/${EXPORT_DOC_ID}/export`)
    await page.waitForSelector('[data-mock-id="export-download"]')
    await page.mouse.move(1, 1)
    await settleFonts(page)
    await assertNotImplementedGuards(page, NOT_IMPLEMENTED, 'export')
    await reportAppExtras(page, testInfo, APP_EXTRA, 'export')

    const compare = compareSectionShots(SIZE_EXCEPTIONS)
    const results = []
    for (const s of PIXEL_SECTIONS) {
      const app = await shotSection(page, `[data-mock-id="${s.id}"]`, {
        maskNames: s.masks,
        label: `export-sec-${s.name}-app`,
      })
      results.push(compare(`export-${s.name}`, mockSec[s.name].shot, app.shot, testInfo))
    }
    finishPixelResults('export', results)
  })
})
