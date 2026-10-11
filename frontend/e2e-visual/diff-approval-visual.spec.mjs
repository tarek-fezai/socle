// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
/**
 * Règles : docs/visual-parity.md
 *
 * Comparaison d'approbation (/approvals/:requestId/diff) vs DiffApproval.dc.html @ 1440×900.
 * Données : `GET …/versions/12/compare/13?mode=lines` (comparaison ligne à ligne, pas le diff JSON),
 * rendu par le même `DiffView` que Historique → Comparer.
 *
 * Pixel honnête (texte inclus, ≤ 1 % par section) ; seul exclu : `mask` Playwright nommé
 * (`topbar-actions` / `controls-badge`, comparés par ailleurs section par section).
 * Annotation maquette / app : data-* uniquement (aucune modification de style / DOM avant capture).
 * Blocs « N lignes inchangées » : dépliés par un clic utilisateur (la maquette les affiche dépliés).
 *
 * La classe CSS des cellules « nouvelle version » des lignes modifiées était fausse dans la maquette
 * (`row-add` posé sur le `.side` lui-même) : corrigée À LA SOURCE (DiffApproval.dc.html), pas avant capture.
 *
 * Écart de taille déclaré (SIZE_EXCEPTIONS) : la maquette n'a pas de sidebar, l'app garde le shell (268 px)
 * → docs/visual-parity.md § Décisions produit — Comparaison / Approbation.
 *
 * Exceptions documentées (reprises dans la PR) :
 *  - D1  Pas de bascule « Côte à côte / Unifié » sur desktop : la maquette l'a remplacée par le badge
 *        « RÉVISION EN ATTENTE D'APPROBATION ». Sur mobile, le rendu est unifié (aucune maquette dédiée).
 *  - D2  « Depuis » / « Vers » : `<select>` natifs (texte du conteneur non comparé en structure).
 *  - D3  CTA « Approuver la révision v13 » : bouton qui décide (la maquette navigue vers Approval) ;
 *        un échec serveur s'affiche sous la barre de contrôles.
 *  - D4  Fil d'Ariane « Approbation → Comparer v12 → v13 » : « Approbation » renvoie au détail
 *        `/approvals/:requestId`.
 *
 * Mesure avant polish : `VISUAL_BASELINE=1 npx playwright test e2e-visual/diff-approval-visual.spec.mjs`
 * (journalise tous les % / tailles, écrit test-results/diff-approval-before.json, n'échoue pas).
 */
import { test, expect } from '@playwright/test'
import {
  DIFF_APPROVAL_IDS,
  annotateDiffApprovalMockup,
  collectMetrics,
  compareMetrics,
} from './structural-compare.mjs'
import { APPR_REQUEST_ID } from './approval-fixtures.mjs'
import { MOCK, prepApproval, settleFonts, writeStructural } from './approval-helpers.mjs'
import {
  compareSectionShots,
  expectNoStructuralDiffs,
  finishPixelResults,
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

const URL = `/approvals/${APPR_REQUEST_ID}/diff`

/**
 * Zones maquette sans donnée / fonctionnalité produit — aucune. Liste conservée vide pour que toute
 * absence future soit déclarée.
 * @type {import('./visual-parity-kit.mjs').NotImplementedZone[]}
 */
export const NOT_IMPLEMENTED = []

/** Sections pleine largeur : maquette 1440 px (sans sidebar) vs app 1172 px (shell conservé). */
const SIZE_EXCEPTIONS = {
  'diff-approval-topbar': { dw: SHELL_DW, dh: 0, reason: SHELL_DECISION_REASON },
  'diff-approval-controls': { dw: SHELL_DW, dh: 0, reason: SHELL_DECISION_REASON },
}

const FIXED_SECTIONS = [
  // Actions (droite) masquées ici : comparées dans adiff-back / adiff-approve.
  { id: 'adiff-topbar', name: 'topbar', masks: ['topbar-actions'], maskColor: '#FFFFFF' },
  { id: 'adiff-back', name: 'back', masks: [] },
  { id: 'adiff-approve', name: 'approve', masks: [] },
  // Badge (droite) masqué ici : comparé dans adiff-badge.
  { id: 'adiff-controls', name: 'controls', masks: ['controls-badge'], maskColor: '#FAFAFB' },
  { id: 'adiff-badge', name: 'badge', masks: [] },
]

async function openDiff(page, opts) {
  await prepApproval(page, opts)
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto(URL)
  await page.waitForSelector('[data-mock-id="diff-hunk-2"]')
  await settleFonts(page)
}

async function expandCollapsed(page) {
  for (const btn of await page.getByTestId('diff-collapsed').all()) await btn.click()
}

/** Annotation DiffApproval.dc.html — data-* uniquement. */
async function annotateMock(page) {
  await annotateDiffApprovalMockup(page)
  await page.evaluate(() => {
    const root = document.querySelector('body div[style*="1440px"]') || document.body.firstElementChild
    const [topbar, controls] = [...root.children]
    topbar?.children[1]?.setAttribute('data-visual-mask', 'topbar-actions')
    controls?.children[5]?.setAttribute('data-visual-mask', 'controls-badge')
  })
  return annotateDiffBody(page, 'mock')
}

/** Annotation app — data-* uniquement. */
async function annotateApp(page) {
  await page.evaluate(() => {
    document
      .querySelector('[data-mock-id="adiff-topbar"] .hist-actions')
      ?.setAttribute('data-visual-mask', 'topbar-actions')
    document.querySelector('[data-mock-id="adiff-badge"]')?.setAttribute('data-visual-mask', 'controls-badge')
  })
  return annotateDiffBody(page, 'app')
}

test.describe('Comparaison d’approbation — parité visuelle', () => {
  test('desktop DiffApproval vs DiffApproval.dc.html — sections pixel ≤ 1 % (texte inclus)', async ({ page }, testInfo) => {
    await prepApproval(page)
    await page.setViewportSize({ width: 1440, height: 900 })

    await page.goto(`${MOCK}/DiffApproval.dc.html`)
    const mockCounts = await annotateMock(page)
    await settleFonts(page)

    const sections = [...FIXED_SECTIONS, ...diffBodySections(mockCounts)]
    const mockSec = {}
    for (const s of sections) {
      mockSec[s.name] = await shotSection(page, `[data-mock-id="${s.id}"]`, {
        maskNames: s.masks,
        maskColor: s.maskColor,
        label: `diff-approval-sec-${s.name}-mock`,
      })
    }

    await page.goto(URL)
    await page.waitForSelector('[data-mock-id="diff-hunk-2"]')
    await expandCollapsed(page)
    await page.mouse.move(1, 1)
    await settleFonts(page)
    const appCounts = await annotateApp(page)
    expect(appCounts, 'même nombre de blocs / lignes maquette et app').toEqual(mockCounts)

    const compare = compareSectionShots({
      ...SIZE_EXCEPTIONS,
      ...diffBodySizeExceptions('diff-approval', mockCounts),
    })
    const results = []
    for (const s of sections) {
      const app = await shotSection(page, `[data-mock-id="${s.id}"]`, {
        maskNames: s.masks,
        maskColor: s.maskColor,
        label: `diff-approval-sec-${s.name}-app`,
      })
      results.push(compare(`diff-approval-${s.name}`, mockSec[s.name].shot, app.shot, testInfo))
    }
    finishPixelResults('diff-approval', results, { notImplemented: NOT_IMPLEMENTED })
  })
})

test.describe('Comparaison d’approbation — structure', () => {
  const SHELL =
    'AppShell : sidebar 268 px absente de la maquette → box.x / box.width décalés (box.y en cascade) ; hauteur et typographie comparées.'
  const exceptions = {
    'adiff-topbar': { skip: ['text', 'box.x', 'box.y', 'box.width'], reason: `Conteneur ; ${SHELL}` },
    'adiff-breadcrumb-current': { skip: ['box.x', 'box.y'], reason: SHELL },
    'adiff-back': { skip: ['box.x', 'box.y'], reason: SHELL },
    'adiff-approve': { skip: ['box.x', 'box.y'], reason: SHELL },
    'adiff-controls': { skip: ['text', 'box.x', 'box.y', 'box.width'], reason: `Conteneur ; ${SHELL}` },
    'adiff-sel-from': { skip: ['text', 'box.x', 'box.y'], reason: `D2 select natif ; ${SHELL}` },
    'adiff-sel-to': { skip: ['text', 'box.x', 'box.y'], reason: `D2 select natif ; ${SHELL}` },
    'adiff-added': { skip: ['box.x', 'box.y'], reason: SHELL },
    'adiff-removed': { skip: ['box.x', 'box.y'], reason: SHELL },
    'adiff-badge': { skip: ['box.x', 'box.y'], reason: SHELL },
    'diff-box': { skip: ['text', 'box.x', 'box.y', 'box.width'], reason: `Conteneur ; ${SHELL}` },
    'diff-hunk-0': { skip: ['box.x', 'box.y', 'box.width'], reason: SHELL },
    'diff-hunk-1': { skip: ['box.x', 'box.y', 'box.width'], reason: SHELL },
    'diff-hunk-2': { skip: ['box.x', 'box.y', 'box.width'], reason: SHELL },
  }

  test('desktop DiffApproval structural match', async ({ page }) => {
    await prepApproval(page)
    await page.setViewportSize({ width: 1440, height: 900 })

    await page.goto(`${MOCK}/DiffApproval.dc.html`)
    await settleFonts(page)
    await annotateDiffApprovalMockup(page)
    const mockMap = await collectMetrics(page, DIFF_APPROVAL_IDS)

    await page.goto(URL)
    await page.waitForSelector('[data-mock-id="diff-hunk-2"]')
    await expandCollapsed(page)
    await settleFonts(page)
    const appMap = await collectMetrics(page, DIFF_APPROVAL_IDS)

    const results = compareMetrics(mockMap, appMap, DIFF_APPROVAL_IDS, { pageExceptions: exceptions })
    writeStructural('structural-diff-approval-desktop.json', results)
    expectNoStructuralDiffs(results)
  })
})

test.describe('Comparaison d’approbation — comportement', () => {
  test('compare la dernière version approuvée (v12) à la révision soumise (v13), mode lignes', async ({ page }) => {
    const urls = []
    await prepApproval(page)
    page.on('request', (r) => {
      if (r.url().includes('/compare/')) urls.push(r.url())
    })
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto(URL)
    await page.waitForSelector('[data-mock-id="diff-hunk-0"]')
    await expect(page.getByTestId('adiff-select-from')).toHaveValue('12')
    await expect(page.getByTestId('adiff-select-to')).toHaveValue('13')
    await expect(page.getByTestId('adiff-added')).toHaveText('+18')
    await expect(page.getByTestId('adiff-removed')).toHaveText('−4')
    await expect(page.getByTestId('diff-view')).toHaveAttribute('data-mode', 'side')
    expect(urls.some((u) => u.includes('/versions/12/compare/13') && u.includes('mode=lines'))).toBe(true)
  })

  test('CTA Approuver : décision puis retour à la liste', async ({ page }) => {
    const decisions = { calls: [] }
    await openDiff(page, { decisions })
    await expect(page.getByTestId('adiff-approve')).toHaveText('Approuver la révision v13')
    await page.getByTestId('adiff-approve').click()
    await expect.poll(() => decisions.calls.length).toBe(1)
    expect(decisions.calls[0].body).toEqual({ decision: 'approuve', comment: null, expectedStepOrder: 2 })
    await expect(page).toHaveURL(/\/approvals$/)
  })

  test('erreur serveur à l’approbation : message affiché, pas de redirection', async ({ page }) => {
    await openDiff(page, {
      decideStatus: 409,
      decideBody: { error: 'already_resolved', message: 'Demande déjà résolue' },
    })
    await page.getByTestId('adiff-approve').click()
    await expect(page.getByTestId('approval-error')).toContainText('déjà été traitée')
    await expect(page).toHaveURL(new RegExp(`/approvals/${APPR_REQUEST_ID}/diff$`))
  })

  test('demande inconnue : message et lien de retour', async ({ page }) => {
    await prepApproval(page, { items: [], detail: null, detailStatus: 404 })
    await page.goto(URL)
    await expect(page.getByTestId('approval-diff-missing')).toBeVisible()
    await expect(page.getByRole('link', { name: 'Retour aux approbations' })).toBeVisible()
  })
})
