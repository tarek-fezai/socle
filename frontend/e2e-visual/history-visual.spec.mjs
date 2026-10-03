// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * Écran Historique (/docs/:id/history) vs History.dc.html @ 1440×900 — colonne principale.
 * Même méthode que edit-visual / page-visual : pixel-diff (glyphes masqués, ≤ 1 %) + comparaison
 * structurelle `data-mock-id` (textes, typographie, couleurs, boîtes ±3 px).
 * Les écrans voisins ont leur propre spec : diff-visual (Diff.dc.html), restore-visual
 * (RestoreVersion.dc.html) et history-mobile-visual (MobileHistory.dc.html).
 *
 * Exceptions documentées (numérotées, reprises dans la PR) :
 *  - H1  Actions de ligne : « Comparer » et « Restaurer » sont groupées à droite. La maquette (v11) les
 *        répartit en `space-between` : « Comparer » flotte au centre de la ligne, ce qui dépend du
 *        nombre de boutons. Boîte de « Comparer » non comparée (x) ; texte, police et couleur le sont.
 *  - H2  « Comparer » est proposé sur toute version non courante qui a une précédente (v10, v9) ;
 *        la maquette ne le montre que sur la v11. Sans effet sur le pixel-diff (lien sans fond).
 *  - H3  « Afficher les versions précédentes » (pagination, 4 versions chargées sur 12) est masqué
 *        dans le pixel-diff : la maquette ne représente pas la pagination.
 *  - H4  Shell (sidebar) : comparaison limitée à la colonne principale (x ≥ 268), comme page-visual.
 *  - H5  Glyphes masqués dans les diffs pixel ; le texte est couvert par le test structurel.
 *  - H6  Aucun écart de libellé : date en `fr-FR` / Europe/Paris, « Système (migration) » + ⚙ pour
 *        un auteur null, badge « Actuelle » sur la version courante.
 */
import { test, expect } from '@playwright/test'
import {
  HISTORY_DESKTOP_IDS,
  annotateHistoryMockup,
  assertFontsLoaded,
  collectMetrics,
  compareMetrics,
} from './structural-compare.mjs'
import { HIST_DOC_ID, HIST_VERSIONS_DESKTOP } from './history-fixtures.mjs'
import { MOCK, diffRatio, maskGlyphs, prep, settleFonts, writeStructural } from './history-helpers.mjs'

test.use({ timezoneId: 'Europe/Paris' })

const HISTORY_URL = `/docs/${HIST_DOC_ID}/history`
const CLIP = { x: 268, y: 0, width: 1172, height: 900 }

async function openHistory(page, opts) {
  await prep(page, opts)
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto(HISTORY_URL)
  await page.waitForSelector('[data-mock-id="hist-r3-restore"], [data-mock-id="hist-r3-author"]')
  await page.waitForSelector('[data-mock-id="hist-subtitle"]')
  await settleFonts(page)
}

test.describe('Historique — parité visuelle', () => {
  test('desktop Historique vs History.dc.html @ 1440×900 (colonne principale)', async ({ page }, testInfo) => {
    await openHistory(page)
    await page.evaluate(() => {
      document.body.style.margin = '0'
      document.documentElement.style.overflow = 'hidden'
    })
    // H3 : pagination non représentée dans la maquette.
    await page.addStyleTag({ content: '.hist-more { display: none !important; }' })
    await maskGlyphs(page)
    await page.waitForTimeout(150)
    const appShot = await page.screenshot({ fullPage: false, clip: CLIP })

    await page.goto(`${MOCK}/History.dc.html`)
    await settleFonts(page)
    await maskGlyphs(page)
    await page.waitForTimeout(150)
    const mockShot = await page.screenshot({ fullPage: false, clip: CLIP })

    await testInfo.attach('maquette-history', { body: mockShot, contentType: 'image/png' })
    await testInfo.attach('app-history', { body: appShot, contentType: 'image/png' })
    const ratio = diffRatio(mockShot, appShot, 'history-desktop')
    expect(ratio, `history desktop diff ${ratio}`).toBeLessThanOrEqual(0.01)
  })
})

test.describe('Historique — structure', () => {
  const exceptions = {
    'hist-topbar': { skip: ['text'], reason: 'Conteneur — textes comparés sur les enfants annotés' },
    'hist-tabs': { skip: ['text'], reason: 'Libellés dans les enfants ; Modifier / Accès conditionnels aux droits' },
    'hist-r1-compare': {
      skip: ['box'],
      reason: 'H1 : « Comparer » groupé à droite (maquette : centré par space-between)',
    },
  }

  test('desktop Historique structural match', async ({ page }, testInfo) => {
    await prep(page)
    await page.setViewportSize({ width: 1440, height: 900 })

    await page.goto(`${MOCK}/History.dc.html`)
    await settleFonts(page)
    await annotateHistoryMockup(page)
    const mockMap = await collectMetrics(page, HISTORY_DESKTOP_IDS)

    await page.goto(HISTORY_URL)
    await page.waitForSelector('[data-mock-id="hist-r3-author"]')
    await settleFonts(page)
    // Les « Comparer » de v10 / v9 (H2) n'ont pas d'équivalent annoté : on les épingle comme les autres
    // actions (line-height 1) pour que la hauteur des lignes reste comparable.
    await page.addStyleTag({ content: '.hist-link { line-height: 1 !important; }' })
    const appMap = await collectMetrics(page, HISTORY_DESKTOP_IDS)

    const fonts = await assertFontsLoaded(page)
    expect(fonts.find((f) => f.family === 'Instrument Serif')?.loaded).toBe(true)
    expect(fonts.find((f) => f.family === 'IBM Plex Sans')?.loaded).toBe(true)

    const results = compareMetrics(mockMap, appMap, HISTORY_DESKTOP_IDS, { pageExceptions: exceptions })
    writeStructural('structural-history-desktop.json', results)
    await testInfo.attach('structural-history-desktop.json', {
      body: Buffer.from(JSON.stringify(results, null, 2)),
      contentType: 'application/json',
    })
    const failures = results.filter((r) => r.diffs.length)
    expect(failures, JSON.stringify(failures, null, 2)).toEqual([])
  })
})

test.describe('Historique — comportement', () => {
  test('données réelles : date fr-FR, auteur Système (migration), badge Actuelle', async ({ page }) => {
    await openHistory(page)
    await expect(page.getByTestId('history-count')).toHaveText('12 versions publiées depuis la création du document')
    await expect(page.getByTestId('history-current-badge')).toHaveCount(1)
    await expect(page.getByTestId('history-row-12').getByTestId('history-current-badge')).toBeVisible()
    await expect(page.locator('[data-mock-id="hist-r1-date"]')).toHaveText('3 septembre 2026 · 09:41')
    await expect(page.locator('[data-mock-id="hist-r3-author"]')).toHaveText('Système (migration)')
    await expect(page.getByTestId('history-row-9').getByTestId('hist-avatar-system')).toHaveText('⚙')
  })

  test('éditeur : Comparer / Restaurer sur les versions non courantes, jamais sur la courante', async ({ page }) => {
    await openHistory(page)
    const current = page.getByTestId('history-row-12')
    await expect(current.getByRole('button', { name: 'Restaurer' })).toHaveCount(0)
    await expect(current.getByRole('link', { name: 'Comparer' })).toHaveCount(0)
    for (const v of HIST_VERSIONS_DESKTOP.slice(1)) {
      await expect(page.getByTestId(`history-row-${v.versionNo}`).getByRole('button', { name: 'Restaurer' })).toBeVisible()
    }
  })

  test('lecteur sans droit d’édition : aucune action Comparer / Restaurer', async ({ page }) => {
    await openHistory(page, { editor: false })
    await expect(page.getByTestId('history-row-11')).toBeVisible()
    await expect(page.getByRole('button', { name: 'Restaurer' })).toHaveCount(0)
    await expect(page.getByRole('link', { name: 'Comparer' })).toHaveCount(0)
  })

  test('Comparer ouvre la vue de comparaison contre la version précédente', async ({ page }) => {
    await openHistory(page)
    await page.getByTestId('history-row-11').getByRole('link', { name: 'Comparer' }).click()
    await expect(page).toHaveURL(new RegExp(`/docs/${HIST_DOC_ID}/history/compare\\?from=10&to=11$`))
  })

  test('Restaurer ouvre la modale avec les valeurs réelles, puis restaure', async ({ page }) => {
    const restored = { calls: [] }
    await prep(page, { restored })
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto(HISTORY_URL)
    await page.getByTestId('history-row-11').getByRole('button', { name: 'Restaurer' }).click()
    const dialog = page.getByTestId('restore-dialog')
    await expect(dialog.getByRole('heading', { name: 'Restaurer la v11 ?' })).toBeVisible()
    await expect(dialog).toContainText('(3 septembre 2026 · 09:41, Claire Dubois)')
    await expect(dialog).toContainText('publiée sous le numéro v13')
    await expect(dialog).toContainText('La version actuelle (v12) n\'est pas perdue')
    await dialog.getByRole('button', { name: 'Restaurer cette version' }).click()
    await expect.poll(() => restored.calls.length).toBe(1)
    expect(restored.calls[0]).toContain('/versions/11/restore?expectedVersionNo=12')
  })
})
