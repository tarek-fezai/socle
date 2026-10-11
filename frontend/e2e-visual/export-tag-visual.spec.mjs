// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
/**
 * Règles : docs/visual-parity.md
 *
 * ExportTag.dc.html vs /tags/:id/export @ 1440×900.
 * Download / format : NOT_IMPLEMENTED (EXPORT-ZIP, décision tf) — libellés app restent PDF.
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
  reportAppExtras,
  reportNotImplemented,
  settleFonts,
  shotSection,
} from './visual-parity-kit.mjs'

const DECISION = 'DP-2026-10-11-tf — Word / Markdown hors ZIP (lignes app, pas de téléchargement)'

export const APP_EXTRA = [
  {
    id: 'export-format-word',
    page: 'export-tag',
    appMask: 'export-format-word',
    reason: 'ligne Word (pas d’API)',
    decision: DECISION,
  },
  {
    id: 'export-format-markdown',
    page: 'export-tag',
    appMask: 'export-format-markdown',
    reason: 'ligne Markdown (pas d’API)',
    decision: DECISION,
  },
]

export const NOT_IMPLEMENTED = [
  {
    id: 'export-preview',
    page: 'export-tag',
    reason: 'Liste des documents étiquetés dans l’aperçu : pas d’API de prévisualisation d’export',
    backlog: 'EXPORT-TAG-PREVIEW',
    mockMask: 'export-preview',
    appImplementedProbe: '[data-mock-id="export-preview"]',
  },
  {
    id: 'export-download',
    page: 'export-tag',
    reason: 'Maquette ZIP / « Télécharger l’archive (.zip) » ; l’API renvoie un PDF fusionné (PR ZIP suivante)',
    backlog: 'EXPORT-ZIP',
    mockMask: 'export-download',
  },
  {
    id: 'export-format-pdf',
    page: 'export-tag',
    reason: 'Maquette « ZIP de PDF » ; l’app affiche PDF (pas de mensonge ZIP)',
    backlog: 'EXPORT-ZIP',
    mockMask: 'export-format-pdf',
  },
]

const PIXEL_SECTIONS = [{ id: 'export-close', name: 'close', masks: [] }]

test.describe('export-tag visual', () => {
  test.use({ timezoneId: 'Europe/Paris' })

  test('desktop ExportTag — sections pixel ≤ 1 % (texte inclus)', async ({ page }, testInfo) => {
    await injectOidcSession(page, VISUAL_NOW)
    await mockParityApis(page)
    await page.setViewportSize({ width: 1440, height: 900 })

    await page.goto(`${MOCK_ORIGIN}/ExportTag.dc.html`)
    await annotateExportMockup(page, { crumbCurrent: 'Exporter le tag', maskZipFormats: true })
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
    await page.waitForSelector('[data-mock-id="export-close"]')
    await page.mouse.move(1, 1)
    await settleFonts(page)
    await assertNotImplementedGuards(page, NOT_IMPLEMENTED, 'export-tag')
    await reportAppExtras(page, testInfo, APP_EXTRA, 'export-tag')

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
