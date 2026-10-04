// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * Écran Approbation (/approvals) vs Approval.dc.html @ 1440×900 — colonne principale (x ≥ 268).
 * Même méthode que history-visual : pixel-diff (glyphes masqués, ≤ 1 %) + comparaison structurelle
 * `data-mock-id` (textes, typographie, couleurs, boîtes ±3 px).
 * Écrans voisins : diff-approval-visual (DiffApproval.dc.html) et approval-mobile-visual
 * (MobileApproval.dc.html).
 *
 * Exceptions documentées (numérotées, reprises dans la PR) :
 *  - A1  Circuit : l'API ne fournit pas l'historique des étapes. Les rôles viennent du circuit applicable
 *        (`approvals/applicable-workflow`) ; les étapes précédant l'étape courante sont déduites
 *        « Approuvé » (une étape escaladée par SLA s'afficherait aussi ainsi). Sans circuit applicable,
 *        repli simplifié N1…Nc sans nom de rôle (couvert en test unitaire, pas en visuel).
 *  - A2  Pastille de l'étape en attente : initiales de l'utilisateur connecté (approbateur de l'étape),
 *        car `ApprovalView` n'expose pas l'approbateur désigné.
 *  - A3  Zone de justification : `<textarea>` réel (boîte 86 px identique) ; le texte (placeholder) n'est
 *        pas comparé, les propriétés de boîte le sont.
 *  - A4  « Approuver la révision v13 » est un bouton qui décide (la maquette navigue vers
 *        DiffApproval) ; « Refuser » est bloqué côté client sans justification (pas de style désactivé,
 *        donc pas d'écart visuel) et affiche « Justification obligatoire pour un refus ».
 *  - A5  Lien « Comparer v12 → v13 » : texte du conteneur non comparé (espace entre les deux spans
 *        dans la maquette) ; libellé et compteurs « +18 −4 » comparés séparément. Compteurs issus de
 *        `compare?mode=lines`.
 *  - A6  Résumé de la révision (« …, incluant … ») : provient du `changeSummary` de la version soumise
 *        (v13), la maquette le code en dur.
 *  - A7  Shell (sidebar) : comparaison limitée à la colonne principale (x ≥ 268), comme history-visual.
 *  - A8  Glyphes masqués dans les diffs pixel ; le texte est couvert par le test structurel.
 *  - A9  Mode lecture seule (`canDecide: false`) : badge « LECTURE », raison, pas de boutons
 *        Approuver/Refuser — hors maquette Approval.dc.html (pas de pixel-diff lecture seule).
 */
import { test, expect } from '@playwright/test'
import {
  APPROVAL_DESKTOP_IDS,
  annotateApprovalMockup,
  assertFontsLoaded,
  collectMetrics,
  compareMetrics,
} from './structural-compare.mjs'
import {
  APPR_DETAIL_DESKTOP,
  APPR_DETAIL_READONLY,
  APPR_REQUEST_ID,
} from './approval-fixtures.mjs'
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

const URL = `/approvals/${APPR_REQUEST_ID}`
const CLIP = { x: 268, y: 0, width: 1172, height: 900 }

async function openApprovals(page, opts) {
  await prepApproval(page, opts)
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto(URL)
  await page.waitForSelector('[data-mock-id="appr-step-2-name"]')
  const detail = opts?.detail ?? APPR_DETAIL_DESKTOP
  if (detail.baselineVersionNo != null && detail.canDecide !== false) {
    await page.waitForSelector('[data-mock-id="appr-compare-counts"]')
  }
  await settleFonts(page)
}

test.describe('Approbation — parité visuelle', () => {
  test('desktop Approbation vs Approval.dc.html @ 1440×900 (colonne principale)', async ({ page }, testInfo) => {
    await openApprovals(page)
    await page.evaluate(() => {
      document.body.style.margin = '0'
      document.documentElement.style.overflow = 'hidden'
    })
    await maskGlyphs(page)
    await page.waitForTimeout(150)
    const appShot = await page.screenshot({ fullPage: false, clip: CLIP })

    await page.goto(`${MOCK}/Approval.dc.html`)
    await normalizeMockupWidth(page)
    await settleFonts(page)
    await maskGlyphs(page)
    await page.waitForTimeout(150)
    const mockShot = await page.screenshot({ fullPage: false, clip: CLIP })

    await testInfo.attach('maquette-approval', { body: mockShot, contentType: 'image/png' })
    await testInfo.attach('app-approval', { body: appShot, contentType: 'image/png' })
    const ratio = diffRatio(mockShot, appShot, 'approval-desktop')
    expect(ratio, `approval desktop diff ${ratio}`).toBeLessThanOrEqual(0.01)
  })
})

test.describe('Approbation — structure', () => {
  const exceptions = {
    'appr-topbar': { skip: ['text'], reason: 'Conteneur — textes comparés sur les enfants annotés' },
    'appr-circuit': { skip: ['text'], reason: 'Conteneur — textes comparés sur les enfants annotés (A1)' },
    'appr-rail': { skip: ['text'], reason: 'Conteneur — textes comparés sur les enfants annotés' },
    'appr-field': { skip: ['text'], reason: 'A3 : textarea réel, placeholder non comparé' },
    'appr-compare-link': { skip: ['text'], reason: 'A5 : conteneur, libellé et compteurs comparés séparément' },
  }

  test('desktop Approbation structural match', async ({ page }, testInfo) => {
    await prepApproval(page)
    await page.setViewportSize({ width: 1440, height: 900 })

    await page.goto(`${MOCK}/Approval.dc.html`)
    await normalizeMockupWidth(page)
    await settleFonts(page)
    await annotateApprovalMockup(page)
    const mockMap = await collectMetrics(page, APPROVAL_DESKTOP_IDS)

    await page.goto(URL)
    await page.waitForSelector('[data-mock-id="appr-link-1"]')
    await page.waitForSelector('[data-mock-id="appr-compare-counts"]')
    await settleFonts(page)
    const appMap = await collectMetrics(page, APPROVAL_DESKTOP_IDS)

    const fonts = await assertFontsLoaded(page)
    expect(fonts.find((f) => f.family === 'Instrument Serif')?.loaded).toBe(true)
    expect(fonts.find((f) => f.family === 'IBM Plex Sans')?.loaded).toBe(true)

    const results = compareMetrics(mockMap, appMap, APPROVAL_DESKTOP_IDS, { pageExceptions: exceptions })
    writeStructural('structural-approval-desktop.json', results)
    await testInfo.attach('structural-approval-desktop.json', {
      body: Buffer.from(JSON.stringify(results, null, 2)),
      contentType: 'application/json',
    })
    const failures = results.filter((r) => r.diffs.length)
    expect(failures, JSON.stringify(failures, null, 2)).toEqual([])
  })
})

test.describe('Approbation — comportement', () => {
  test('données réelles : demandeur, date fr-FR, SLA, liens filtrés, lien de comparaison', async ({ page }) => {
    await openApprovals(page)
    await expect(page.locator('[data-mock-id="appr-requester-name"]')).toHaveText('Claire Dubois')
    await expect(page.locator('[data-mock-id="appr-requester-avatar"]')).toHaveText('CD')
    await expect(page.locator('[data-mock-id="appr-submitted"]')).toHaveText('27 septembre 2026 · 09:14')
    await expect(page.getByTestId('approval-sla')).toHaveText('Dans 22h · SLA 48h')
    await expect(page.getByTestId('approval-impacted-links').getByRole('link')).toHaveCount(2)
    await expect(page.getByTestId('approval-compare-link')).toHaveAttribute(
      'href',
      `/approvals/${APPR_REQUEST_ID}/diff`,
    )
  })

  test('première soumission (aucune base approuvée) : pas de lien de comparaison', async ({ page }) => {
    await openApprovals(page, {
      detail: { ...APPR_DETAIL_DESKTOP, baselineVersionNo: null, impactedLinks: [] },
    })
    await expect(page.getByTestId('approval-compare-link')).toHaveCount(0)
    await expect(page.getByTestId('approval-no-baseline')).toBeVisible()
  })

  test('lecture seule (A9) : badge LECTURE, raison, pas de boutons', async ({ page }) => {
    await openApprovals(page, { detail: APPR_DETAIL_READONLY })
    await expect(page.getByTestId('approval-badge')).toContainText('LECTURE')
    await expect(page.getByTestId('approval-readonly-reason')).toHaveText(
      'Vous avez demandé cette approbation',
    )
    await expect(page.getByRole('button', { name: /Approuver/ })).toHaveCount(0)
    await expect(page.getByRole('button', { name: 'Refuser' })).toHaveCount(0)
  })

  test('refus sans justification : bloqué côté client, aucun appel', async ({ page }) => {
    const decisions = { calls: [] }
    await openApprovals(page, { decisions })
    await page.getByRole('button', { name: 'Refuser' }).click()
    await expect(page.getByTestId('approval-error')).toHaveText('Justification obligatoire pour un refus')
    expect(decisions.calls).toHaveLength(0)
  })

  test('refus avec justification : appel avec commentaire et étape attendue', async ({ page }) => {
    const decisions = { calls: [] }
    await openApprovals(page, { decisions })
    await page.getByTestId('approval-comment').fill('Périmètre trop large')
    await page.getByRole('button', { name: 'Refuser' }).click()
    await expect.poll(() => decisions.calls.length).toBe(1)
    expect(decisions.calls[0].body).toEqual({ decision: 'rejete', comment: 'Périmètre trop large', expectedStepOrder: 2 })
  })

  test('400 serveur sur un refus : le message du serveur est affiché', async ({ page }) => {
    await openApprovals(page, {
      decideStatus: 400,
      decideBody: { status: 400, message: 'Justification obligatoire pour un refus' },
    })
    await page.getByTestId('approval-comment').fill('x')
    await page.getByRole('button', { name: 'Refuser' }).click()
    await expect(page.getByTestId('approval-error')).toHaveText('Justification obligatoire pour un refus')
  })

  test('Approuver : décision sans commentaire obligatoire', async ({ page }) => {
    const decisions = { calls: [] }
    await openApprovals(page, { decisions })
    await page.getByRole('button', { name: 'Approuver la révision v13' }).click()
    await expect.poll(() => decisions.calls.length).toBe(1)
    expect(decisions.calls[0].body).toEqual({ decision: 'approuve', comment: null, expectedStepOrder: 2 })
  })
})
