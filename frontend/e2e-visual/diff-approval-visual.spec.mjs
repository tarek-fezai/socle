// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * Comparaison d'approbation (/approvals/:requestId/diff) vs DiffApproval.dc.html @ 1440×900.
 * Données : `GET …/versions/12/compare/13?mode=lines` (comparaison ligne à ligne, pas le diff JSON),
 * rendu par le même `DiffView` que Historique → Comparer.
 *
 * Exceptions documentées (numérotées, reprises dans la PR) :
 *  - D1  Pas de bascule « Côte à côte / Unifié » sur desktop : la maquette l'a remplacée par le badge
 *        « RÉVISION EN ATTENTE D'APPROBATION ». Sur mobile, le rendu est unifié (aucune maquette dédiée).
 *  - D2  « Depuis » / « Vers » : `<select>` natifs (texte du conteneur non comparé), comme diff-visual.
 *  - D3  CTA « Approuver la révision v13 » : bouton qui décide (la maquette navigue vers Approval) ;
 *        un échec serveur s'affiche sous la barre de contrôles.
 *  - D4  Fil d'Ariane « Approbation → Comparer v12 → v13 » : « Approbation » renvoie au détail
 *        `/approvals/:requestId`.
 *  - D5  La maquette colore mal les cellules « nouvelle version » des lignes 1-2 (classe posée sur le
 *        `.side` lui-même) ; la règle est corrigée côté maquette avant comparaison, comme diff-visual.
 *  - D6  Blocs « N lignes inchangées » dépliés avant la capture (la maquette les affiche).
 *  - D7  Shell (sidebar) : comparaison limitée à la colonne principale (x ≥ 268).
 *  - D8  Glyphes masqués dans les diffs pixel ; le texte est couvert par le test structurel.
 */
import { test, expect } from '@playwright/test'
import {
  DIFF_APPROVAL_IDS,
  annotateDiffApprovalMockup,
  collectMetrics,
  compareMetrics,
} from './structural-compare.mjs'
import { APPR_REQUEST_ID } from './approval-fixtures.mjs'
import {
  MOCK,
  diffRatio,
  maskGlyphs,
  normalizeMockupWidth,
  prepApproval,
  settleFonts,
  writeStructural,
} from './approval-helpers.mjs'

test.use({ timezoneId: 'Europe/Paris' })

const URL = `/approvals/${APPR_REQUEST_ID}/diff`
const CLIP = { x: 268, y: 0, width: 1172, height: 900 }

async function normalizeMockup(page) {
  await normalizeMockupWidth(page)
  await page.addStyleTag({
    content: '.side.new.row-add .ln { background: #B7E4C7; color: #14532D; } .side.new.row-add .cell { background: #DFF3E6; }',
  })
}

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

test.describe('Comparaison d’approbation — parité visuelle', () => {
  test('desktop DiffApproval vs DiffApproval.dc.html @ 1440×900', async ({ page }, testInfo) => {
    await openDiff(page)
    await expandCollapsed(page)
    await page.evaluate(() => {
      document.body.style.margin = '0'
      document.documentElement.style.overflow = 'hidden'
    })
    await maskGlyphs(page)
    await page.waitForTimeout(150)
    const appShot = await page.screenshot({ fullPage: false, clip: CLIP })

    await page.goto(`${MOCK}/DiffApproval.dc.html`)
    await normalizeMockup(page)
    await settleFonts(page)
    await maskGlyphs(page)
    await page.waitForTimeout(150)
    const mockShot = await page.screenshot({ fullPage: false, clip: CLIP })

    await testInfo.attach('maquette-diff-approval', { body: mockShot, contentType: 'image/png' })
    await testInfo.attach('app-diff-approval', { body: appShot, contentType: 'image/png' })
    const ratio = diffRatio(mockShot, appShot, 'diff-approval-desktop')
    expect(ratio, `diff approval desktop diff ${ratio}`).toBeLessThanOrEqual(0.01)
  })
})

test.describe('Comparaison d’approbation — structure', () => {
  const exceptions = {
    'adiff-topbar': { skip: ['text'], reason: 'Conteneur' },
    'adiff-controls': { skip: ['text'], reason: 'Conteneur' },
    'diff-box': { skip: ['text'], reason: 'Conteneur' },
    'adiff-sel-from': { skip: ['text'], reason: 'D2 select natif' },
    'adiff-sel-to': { skip: ['text'], reason: 'D2 select natif' },
  }

  test('desktop DiffApproval structural match', async ({ page }, testInfo) => {
    await prepApproval(page)
    await page.setViewportSize({ width: 1440, height: 900 })

    await page.goto(`${MOCK}/DiffApproval.dc.html`)
    await normalizeMockup(page)
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
    await testInfo.attach('structural-diff-approval-desktop.json', {
      body: Buffer.from(JSON.stringify(results, null, 2)),
      contentType: 'application/json',
    })
    const failures = results.filter((r) => r.diffs.length)
    expect(failures, JSON.stringify(failures, null, 2)).toEqual([])
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
