// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
/**
 * Mode réduit de la sidebar contre le bloc collapsed de FolderReference.dc.html.
 * Le mode agrandi n'est pas rejoué ici : Dashboard.dc.html reste la référence,
 * et le bouton de repli est hors des sections déjà comparées.
 *
 * Arbre visible de la capture : Politique, Procédures, Référence (active).
 * Même ordre que la maquette, sans documents imbriqués.
 */
import { test } from '@playwright/test'
import { SPACE_IDENTITE, VISUAL_NOW } from './dashboard-fixtures.mjs'
import { FOLDER_PROC, FOLDER_REF, mockFolderApis } from './folder-visual-kit.mjs'
import {
  MOCK_ORIGIN,
  compareSectionShots,
  finishPixelResults,
  injectOidcSession,
  json,
  settleFonts,
  shotSection,
} from './visual-parity-kit.mjs'

const MAC_UA =
  'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36'

const COLLAPSED_TREE = {
  spaceId: SPACE_IDENTITE.id,
  spaceName: SPACE_IDENTITE.name,
  folders: [
    {
      id: FOLDER_PROC,
      name: 'Procédures',
      parentFolderId: null,
      position: 0,
      documentCount: 0,
      folderCount: 0,
    },
    {
      id: FOLDER_REF,
      name: 'Référence',
      parentFolderId: null,
      position: 1,
      documentCount: 0,
      folderCount: 0,
    },
  ],
  documents: [
    {
      id: 'd0000001-0000-4000-8000-000000000001',
      title: 'Politique de gestion des accès',
      folderId: null,
      position: 0,
      status: 'valide',
    },
  ],
}

test.use({
  timezoneId: 'Europe/Paris',
  userAgent: MAC_UA,
  launchOptions: { args: ['--disable-lcd-text'] },
})

test('desktop sidebar réduite — pixel ≤ 1 %', async ({ page }, testInfo) => {
  await injectOidcSession(page, VISUAL_NOW, { expandedSpaces: [SPACE_IDENTITE.id] })
  await page.addInitScript(() => {
    localStorage.setItem('socle.prefs.sidebarCollapsed', '1')
  })
  await mockFolderApis(page, 'reference')
  await page.route(`**/api/v1/spaces/${SPACE_IDENTITE.id}/tree**`, (route) => json(route, COLLAPSED_TREE))
  await page.setViewportSize({ width: 1440, height: 900 })

  await page.goto(`${MOCK_ORIGIN}/FolderReference.dc.html?collapsed=1`)
  await page.evaluate(() => {
    const root = document.querySelector('body div[style*="1440px"]') || document.body.firstElementChild
    root?.children?.[0]?.setAttribute('data-mock-id', 'shell-sidebar')
  })
  await page.mouse.move(1, 1)
  await settleFonts(page)
  const mock = await shotSection(page, '[data-mock-id="shell-sidebar"]', {
    label: 'sidebar-collapsed-mock',
  })

  await page.goto(`/folders/${FOLDER_REF}`)
  await page.waitForSelector('.shell-sidebar.is-collapsed')
  await page.waitForSelector('[title="Référence"]')
  await page.mouse.move(1, 1)
  await settleFonts(page)
  const app = await shotSection(page, '[data-mock-id="shell-sidebar"]', {
    label: 'sidebar-collapsed-app',
  })

  const compare = compareSectionShots()
  finishPixelResults('sidebar-collapsed', [
    compare('sidebar-collapsed', mock.shot, app.shot, testInfo),
  ])
})
