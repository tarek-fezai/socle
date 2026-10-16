// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
/**
 * Règles : docs/visual-parity.md
 *
 * FolderProcedures.dc.html vs /folders/:id @ 1440×900.
 * Texte inclus, seuil 1 % par section. La sidebar du shell n'est pas comparée
 * (décision DP-2026-10-10). Dates et auteurs viennent des réponses API mockées
 * (mêmes champs que GET /folders/{id} et GET /folders/{id}/page).
 * Barre du haut : Page / Graphe / Index. Barre d'onglets pleine largeur.
 * Plus de section toolbar (maquettes v159, DP-2026-10-16).
 */
import { test } from '@playwright/test'
import {
  annotateFolderMock,
  FOLDER_PROC,
  folderSections,
  mockFolderApis,
  VISUAL_NOW,
} from './folder-visual-kit.mjs'
import {
  MOCK_ORIGIN,
  compareSectionShots,
  finishPixelResults,
  injectOidcSession,
  settleFonts,
  shotSection,
} from './visual-parity-kit.mjs'

const MAC_UA =
  'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36'

test.use({
  timezoneId: 'Europe/Paris',
  userAgent: MAC_UA,
  launchOptions: { args: ['--disable-lcd-text'] },
})

test('desktop FolderProcedures — sections pixel ≤ 1 % (texte inclus)', async ({ page }, testInfo) => {
  await injectOidcSession(page, VISUAL_NOW)
  await page.addInitScript((spaceId) => {
    localStorage.setItem('socle.shell.expandedSpaces', JSON.stringify([spaceId]))
  }, 's0000001-0000-4000-8000-000000000001')
  await mockFolderApis(page, 'procedures')
  await page.setViewportSize({ width: 1440, height: 900 })

  await page.goto(`${MOCK_ORIGIN}/FolderProcedures.dc.html`)
  await annotateFolderMock(page)
  await settleFonts(page)
  const sections = folderSections(3)
  const mock = {}
  for (const s of sections) {
    mock[s.name] = await shotSection(page, `[data-mock-id="${s.id}"]`, {
      label: `folder-procedures-${s.name}-mock`,
    })
  }

  await page.goto(`/folders/${FOLDER_PROC}`)
  await page.waitForSelector('[data-mock-id="fp-title"]')
  await page.waitForSelector('text=Révisé le 12 septembre 2026')
  await page.waitForSelector('text=En revue depuis le 20 septembre 2026')
  await settleFonts(page)
  const compare = compareSectionShots()
  const results = []
  for (const s of sections) {
    await page.mouse.move(1, 1)
    const app = await shotSection(page, `[data-mock-id="${s.id}"]`, {
      label: `folder-procedures-${s.name}-app`,
    })
    results.push(compare(`folder-procedures-${s.name}`, mock[s.name].shot, app.shot, testInfo))
  }
  finishPixelResults('folder-procedures', results)
})
