// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * Historique mobile (/docs/:id/history @ 390×844) vs MobileHistory.dc.html.
 * Même schéma que page-visual (PAGE_MOBILE_IDS) : pixel-diff plein écran (glyphes masqués, ≤ 1 %)
 * + comparaison structurelle `data-mock-id`. La barre d'onglets du bas est celle du lot #40.
 *
 * Exceptions documentées (numérotées, reprises dans la PR) :
 *  - M1  v11 : en plus de « Voir les changements → », l'éditeur dispose de « Restaurer cette version »
 *        (la maquette ne le montre que sur la v1). Les deux liens tiennent sur une seule ligne : la hauteur
 *        de la ligne est inchangée ; seul le second lien (sans fond) s'ajoute.
 *  - M2  v1 (système) : l'app affiche l'heure (« 14 juillet 2026 à 11:00 ») car l'API fournit un
 *        horodatage complet ; la maquette n'affiche que la date. Texte de `hist-m-date-2` non comparé.
 *  - M3  Pas de résumé de changement sur mobile (la maquette n'en affiche pas) : le champ existe côté
 *        API mais n'est pas rendu à ce format.
 *  - M4  Glyphes masqués dans les diffs pixel ; le texte est couvert par le test structurel.
 */
import { test, expect } from '@playwright/test'
import {
  HISTORY_MOBILE_IDS,
  annotateMobileHistoryMockup,
  collectMetrics,
  compareMetrics,
} from './structural-compare.mjs'
import { HIST_DOC_ID, HIST_VERSIONS_MOBILE } from './history-fixtures.mjs'
import { MOCK, diffRatio, maskGlyphs, prep, settleFonts, writeStructural } from './history-helpers.mjs'

test.use({ timezoneId: 'Europe/Paris' })

const HISTORY_URL = `/docs/${HIST_DOC_ID}/history`
const MOBILE_OPTS = { versions: HIST_VERSIONS_MOBILE, total: HIST_VERSIONS_MOBILE.length }

async function openMobile(page, opts = {}) {
  await prep(page, { ...MOBILE_OPTS, ...opts })
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto(HISTORY_URL)
  await page.waitForSelector('[data-mock-id="hist-m-name-2"]')
  await page.waitForSelector('[data-mock-id="doc-mobile-tab-history"]')
  await settleFonts(page)
}

async function pinMockupRoot(page) {
  await page.evaluate(() => {
    const root = Array.from(document.querySelectorAll('div')).find((d) =>
      (d.getAttribute('style') || '').includes('390px'),
    )
    if (root) {
      root.style.position = 'fixed'
      root.style.left = '0'
      root.style.top = '0'
    }
  })
}

test.describe('Historique mobile — parité visuelle', () => {
  test('mobile Historique vs MobileHistory.dc.html @ 390×844', async ({ page }, testInfo) => {
    await prep(page, MOBILE_OPTS)
    await page.setViewportSize({ width: 390, height: 844 })

    await page.goto(`${MOCK}/MobileHistory.dc.html`)
    await pinMockupRoot(page)
    await settleFonts(page)
    await maskGlyphs(page)
    await page.waitForTimeout(100)
    const clip = { x: 0, y: 0, width: 390, height: 844 }
    const mockShot = await page.screenshot({ fullPage: false, clip })

    await page.goto(HISTORY_URL)
    await page.waitForSelector('[data-mock-id="hist-m-name-2"]')
    await page.waitForSelector('[data-mock-id="doc-mobile-tab-history"]')
    await page.evaluate(() => {
      document.body.style.margin = '0'
    })
    await settleFonts(page)
    await maskGlyphs(page)
    await page.waitForTimeout(100)
    const appShot = await page.screenshot({ fullPage: false, clip })

    await testInfo.attach('maquette-history-mobile', { body: mockShot, contentType: 'image/png' })
    await testInfo.attach('app-history-mobile', { body: appShot, contentType: 'image/png' })
    const ratio = diffRatio(mockShot, appShot, 'history-mobile')
    expect(ratio, `mobile history diff ${ratio}`).toBeLessThanOrEqual(0.01)
  })
})

test.describe('Historique mobile — structure', () => {
  const exceptions = {
    'hist-m-date-2': { skip: ['text'], reason: 'M2 : l’app affiche aussi l’heure de la v1' },
  }

  test('mobile Historique structural match', async ({ page }, testInfo) => {
    await prep(page, MOBILE_OPTS)
    await page.setViewportSize({ width: 390, height: 844 })

    await page.goto(`${MOCK}/MobileHistory.dc.html`)
    await settleFonts(page)
    await annotateMobileHistoryMockup(page)
    const mockMap = await collectMetrics(page, HISTORY_MOBILE_IDS)

    await page.goto(HISTORY_URL)
    await page.waitForSelector('[data-mock-id="hist-m-name-2"]')
    await page.waitForSelector('[data-mock-id="doc-mobile-tab-history"]')
    await settleFonts(page)
    const appMap = await collectMetrics(page, HISTORY_MOBILE_IDS)

    const results = compareMetrics(mockMap, appMap, HISTORY_MOBILE_IDS, { pageExceptions: exceptions })
    writeStructural('structural-history-mobile.json', results)
    await testInfo.attach('structural-history-mobile.json', {
      body: Buffer.from(JSON.stringify(results, null, 2)),
      contentType: 'application/json',
    })
    const failures = results.filter((r) => r.diffs.length)
    expect(failures, JSON.stringify(failures, null, 2)).toEqual([])
  })
})

test.describe('Historique mobile — comportement', () => {
  test('« Voir les changements → » ouvre le diff ; « Restaurer cette version » ouvre la modale', async ({ page }) => {
    await openMobile(page)
    await expect(page.getByTestId('history-row-12').getByRole('link')).toHaveCount(0)
    await page.getByRole('link', { name: 'Voir les changements →' }).click()
    await expect(page).toHaveURL(new RegExp(`/docs/${HIST_DOC_ID}/history/compare\\?from=1&to=11$`))
  })

  test('Restaurer cette version : modale puis appel de restauration', async ({ page }) => {
    const restored = { calls: [] }
    await openMobile(page, { restored })
    await page.getByTestId('history-row-1').getByRole('button', { name: 'Restaurer cette version' }).click()
    const dialog = page.getByTestId('restore-dialog')
    await expect(dialog.getByRole('heading', { name: 'Restaurer la v1 ?' })).toBeVisible()
    await dialog.getByRole('button', { name: 'Restaurer cette version' }).click()
    await expect.poll(() => restored.calls.length).toBe(1)
    expect(restored.calls[0]).toContain('/versions/1/restore')
  })

  test('lecteur sans droit d’édition : pas de liens d’action', async ({ page }) => {
    await openMobile(page, { editor: false })
    await expect(page.getByRole('link', { name: 'Voir les changements →' })).toHaveCount(0)
    await expect(page.getByRole('button', { name: 'Restaurer cette version' })).toHaveCount(0)
  })

  test('barre d’onglets du bas réutilisée, Historique actif', async ({ page }) => {
    await openMobile(page)
    await expect(page.locator('[data-mock-id="doc-mobile-tabs"]')).toBeVisible()
    await expect(page.locator('[data-mock-id="doc-mobile-tab-history"]')).toHaveText('Historique')
  })
})
