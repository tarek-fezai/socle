// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
/**
 * Règles : docs/visual-parity.md
 *
 * Comparaison (Historique → Comparer, /docs/:id/history/compare) vs Diff.dc.html @ 1440×900.
 *
 * Pixel honnête (texte inclus, ≤ 1 % par section) ; seul exclu : `mask` Playwright nommé
 * (`topbar-actions` / `controls-toggle`, comparés par ailleurs section par section).
 * Annotation maquette / app : data-* uniquement (aucune modification de style / DOM avant capture).
 * Blocs « N lignes inchangées » : dépliés par un clic utilisateur (la maquette les affiche dépliés).
 *
 * La classe CSS des cellules « nouvelle version » des lignes modifiées était fausse dans la maquette
 * (`row-add` posé sur le `.side` lui-même) : corrigée À LA SOURCE (Diff.dc.html), pas avant capture.
 *
 * Écart de taille déclaré (SIZE_EXCEPTIONS) : la maquette n'a pas de sidebar, l'app garde le shell (268 px)
 * → docs/visual-parity.md § Décisions produit — Comparaison / Approbation.
 *
 * Mesure avant polish : `VISUAL_BASELINE=1 npx playwright test e2e-visual/diff-visual.spec.mjs`
 * (journalise tous les % / tailles, écrit test-results/diff-before.json, n'échoue pas).
 */
import { test, expect } from '@playwright/test'
import {
  DIFF_DESKTOP_IDS,
  annotateDiffMockup,
  collectMetrics,
  compareMetrics,
} from './structural-compare.mjs'
import { HIST_DOC_ID } from './history-fixtures.mjs'
import { MOCK, prep, writeStructural } from './history-helpers.mjs'
import {
  compareSectionShots,
  expectNoStructuralDiffs,
  finishPixelResults,
  settleFonts,
  shotSection,
} from './visual-parity-kit.mjs'
import {
  SHELL_DECISION_REASON,
  SHELL_DW,
  annotateDiffBody,
  diffBodySections,
  diffBodySizeExceptions,
} from './diff-parity-shared.mjs'

test.use({ timezoneId: 'Europe/Paris' })

const COMPARE_URL = `/docs/${HIST_DOC_ID}/history/compare?from=11&to=12`

/**
 * Zones maquette sans donnée / fonctionnalité produit — aucune (Comparaison complète : sélecteurs,
 * compteurs, bascule, CTA Restaurer). Liste conservée vide pour que toute absence future soit déclarée.
 * @type {import('./visual-parity-kit.mjs').NotImplementedZone[]}
 */
export const NOT_IMPLEMENTED = []

/** Sections pleine largeur : maquette 1440 px (sans sidebar) vs app 1172 px (shell conservé). */
const SIZE_EXCEPTIONS = {
  'diff-topbar': { dw: SHELL_DW, dh: 0, reason: SHELL_DECISION_REASON },
  'diff-controls': { dw: SHELL_DW, dh: 0, reason: SHELL_DECISION_REASON },
}

const FIXED_SECTIONS = [
  // Actions (droite) masquées ici : comparées dans diff-back / diff-restore.
  { id: 'diff-topbar', name: 'topbar', masks: ['topbar-actions'], maskColor: '#FFFFFF' },
  { id: 'diff-back', name: 'back', masks: [] },
  { id: 'diff-restore', name: 'restore', masks: [] },
  // Bascule (droite) masquée ici : comparée dans diff-toggle.
  { id: 'diff-controls', name: 'controls', masks: ['controls-toggle'], maskColor: '#FAFAFB' },
  { id: 'diff-toggle', name: 'toggle', masks: [] },
]

async function openCompare(page, opts) {
  await prep(page, opts)
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto(COMPARE_URL)
  await page.waitForSelector('[data-mock-id="diff-hunk-2"]')
  await settleFonts(page)
}

async function expandCollapsed(page) {
  for (const btn of await page.getByTestId('diff-collapsed').all()) await btn.click()
}

/** Annotation Diff.dc.html — data-* uniquement. */
async function annotateMock(page) {
  await annotateDiffMockup(page)
  await page.evaluate(() => {
    const root = document.querySelector('body div[style*="1440px"]') || document.body.firstElementChild
    const [topbar, controls] = [...root.children]
    topbar?.children[1]?.setAttribute('data-visual-mask', 'topbar-actions')
    controls?.children[5]?.setAttribute('data-visual-mask', 'controls-toggle')
  })
  return annotateDiffBody(page, 'mock')
}

/** Annotation app — data-* uniquement. */
async function annotateApp(page) {
  await page.evaluate(() => {
    document
      .querySelector('[data-mock-id="diff-topbar"] .hist-actions')
      ?.setAttribute('data-visual-mask', 'topbar-actions')
    document.querySelector('[data-mock-id="diff-toggle"]')?.setAttribute('data-visual-mask', 'controls-toggle')
  })
  return annotateDiffBody(page, 'app')
}

test.describe('Comparaison - parite visuelle', () => {
  test('desktop Comparaison vs Diff.dc.html - sections pixel <= 1 % (texte inclus)', async ({ page }, testInfo) => {
    await prep(page)
    await page.setViewportSize({ width: 1440, height: 900 })

    await page.goto(`${MOCK}/Diff.dc.html`)
    const mockCounts = await annotateMock(page)
    await settleFonts(page)

    const sections = [...FIXED_SECTIONS, ...diffBodySections(mockCounts)]
    const mockSec = {}
    for (const s of sections) {
      mockSec[s.name] = await shotSection(page, `[data-mock-id="${s.id}"]`, {
        maskNames: s.masks,
        maskColor: s.maskColor,
        label: `diff-sec-${s.name}-mock`,
      })
    }

    await page.goto(COMPARE_URL)
    await page.waitForSelector('[data-mock-id="diff-hunk-2"]')
    await expandCollapsed(page)
    await page.mouse.move(1, 1)
    await settleFonts(page)
    const appCounts = await annotateApp(page)
    expect(appCounts, 'même nombre de blocs / lignes maquette et app').toEqual(mockCounts)

    const compare = compareSectionShots({
      ...SIZE_EXCEPTIONS,
      ...diffBodySizeExceptions('diff', mockCounts),
    })
    const results = []
    for (const s of sections) {
      const app = await shotSection(page, `[data-mock-id="${s.id}"]`, {
        maskNames: s.masks,
        maskColor: s.maskColor,
        label: `diff-sec-${s.name}-app`,
      })
      results.push(compare(`diff-${s.name}`, mockSec[s.name].shot, app.shot, testInfo))
    }
    finishPixelResults('diff', results, { notImplemented: NOT_IMPLEMENTED })
  })
})

test.describe('Comparaison - structure', () => {
  const SHELL =
    'AppShell : sidebar 268 px absente de la maquette → box.x / box.width décalés (box.y en cascade) ; hauteur et typographie comparées.'
  const exceptions = {
    'diff-topbar': { skip: ['text', 'box.x', 'box.y', 'box.width'], reason: `Conteneur ; ${SHELL}` },
    'diff-breadcrumb-current': { skip: ['box.x', 'box.y'], reason: SHELL },
    'diff-back': { skip: ['box.x', 'box.y'], reason: SHELL },
    'diff-restore': { skip: ['box.x', 'box.y'], reason: SHELL },
    'diff-controls': { skip: ['text', 'box.x', 'box.y', 'box.width'], reason: `Conteneur ; ${SHELL}` },
    'diff-box': { skip: ['text', 'box.x', 'box.y', 'box.width'], reason: `Conteneur ; ${SHELL}` },
    'diff-toggle': { skip: ['text', 'box.x', 'box.y'], reason: `Conteneur ; ${SHELL}` },
    'diff-seg-side': { skip: ['box.x', 'box.y'], reason: SHELL },
    'diff-seg-unified': { skip: ['box.x', 'box.y'], reason: SHELL },
    'diff-sel-from': { skip: ['text', 'box.x', 'box.y'], reason: `D2 select natif ; ${SHELL}` },
    'diff-sel-to': { skip: ['text', 'box.x', 'box.y'], reason: `D2 select natif ; ${SHELL}` },
    'diff-added': { skip: ['box.x', 'box.y'], reason: SHELL },
    'diff-removed': { skip: ['box.x', 'box.y'], reason: SHELL },
    'diff-hunk-0': { skip: ['box.x', 'box.y', 'box.width'], reason: SHELL },
    'diff-hunk-1': { skip: ['box.x', 'box.y', 'box.width'], reason: SHELL },
    'diff-hunk-2': { skip: ['box.x', 'box.y', 'box.width'], reason: SHELL },
  }
  test('desktop Comparaison structural match', async ({ page }) => {
    await prep(page)
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto(`${MOCK}/Diff.dc.html`)
    await settleFonts(page)
    await annotateDiffMockup(page)
    const mockMap = await collectMetrics(page, DIFF_DESKTOP_IDS)
    await page.goto(COMPARE_URL)
    await page.waitForSelector('[data-mock-id="diff-hunk-2"]')
    await expandCollapsed(page)
    await settleFonts(page)
    const appMap = await collectMetrics(page, DIFF_DESKTOP_IDS)
    const results = compareMetrics(mockMap, appMap, DIFF_DESKTOP_IDS, { pageExceptions: exceptions })
    writeStructural('structural-diff-desktop.json', results)
    expectNoStructuralDiffs(results)
  })
})

test.describe('Comparaison - comportement', () => {
  test('selecteurs Depuis / Vers, compteurs et CTA Restaurer v11', async ({ page }) => {
    await openCompare(page)
    await expect(page.getByTestId('diff-select-from')).toHaveValue('11')
    await expect(page.getByTestId('diff-select-to')).toHaveValue('12')
    await expect(page.getByTestId('diff-added')).toHaveText('+18')
    await expect(page.getByTestId('diff-removed')).toHaveText('−4')
    await expect(page.getByTestId('compare-restore')).toHaveText('Restaurer v11')
  })

  test('N lignes inchangees est repliee puis depliable', async ({ page }) => {
    await openCompare(page)
    const collapsed = page.getByTestId('diff-collapsed')
    await expect(collapsed).toHaveCount(1)
    await expect(collapsed).toContainText(/2 lignes inchang/)
    await expect(page.getByText(/de revue formalis/)).toHaveCount(0)
    await collapsed.click()
    await expect(page.getByTestId('diff-collapsed')).toHaveCount(0)
    await expect(page.getByText(/de revue formalis/)).toHaveCount(2)
  })

  test('bascule Cote a cote / Unifie en etat React', async ({ page }) => {
    await openCompare(page)
    await expect(page.getByTestId('diff-view')).toHaveAttribute('data-mode', 'side')
    await page.getByTestId('diff-mode-unified').click()
    await expect(page.getByTestId('diff-view')).toHaveAttribute('data-mode', 'unified')
    await page.getByTestId('diff-mode-side').click()
    await expect(page.getByTestId('diff-view')).toHaveAttribute('data-mode', 'side')
    const stored = await page.evaluate(() =>
      Object.keys(localStorage).filter((k) => /diff|compare|mode/i.test(k)),
    )
    expect(stored).toEqual([])
  })

  test('lecteur sans droit edition : pas de CTA Restaurer', async ({ page }) => {
    await openCompare(page, { editor: false })
    await expect(page.getByTestId('diff-view')).toBeVisible()
    await expect(page.getByTestId('compare-restore')).toHaveCount(0)
  })
})
