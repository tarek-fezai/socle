// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
/**
 * Mode réduit de la sidebar contre le bloc collapsed de FolderReference.dc.html.
 * La section shell-brand-row compare la ligne entière (logo et bouton), agrandie et réduite.
 *
 * Arbre visible de la capture : Politique, Procédures, Référence (active).
 * Même ordre que la maquette, sans documents imbriqués.
 *
 * Le choix réduit est mémorisé sur cet appareil (navigateur), sans stockage serveur.
 */
import { test } from '@playwright/test'
import { ME_TAREK, NOTIFICATIONS_SEED, SPACE_IDENTITE, VISUAL_NOW } from './dashboard-fixtures.mjs'
import { FOLDER_PROC, FOLDER_REF, mockFolderApis } from './folder-visual-kit.mjs'
import {
  MOCK_ORIGIN,
  compareSectionShots,
  finishPixelResults,
  injectOidcSession,
  json,
  mockBaseApis,
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

/** Coordonnées du bouton (repère viewport) pour le rapport de parité. */
async function logCollapseButton(page, label) {
  const box = await page.evaluate(() => {
    const el = document.querySelector('.collapse-btn, .shell-collapse')
    if (!el) return null
    const r = el.getBoundingClientRect()
    return { x: r.x, y: r.y, w: r.width, h: r.height }
  })
  if (!box) throw new Error(`bouton de repli absent (${label})`)
  console.log(
    `BUTTON ${label} x=${box.x.toFixed(1)} y=${box.y.toFixed(1)} w=${box.w.toFixed(1)} h=${box.h.toFixed(1)}`,
  )
}

/** Ancre la ligne logo + bouton. data-mock-id seulement : la maquette n'est pas restylée. */
async function tagBrandRow(page) {
  await page.evaluate(() => {
    document.querySelector('.collapse-btn')?.parentElement?.setAttribute('data-mock-id', 'shell-brand-row')
  })
}

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
  await tagBrandRow(page)
  await page.mouse.move(1, 1)
  await settleFonts(page)
  await logCollapseButton(page, 'collapsed mock')
  const mock = await shotSection(page, '[data-mock-id="shell-sidebar"]', {
    label: 'sidebar-collapsed-mock',
  })
  const mockRow = await shotSection(page, '[data-mock-id="shell-brand-row"]', {
    label: 'shell-brand-row-collapsed-mock',
  })

  await page.goto(`/folders/${FOLDER_REF}`)
  await page.waitForSelector('.shell-sidebar.is-collapsed')
  await page.waitForSelector('[title="Référence"]')
  await page.mouse.move(1, 1)
  await settleFonts(page)
  await logCollapseButton(page, 'collapsed app')
  const app = await shotSection(page, '[data-mock-id="shell-sidebar"]', {
    label: 'sidebar-collapsed-app',
  })
  const appRow = await shotSection(page, '[data-mock-id="shell-brand-row"]', {
    label: 'shell-brand-row-collapsed-app',
  })

  const compare = compareSectionShots()
  finishPixelResults('sidebar-collapsed', [
    compare('sidebar-collapsed', mock.shot, app.shot, testInfo),
    compare('shell-brand-row', mockRow.shot, appRow.shot, testInfo),
  ])
})

test('desktop shell-brand-row agrandie — pixel ≤ 1 %', async ({ page }, testInfo) => {
  await injectOidcSession(page, VISUAL_NOW, { expandedSpaces: [SPACE_IDENTITE.id] })
  await mockBaseApis(page, { me: ME_TAREK, notifications: NOTIFICATIONS_SEED, spaces: [SPACE_IDENTITE] })
  await page.route('**/api/v1/public/branding', (route) =>
    json(route, { accentColor: '#3730E0', hidePoweredBy: false, logoUrl: null, instanceName: '' }),
  )
  await page.setViewportSize({ width: 1440, height: 900 })

  await page.goto(`${MOCK_ORIGIN}/FolderReference.dc.html`)
  await tagBrandRow(page)
  await page.mouse.move(1, 1)
  await settleFonts(page)
  await logCollapseButton(page, 'expanded mock')
  const mockRow = await shotSection(page, '[data-mock-id="shell-brand-row"]', {
    label: 'shell-brand-row-expanded-mock',
  })

  await page.goto('/')
  await page.waitForSelector('.shell-collapse')
  await page.mouse.move(1, 1)
  await settleFonts(page)
  await logCollapseButton(page, 'expanded app')
  const appRow = await shotSection(page, '[data-mock-id="shell-brand-row"]', {
    label: 'shell-brand-row-expanded-app',
  })

  const compare = compareSectionShots()
  finishPixelResults('shell-brand-row-expanded', [
    compare('shell-brand-row', mockRow.shot, appRow.shot, testInfo),
  ])
})
