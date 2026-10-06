// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
/**
 * Modale « Restaurer la v11 ? » (ouverte depuis l'Historique) vs RestoreVersion.dc.html @ 1440×900.
 * Pixel-diff limité à la carte de la modale (≤ 1 %, glyphes masqués) + comparaison structurelle.
 *
 * Exceptions documentées (numérotées, reprises dans la PR) :
 *  - R1  Fond : la maquette dessine un faux squelette de page sous le voile ; l'app a la vraie page.
 *        Seule la carte est comparée (clip = boîte de la carte de la maquette). Les quatre coins arrondis
 *        laissent voir ce fond : l'écart correspondant est borné par le budget de 1 %.
 *  - R2  Document en `brouillon` dans la fixture : en `valide`, l'app ajoute un encart « repasse en
 *        en_revue » (information réelle du backend) absent de la maquette.
 *  - R3  « Annuler » / « Restaurer cette version » : <button> au lieu de <a> ; line-height épinglé à 1
 *        des deux côtés. Pas de lien « Annuler → History » : la modale se ferme sur place.
 *  - R4  Résumé de la version courante mis en minuscule initiale dans l'encart d'avertissement
 *        (« clarification du circuit… »), comme la maquette, sauf sigle (« IAM… » reste tel quel).
 *  - R5  Glyphes masqués dans les diffs pixel ; le texte est couvert par le test structurel.
 */
import { test, expect } from '@playwright/test'
import { RESTORE_IDS, annotateRestoreMockup, collectMetrics, compareMetrics } from './structural-compare.mjs'
import { HIST_DOC_ID, HIST_VERSIONS_RESTORE } from './history-fixtures.mjs'
import { MOCK, diffRatio, maskGlyphs, prep, settleFonts, writeStructural } from './history-helpers.mjs'

test.use({ timezoneId: 'Europe/Paris' })

// R2 + R4 : document en brouillon, résumé de la v12 identique à celui de la maquette.
const RESTORE_OPTS = { status: 'brouillon', versions: HIST_VERSIONS_RESTORE }
const HISTORY_URL = `/docs/${HIST_DOC_ID}/history`

async function openRestoreModal(page) {
  await prep(page, RESTORE_OPTS) // R2
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto(HISTORY_URL)
  await page.getByTestId('history-row-11').getByRole('button', { name: 'Restaurer' }).click()
  await page.waitForSelector('[data-mock-id="restore-modal"]')
  await settleFonts(page)
  await page.evaluate(() => document.activeElement?.blur())
}

/** Boîte de la carte dans la maquette (référence du clip pour les deux captures). */
async function mockCardBox(page) {
  await page.goto(`${MOCK}/RestoreVersion.dc.html`)
  await settleFonts(page)
  return page.evaluate(() => {
    const card = Array.from(document.querySelectorAll('div')).find((d) =>
      (d.getAttribute('style') || '').includes('width: 480px'),
    )
    const r = card.getBoundingClientRect()
    return { x: Math.floor(r.x), y: Math.floor(r.y), width: Math.ceil(r.width), height: Math.ceil(r.height) }
  })
}

test.describe('Restauration — parité visuelle', () => {
  test('modale Restaurer vs RestoreVersion.dc.html @ 1440×900 (carte)', async ({ page }, testInfo) => {
    await page.setViewportSize({ width: 1440, height: 900 })
    await prep(page, RESTORE_OPTS)
    const clip = await mockCardBox(page)
    await maskGlyphs(page)
    await page.waitForTimeout(100)
    const mockShot = await page.screenshot({ fullPage: false, clip })

    await openRestoreModal(page)
    await maskGlyphs(page)
    await page.waitForTimeout(150)
    const appShot = await page.screenshot({ fullPage: false, clip })

    await testInfo.attach('maquette-restore', { body: mockShot, contentType: 'image/png' })
    await testInfo.attach('app-restore', { body: appShot, contentType: 'image/png' })
    const ratio = diffRatio(mockShot, appShot, 'restore-modal')
    expect(ratio, `restore modal diff ${ratio}`).toBeLessThanOrEqual(0.01)
  })
})

test.describe('Restauration — structure', () => {
  const exceptions = {
    'restore-modal': { skip: ['text'], reason: 'Conteneur — textes comparés sur les enfants annotés' },
  }

  test('modale Restaurer structural match', async ({ page }, testInfo) => {
    await prep(page, RESTORE_OPTS)
    await page.setViewportSize({ width: 1440, height: 900 })

    await page.goto(`${MOCK}/RestoreVersion.dc.html`)
    await settleFonts(page)
    await annotateRestoreMockup(page)
    const mockMap = await collectMetrics(page, RESTORE_IDS)

    await openRestoreModal(page)
    const appMap = await collectMetrics(page, RESTORE_IDS)

    const results = compareMetrics(mockMap, appMap, RESTORE_IDS, { pageExceptions: exceptions })
    writeStructural('structural-restore-modal.json', results)
    await testInfo.attach('structural-restore-modal.json', {
      body: Buffer.from(JSON.stringify(results, null, 2)),
      contentType: 'application/json',
    })
    const failures = results.filter((r) => r.diffs.length)
    expect(failures, JSON.stringify(failures, null, 2)).toEqual([])
  })
})

test.describe('Restauration — textes de la modale', () => {
  test('libellés exacts avec les valeurs réelles', async ({ page }) => {
    await openRestoreModal(page)
    const t = (id) => page.locator(`[data-mock-id="${id}"]`)
    await expect(t('restore-title')).toHaveText('Restaurer la v11 ?')
    await expect(t('restore-text-1')).toHaveText(
      'Le contenu de la v11 (3 septembre 2026 · 09:41, Claire Dubois) deviendra la nouvelle version courante du document, publiée sous le numéro v13.',
    )
    await expect(t('restore-text-2')).toHaveText(
      "La version actuelle (v12) n'est pas perdue : elle reste consultable et comparable dans l'historique.",
    )
    await expect(t('restore-warning')).toHaveText(
      "Les modifications propres à la v12 (clarification du circuit d'approbation N2) ne seront plus reflétées dans le contenu affiché.",
    )
    await expect(t('restore-cancel')).toHaveText('Annuler')
    await expect(t('restore-confirm')).toHaveText('Restaurer cette version')
  })

  test('Annuler ferme la modale sans appeler le backend', async ({ page }) => {
    const restored = { calls: [] }
    await prep(page, { ...RESTORE_OPTS, restored })
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto(HISTORY_URL)
    await page.getByTestId('history-row-10').getByRole('button', { name: 'Restaurer' }).click()
    await page.getByRole('button', { name: 'Annuler' }).click()
    await expect(page.getByTestId('restore-dialog')).toHaveCount(0)
    expect(restored.calls).toEqual([])
  })
})
