// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
/**
 * Règles : docs/visual-parity.md
 *
 * Écran Historique (/docs/:id/history) vs History.dc.html @ 1440×900.
 *
 * Pixel honnête (texte inclus, ≤ 1 % par section) ; aucun masque, aucun clip hors sections déclarées.
 * Annotation maquette : data-* uniquement (aucune modification de style / DOM).
 * Sections : sidebar, barre haute, onglets, titre, sous-titre, timeline entière et chacune des 4 lignes.
 *
 * Les écarts qui subsistent sont des écarts de conception (pas de données manquantes) et restent
 * ROUGES : voir `.tmp-design-gaps-history.md`. Aucune fonctionnalité absente de l'API n'est en jeu ici.
 *
 * Mesure avant polish : `VISUAL_BASELINE=1 npx playwright test e2e-visual/history-visual.spec.mjs`
 * (journalise tous les % / tailles, écrit test-results/history-before.json, n'échoue pas).
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

/**
 * Zones maquette sans équivalent app — NON comparées, jamais « passées ».
 * Aucune pour l'Historique : toutes les données de la maquette existent dans l'API (versions, auteurs,
 * lignes +/−, résumé) ; la pagination « Afficher les versions précédentes » de l'app est hors de toute section.
 * @type {import('./visual-parity-kit.mjs').NotImplementedZone[]}
 */
export const NOT_IMPLEMENTED = []

/** Maquette avec sidebar comme l'app : aucune exception de taille a priori (mesure : tout écart est un échec). */
const SIZE_EXCEPTIONS = {}

/** [section pixel] nom, sélecteur maquette, sélecteur app. */
const dm = (id) => `[data-mock-id="${id}"]`
const PIXEL_SECTIONS = [
  { name: 'sidebar', mock: dm('hist-sidebar'), app: dm('shell-sidebar') },
  { name: 'topbar', mock: dm('hist-topbar'), app: dm('hist-topbar') },
  { name: 'tabs', mock: dm('hist-tabs'), app: dm('hist-tabs') },
  { name: 'title', mock: dm('hist-title'), app: dm('hist-title') },
  { name: 'subtitle', mock: dm('hist-subtitle'), app: dm('hist-subtitle') },
  { name: 'timeline', mock: dm('hist-timeline'), app: '[data-testid="history-timeline"]' },
  ...[12, 11, 10, 9].map((v, i) => ({
    name: `row-${i}`,
    mock: dm(`hist-row-${i}`),
    app: `[data-testid="history-row-${v}"]`,
  })),
]

/** Annotation supplémentaire de History.dc.html — data-* uniquement. */
async function annotateHistoryExtras(page) {
  await page.evaluate(() => {
    const root = document.querySelector('body div[style*="1440px"]') || document.body.firstElementChild
    root?.children[0]?.setAttribute('data-mock-id', 'hist-sidebar')
    const timeline = root?.children[1]?.children[2]?.children[0]?.children[2]
    timeline?.setAttribute('data-mock-id', 'hist-timeline')
    ;[...(timeline?.children ?? [])].slice(1).forEach((item, i) => item.setAttribute('data-mock-id', `hist-row-${i}`))
  })
}

async function openHistory(page, opts) {
  await prep(page, opts)
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto(HISTORY_URL)
  await page.waitForSelector('[data-mock-id="hist-r3-restore"], [data-mock-id="hist-r3-author"]')
  await page.waitForSelector('[data-mock-id="hist-subtitle"]')
  await settleFonts(page)
}

test.describe('Historique — parité visuelle', () => {
  test('desktop Historique — sections pixel ≤ 1 % (texte inclus)', async ({ page }, testInfo) => {
    await prep(page)
    await page.setViewportSize({ width: 1440, height: 900 })

    await page.goto(`${MOCK_ORIGIN}/History.dc.html`)
    await annotateHistoryMockup(page)
    await annotateHistoryExtras(page)
    await settleFonts(page)
    await reportNotImplemented(page, testInfo, NOT_IMPLEMENTED, 'history')

    const mockSec = {}
    for (const s of PIXEL_SECTIONS) {
      mockSec[s.name] = await shotSection(page, s.mock, { label: `history-sec-${s.name}-mock` })
    }

    await page.goto(HISTORY_URL)
    await page.waitForSelector('[data-mock-id="hist-r3-author"]')
    await settleFonts(page)
    await assertNotImplementedGuards(page, NOT_IMPLEMENTED, 'history')
    await page.mouse.move(1, 1)

    const compare = compareSectionShots(SIZE_EXCEPTIONS)
    const results = []
    for (const s of PIXEL_SECTIONS) {
      const app = await shotSection(page, s.app, { label: `history-sec-${s.name}-app` })
      results.push(compare(`history-${s.name}`, mockSec[s.name].shot, app.shot, testInfo))
    }
    finishPixelResults('history', results)
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
    // box.y : cascade autorisée (docs/visual-parity.md § Exceptions structurales). collectMetrics épingle le
    // line-height des <button> annotés (« Restaurer ») à 1 mais pas le « Comparer » de la v10 (H2, sans équivalent
    // maquette) : la mesure de la ligne v10 est 3,5 px plus haute dans l'app, et la v9 suit. Les hauteurs réelles
    // sont identiques (sections pixel row-2 / row-3 / timeline : mêmes tailles PNG, ≤ 1 %).
    ...Object.fromEntries(
      ['dot', 'version', 'date', 'summary', 'avatar', 'author', 'added', 'removed', 'restore'].map((f) => [
        `hist-r3-${f}`,
        { skip: ['box.y'], reason: 'cascade box.y depuis la ligne v10 (cf. commentaire) ; x / w / h comparés' },
      ]),
    ),
  }

  test('desktop Historique structural match', async ({ page }, testInfo) => {
    await prep(page)
    await page.setViewportSize({ width: 1440, height: 900 })

    await page.goto(`${MOCK_ORIGIN}/History.dc.html`)
    await settleFonts(page)
    await annotateHistoryMockup(page)
    const mockMap = await collectMetrics(page, HISTORY_DESKTOP_IDS)

    await page.goto(HISTORY_URL)
    await page.waitForSelector('[data-mock-id="hist-r3-author"]')
    await settleFonts(page)
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
