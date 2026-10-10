// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
/**
 * Règles : docs/visual-parity.md
 *
 * Modale « Restaurer la v11 ? » (ouverte depuis l'Historique) vs RestoreVersion.dc.html @ 1440×900.
 *
 * Pixel honnête (texte inclus, ≤ 1 % par section) ; aucun masque, aucun clip hors sections déclarées.
 * Annotation maquette : data-* uniquement (aucune modification de style / DOM).
 * Sections : la carte entière (`restore-modal`), l'en-tête (icône + titre), les deux paragraphes,
 * l'encart d'avertissement, « Annuler » et « Restaurer cette version ».
 *
 * Limites documentées :
 *  - R1  Fond : la maquette dessine un faux squelette de page sous le voile ; l'app a la vraie page.
 *        Seule la carte est comparée ; les 4 coins arrondis laissent voir ce fond (≈ 0,14 % de la carte,
 *        compté dans le budget de 1 %). L'ombre portée est hors de la boîte de la carte.
 *  - R2  Document en `brouillon` dans la fixture : en `valide`, l'app ajoute un encart « repasse en
 *        en_revue » (information réelle du backend) absent de la maquette.
 *  - R3  « Annuler » / « Restaurer cette version » : <button> au lieu de <a> ; pas de lien « Annuler → History »,
 *        la modale se ferme sur place.
 *  - R4  Résumé de la version courante : fixture alignée sur la maquette (HIST_VERSIONS_RESTORE).
 *
 * Mesure avant polish : `VISUAL_BASELINE=1 npx playwright test e2e-visual/restore-visual.spec.mjs`
 * (journalise tous les % / tailles, écrit test-results/restore-before.json, n'échoue pas).
 */
import { test, expect } from '@playwright/test'
import { RESTORE_IDS, annotateRestoreMockup, collectMetrics, compareMetrics } from './structural-compare.mjs'
import { HIST_DOC_ID, HIST_VERSIONS_RESTORE } from './history-fixtures.mjs'
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

/**
 * Anti-crénelage : le texte de la carte (calque composité par `transform`) est rendu en LCD d'un côté et en
 * niveaux de gris de l'autre. Même rendu des deux côtés (--disable-lcd-text), uniquement pour ce fichier
 * (même précédent que new-document-visual) ; le texte reste comparé pixel à pixel.
 */
test.use({ launchOptions: { args: ['--disable-lcd-text'] } })

// R2 + R4 : document en brouillon, résumé de la v12 identique à celui de la maquette.
const RESTORE_OPTS = { status: 'brouillon', versions: HIST_VERSIONS_RESTORE }
const HISTORY_URL = `/docs/${HIST_DOC_ID}/history`

/**
 * Zones maquette sans équivalent app — NON comparées, jamais « passées ».
 * Aucune : tout le contenu de la modale est dérivé de données réelles (versions, auteur, dates).
 * @type {import('./visual-parity-kit.mjs').NotImplementedZone[]}
 */
export const NOT_IMPLEMENTED = []

/** Carte 480 px des deux côtés : aucune exception de taille a priori (mesure : tout écart est un échec). */
const SIZE_EXCEPTIONS = {}

const dm = (id) => `[data-mock-id="${id}"]`
const PIXEL_SECTIONS = [
  { name: 'modal', sel: dm('restore-modal') },
  { name: 'head', sel: dm('restore-head') },
  { name: 'text-1', sel: dm('restore-text-1') },
  { name: 'text-2', sel: dm('restore-text-2') },
  { name: 'warning', sel: dm('restore-warning') },
  { name: 'cancel', sel: dm('restore-cancel') },
  { name: 'confirm', sel: dm('restore-confirm') },
]

/** Annotation supplémentaire de RestoreVersion.dc.html (en-tête) — data-* uniquement. */
async function annotateRestoreHead(page) {
  await page.evaluate(() => {
    const card = document.querySelector('[data-mock-id="restore-modal"]')
    card?.children[0]?.setAttribute('data-mock-id', 'restore-head')
  })
}

async function openRestoreModal(page) {
  await prep(page, RESTORE_OPTS) // R2
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto(HISTORY_URL)
  await page.getByTestId('history-row-11').getByRole('button', { name: 'Restaurer' }).click()
  await page.waitForSelector('[data-mock-id="restore-modal"]')
  await settleFonts(page)
}

test.describe('Restauration — parité visuelle', () => {
  test('modale Restaurer — sections pixel ≤ 1 % (texte inclus)', async ({ page }, testInfo) => {
    await page.setViewportSize({ width: 1440, height: 900 })
    await prep(page, RESTORE_OPTS)

    await page.goto(`${MOCK_ORIGIN}/RestoreVersion.dc.html`)
    await annotateRestoreMockup(page)
    await annotateRestoreHead(page)
    await settleFonts(page)
    await reportNotImplemented(page, testInfo, NOT_IMPLEMENTED, 'restore')

    const mockSec = {}
    for (const s of PIXEL_SECTIONS) {
      mockSec[s.name] = await shotSection(page, s.sel, { label: `restore-sec-${s.name}-mock` })
    }

    await openRestoreModal(page)
    await assertNotImplementedGuards(page, NOT_IMPLEMENTED, 'restore')
    await page.mouse.move(1, 1)

    const compare = compareSectionShots(SIZE_EXCEPTIONS)
    const results = []
    for (const s of PIXEL_SECTIONS) {
      const sel = s.name === 'head' ? '[data-testid="restore-dialog"] .hist-modal-head' : s.sel
      const app = await shotSection(page, sel, { label: `restore-sec-${s.name}-app` })
      results.push(compare(`restore-${s.name}`, mockSec[s.name].shot, app.shot, testInfo))
    }
    finishPixelResults('restore', results)
  })
})

test.describe('Restauration — structure', () => {
  const exceptions = {
    'restore-modal': { skip: ['text'], reason: 'Conteneur — textes comparés sur les enfants annotés' },
  }

  test('modale Restaurer structural match', async ({ page }, testInfo) => {
    await prep(page, RESTORE_OPTS)
    await page.setViewportSize({ width: 1440, height: 900 })

    await page.goto(`${MOCK_ORIGIN}/RestoreVersion.dc.html`)
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
