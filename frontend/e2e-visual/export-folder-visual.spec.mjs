// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
/**
 * Règles : docs/visual-parity.md
 *
 * ExportFolder.dc.html vs /folders/:id/export @ 1440×900.
 * Download / format : NOT_IMPLEMENTED (EXPORT-ZIP, décision tf) — libellés app restent PDF.
 */
import { test } from '@playwright/test'
import { VISUAL_NOW } from './dashboard-fixtures.mjs'
import { EXPORT_FOLDER_ID } from './parity-screens-fixtures.mjs'
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
    page: 'export-folder',
    appMask: 'export-format-word',
    reason: 'ligne Word (pas d’API)',
    decision: DECISION,
  },
  {
    id: 'export-format-markdown',
    page: 'export-folder',
    appMask: 'export-format-markdown',
    reason: 'ligne Markdown (pas d’API)',
    decision: DECISION,
  },
]

export const NOT_IMPLEMENTED = [
  {
    id: 'export-preview',
    page: 'export-folder',
    reason: 'Liste des documents du dossier dans l’aperçu : pas d’API de prévisualisation d’export',
    backlog: 'EXPORT-FOLDER-PREVIEW',
    mockMask: 'export-preview',
    appImplementedProbe: '[data-mock-id="export-preview"]',
  },
  {
    id: 'export-download',
    page: 'export-folder',
    reason: 'Maquette ZIP / « Télécharger l’archive (.zip) » ; l’API renvoie un PDF fusionné (PR ZIP suivante)',
    backlog: 'EXPORT-ZIP',
    mockMask: 'export-download',
  },
  {
    id: 'export-format-pdf',
    page: 'export-folder',
    reason: 'Maquette « ZIP de PDF » ; l’app affiche PDF (pas de mensonge ZIP)',
    backlog: 'EXPORT-ZIP',
    mockMask: 'export-format-pdf',
  },
]

const PIXEL_SECTIONS = [{ id: 'export-close', name: 'close', masks: [] }]

test.describe('export-folder visual', () => {
  test.use({ timezoneId: 'Europe/Paris' })

  test('desktop ExportFolder — sections pixel ≤ 1 % (texte inclus)', async ({ page }, testInfo) => {
    await injectOidcSession(page, VISUAL_NOW)
    await mockParityApis(page)
    await page.setViewportSize({ width: 1440, height: 900 })

    await page.goto(`${MOCK_ORIGIN}/ExportFolder.dc.html`)
    await annotateExportMockup(page, { crumbCurrent: 'Exporter le dossier', maskZipFormats: true })
    await settleFonts(page)
    await reportNotImplemented(page, testInfo, NOT_IMPLEMENTED, 'export-folder')

    const mockSec = {}
    for (const s of PIXEL_SECTIONS) {
      mockSec[s.name] = await shotSection(page, `[data-mock-id="${s.id}"]`, {
        maskNames: s.masks,
        label: `export-folder-sec-${s.name}-mock`,
      })
    }

    await page.goto(`/folders/${EXPORT_FOLDER_ID}/export`)
    await page.waitForSelector('[data-mock-id="export-close"]')
    await page.mouse.move(1, 1)
    await settleFonts(page)
    await assertNotImplementedGuards(page, NOT_IMPLEMENTED, 'export-folder')
    await reportAppExtras(page, testInfo, APP_EXTRA, 'export-folder')

    const compare = compareSectionShots({})
    const results = []
    for (const s of PIXEL_SECTIONS) {
      const app = await shotSection(page, `[data-mock-id="${s.id}"]`, {
        maskNames: s.masks,
        label: `export-folder-sec-${s.name}-app`,
      })
      results.push(compare(`export-folder-${s.name}`, mockSec[s.name].shot, app.shot, testInfo))
    }
    finishPixelResults('export-folder', results)
  })
})
