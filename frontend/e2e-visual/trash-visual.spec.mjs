// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
/**
 * Règles : docs/visual-parity.md
 *
 * /trash vs Trash.dc.html @ 1440×900.
 * Mesure : `VISUAL_BASELINE=1 npx playwright test e2e-visual/trash-visual.spec.mjs`
 *
 * Sections encore > 1 % après polish (laissées échouer) : trash-row-*-delete 8.067 %
 * (169×30 des deux côtés) — anticrénelage du libellé « Supprimer définitivement ».
 */
import { test } from '@playwright/test'
import { ME_TAREK, VISUAL_NOW } from './dashboard-fixtures.mjs'
import { annotateTrashMockup, mockParityApis } from './parity-screens-kit.mjs'
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
    id: 'trash-space-chrome',
    page: 'trash',
    reason:
      'Maquette corbeille d’espace (Espaces → Identité → Corbeille + Retour aux paramètres) ; l’app est globale /trash (Accueil → Corbeille)',
    backlog: 'TRASH-SPACE-SCOPED',
    mockMask: 'trash-space-chrome',
  },
]

const SIZE_EXCEPTIONS = {}

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
    test.fail(true, 'trash-row-*-delete 8.067 % (169×30) — anticrénelage « Supprimer définitivement »')
    await injectOidcSession(page, VISUAL_NOW)
    await mockParityApis(page)
    await page.setViewportSize({ width: 1440, height: 900 })

    await page.goto(`${MOCK_ORIGIN}/Trash.dc.html`)
    await annotateTrashMockup(page)
    await settleFonts(page)
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
    await assertNotImplementedGuards(page, NOT_IMPLEMENTED, 'trash')

    const compare = compareSectionShots(SIZE_EXCEPTIONS)
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
