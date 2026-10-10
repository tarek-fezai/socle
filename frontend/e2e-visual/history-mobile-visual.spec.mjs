// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
/**
 * Règles : docs/visual-parity.md
 *
 * Historique mobile (/docs/:id/history @ 390×844) vs MobileHistory.dc.html.
 *
 * Pixel honnête (texte inclus, ≤ 1 % par section, surface de page 390×844) ; aucun masque,
 * aucun clip hors sections déclarées. Annotation maquette : data-* uniquement.
 * Sections : barre haute, 3 lignes (v12 actuelle, v11, v1 système), barre d'onglets du bas (lot #40).
 *
 * Actions (maquette, décision tf) : une seule action par ligne — « Voir les changements → » s'il existe
 * une version précédente, sinon « Restaurer cette version » ; jamais d'action sur la version courante.
 *
 * Écarts connus :
 *  - M2  v1 (système) : l'app affiche l'heure (« 14 juillet 2026 à 11:00 ») car l'API fournit un
 *        horodatage complet ; la maquette n'affiche que la date.
 *  - M3  Pas de résumé de changement sur mobile (la maquette n'en affiche pas).
 *
 * Mesure avant polish : `VISUAL_BASELINE=1 npx playwright test e2e-visual/history-mobile-visual.spec.mjs`
 * (journalise tous les % / tailles, écrit test-results/history-mobile-before.json, n'échoue pas).
 */
import { test, expect } from '@playwright/test'
import {
  HISTORY_MOBILE_IDS,
  annotateMobileHistoryMockup,
  collectMetrics,
  compareMetrics,
} from './structural-compare.mjs'
import { HIST_DOC_ID, HIST_VERSIONS_MOBILE } from './history-fixtures.mjs'
import { prep, writeStructural } from './history-helpers.mjs'
import {
  MOCK_ORIGIN,
  assertNotImplementedGuards,
  compareSectionShots,
  finishPixelResults,
  reportNotImplemented,
  settleFonts,
  shotSection,
} from './visual-parity-kit.mjs'

test.use({ timezoneId: 'Europe/Paris' })

const HISTORY_URL = `/docs/${HIST_DOC_ID}/history`
const MOBILE_OPTS = { versions: HIST_VERSIONS_MOBILE, total: HIST_VERSIONS_MOBILE.length }
const MOBILE_PAGE_AREA = 390 * 844

/**
 * Zones maquette sans équivalent app — NON comparées, jamais « passées ».
 * Aucune : toutes les données de la maquette mobile existent dans l'API.
 * @type {import('./visual-parity-kit.mjs').NotImplementedZone[]}
 */
export const NOT_IMPLEMENTED = []

/** Mobile 390 px : même largeur des deux côtés, aucune exception de taille a priori. */
const SIZE_EXCEPTIONS = {}

const dm = (id) => `[data-mock-id="${id}"]`
const PIXEL_SECTIONS = [
  { name: 'top', mock: dm('hist-m-top'), app: dm('hist-mobile-top') },
  ...[12, 11, 1].map((v, i) => ({
    name: `row-${i}`,
    mock: dm(`hist-m-row-${i}`),
    app: `[data-testid="history-row-${v}"]`,
  })),
  { name: 'tabs', mock: dm('hist-m-tabs'), app: dm('doc-mobile-tabs') },
]

/** Annotation supplémentaire de MobileHistory.dc.html — data-* uniquement. */
async function annotateMobileExtras(page) {
  await page.evaluate(() => {
    const root = Array.from(document.querySelectorAll('div')).find((d) =>
      (d.getAttribute('style') || '').includes('390px'),
    )
    if (!root) return
    root.children[0]?.setAttribute('data-mock-id', 'hist-m-top')
    ;[...(root.children[1]?.children ?? [])].forEach((row, i) => row.setAttribute('data-mock-id', `hist-m-row-${i}`))
    root.children[root.children.length - 1]?.setAttribute('data-mock-id', 'hist-m-tabs')
  })
}

async function openMobile(page, opts = {}) {
  await prep(page, { ...MOBILE_OPTS, ...opts })
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto(HISTORY_URL)
  await page.waitForSelector('[data-mock-id="hist-m-name-2"]')
  await page.waitForSelector('[data-mock-id="doc-mobile-tab-history"]')
  await settleFonts(page)
}

test.describe('Historique mobile — parité visuelle', () => {
  test('mobile Historique — sections pixel ≤ 1 % (texte inclus)', async ({ page }, testInfo) => {
    await prep(page, MOBILE_OPTS)
    await page.setViewportSize({ width: 390, height: 844 })

    await page.goto(`${MOCK_ORIGIN}/MobileHistory.dc.html`)
    await annotateMobileHistoryMockup(page)
    await annotateMobileExtras(page)
    await settleFonts(page)
    await reportNotImplemented(page, testInfo, NOT_IMPLEMENTED, 'history-mobile')

    const mockSec = {}
    for (const s of PIXEL_SECTIONS) {
      mockSec[s.name] = await shotSection(page, s.mock, {
        label: `history-mobile-sec-${s.name}-mock`,
        pageArea: MOBILE_PAGE_AREA,
      })
    }

    await page.goto(HISTORY_URL)
    await page.waitForSelector('[data-mock-id="hist-m-name-2"]')
    await page.waitForSelector('[data-mock-id="doc-mobile-tab-history"]')
    await settleFonts(page)
    await assertNotImplementedGuards(page, NOT_IMPLEMENTED, 'history-mobile')
    await page.mouse.move(1, 1)

    const compare = compareSectionShots(SIZE_EXCEPTIONS)
    const results = []
    for (const s of PIXEL_SECTIONS) {
      const app = await shotSection(page, s.app, {
        label: `history-mobile-sec-${s.name}-app`,
        pageArea: MOBILE_PAGE_AREA,
      })
      results.push(compare(`history-mobile-${s.name}`, mockSec[s.name].shot, app.shot, testInfo))
    }
    finishPixelResults('history-mobile', results)
  })
})

test.describe('Historique mobile — structure', () => {
  const exceptions = {
    'hist-m-date-2': { skip: ['text'], reason: 'M2 : l’app affiche aussi l’heure de la v1' },
  }

  test('mobile Historique structural match', async ({ page }, testInfo) => {
    await prep(page, MOBILE_OPTS)
    await page.setViewportSize({ width: 390, height: 844 })

    await page.goto(`${MOCK_ORIGIN}/MobileHistory.dc.html`)
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
    await expect(page.getByTestId('history-row-12').getByRole('button')).toHaveCount(0)
    await expect(page.getByTestId('history-row-11').getByRole('button', { name: 'Restaurer cette version' })).toHaveCount(0)
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
