// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
/**
 * Règles : docs/visual-parity.md
 *
 * Écran Approbation (/approvals/:requestId) vs Approval.dc.html @ 1440×900.
 *
 * Pixel honnête (texte inclus, ≤ 1 % par section) ; seul exclu : `mask` Playwright nommé
 * (`topbar-actions`, comparé par ailleurs dans `approval-comments`).
 * Annotation maquette / app : data-* uniquement (aucune modification de style / DOM avant capture).
 *
 * Écart de taille déclaré (SIZE_EXCEPTIONS) : la maquette n'a pas de sidebar, l'app garde le shell (268 px)
 * → docs/visual-parity.md § Décisions produit — Comparaison / Approbation. Seule la barre haute (pleine
 * largeur) est concernée : les éléments de la colonne centrale (680 px) et le rail (349 px) ont la même
 * taille des deux côtés.
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
 *  - A3  Zone de justification : `<textarea>` réel (boîte 86 px identique) ; le texte (placeholder) est
 *        comparé au pixel ; en structure seules les propriétés de boîte le sont.
 *  - A4  « Approuver la révision v13 » est un bouton qui décide (la maquette navigue vers
 *        DiffApproval) ; « Refuser » est bloqué côté client sans justification (pas de style désactivé,
 *        donc pas d'écart visuel) et affiche « Justification obligatoire pour un refus ».
 *  - A5  Lien « Comparer v12 → v13 » : texte du conteneur non comparé en structure (espace entre les deux
 *        spans dans la maquette) ; libellé et compteurs « +18 −4 » comparés séparément. Compteurs issus de
 *        `compare?mode=lines`.
 *  - A6  Résumé de la révision (« …, incluant … ») : provient du `changeSummary` de la version soumise
 *        (v13), la maquette le code en dur.
 *  - A9  Mode lecture seule (`canDecide: false`) : badge « LECTURE », raison, pas de boutons
 *        Approuver/Refuser — hors maquette Approval.dc.html (pas de pixel-diff lecture seule).
 *
 * Mesure avant polish : `VISUAL_BASELINE=1 npx playwright test e2e-visual/approval-visual.spec.mjs`
 * (journalise tous les % / tailles, écrit test-results/approval-before.json, n'échoue pas).
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
import { MOCK, prepApproval, settleFonts, writeStructural } from './approval-helpers.mjs'
import {
  compareSectionShots,
  expectNoStructuralDiffs,
  finishPixelResults,
  shotSection,
} from './visual-parity-kit.mjs'
import { SHELL_DECISION_REASON, SHELL_DW } from './diff-parity-shared.mjs'

test.use({ timezoneId: 'Europe/Paris' })

const URL = `/approvals/${APPR_REQUEST_ID}`

/**
 * Zones maquette sans donnée / fonctionnalité produit — aucune pour l'instant. Liste conservée vide pour
 * que toute absence future soit déclarée.
 * @type {import('./visual-parity-kit.mjs').NotImplementedZone[]}
 */
export const NOT_IMPLEMENTED = []

/** Barre haute pleine largeur : maquette 1440 px (sans sidebar) vs app 1172 px (shell conservé). */
const SIZE_EXCEPTIONS = {
  'approval-topbar': { dw: SHELL_DW, dh: 0, reason: SHELL_DECISION_REASON },
}

const PIXEL_SECTIONS = [
  // Action (droite) masquée ici : comparée dans approval-comments.
  { id: 'appr-topbar', name: 'topbar', masks: ['topbar-actions'] },
  { id: 'appr-comments', name: 'comments', masks: [] },
  { id: 'appr-badge', name: 'badge', masks: [] },
  { id: 'appr-title', name: 'title', masks: [] },
  { id: 'appr-lede', name: 'lede', masks: [] },
  { id: 'appr-circuit', name: 'circuit', masks: [] },
  { id: 'appr-justif-label', name: 'justif-label', masks: [] },
  { id: 'appr-field', name: 'field', masks: [] },
  { id: 'appr-approve', name: 'approve', masks: [] },
  { id: 'appr-reject', name: 'reject', masks: [] },
  { id: 'appr-rail', name: 'rail', masks: [] },
]

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

/** Annotation Approval.dc.html — data-* uniquement. */
async function annotateMock(page) {
  await annotateApprovalMockup(page)
  await page.evaluate(() => {
    const root = document.querySelector('body div[style*="1440px"]') || document.body.firstElementChild
    root?.children[0]?.children[1]?.setAttribute('data-visual-mask', 'topbar-actions')
  })
}

/** Annotation app — data-* uniquement. */
async function annotateApp(page) {
  await page.evaluate(() => {
    document.querySelector('[data-mock-id="appr-comments"]')?.setAttribute('data-visual-mask', 'topbar-actions')
  })
}

test.describe('Approbation — parité visuelle', () => {
  test('desktop Approbation vs Approval.dc.html — sections pixel ≤ 1 % (texte inclus)', async ({ page }, testInfo) => {
    await prepApproval(page)
    await page.setViewportSize({ width: 1440, height: 900 })

    await page.goto(`${MOCK}/Approval.dc.html`)
    await annotateMock(page)
    await settleFonts(page)

    const mockSec = {}
    for (const s of PIXEL_SECTIONS) {
      mockSec[s.name] = await shotSection(page, `[data-mock-id="${s.id}"]`, {
        maskNames: s.masks,
        label: `approval-sec-${s.name}-mock`,
      })
    }

    await page.goto(URL)
    await page.waitForSelector('[data-mock-id="appr-step-2-name"]')
    await page.waitForSelector('[data-mock-id="appr-compare-counts"]')
    await page.mouse.move(1, 1)
    await settleFonts(page)
    await annotateApp(page)

    const compare = compareSectionShots(SIZE_EXCEPTIONS)
    const results = []
    for (const s of PIXEL_SECTIONS) {
      const app = await shotSection(page, `[data-mock-id="${s.id}"]`, {
        maskNames: s.masks,
        label: `approval-sec-${s.name}-app`,
      })
      results.push(compare(`approval-${s.name}`, mockSec[s.name].shot, app.shot, testInfo))
    }
    finishPixelResults('approval', results, { notImplemented: NOT_IMPLEMENTED })
  })
})

test.describe('Approbation — structure', () => {
  const SHELL =
    'AppShell : sidebar 268 px absente de la maquette → colonne centrale recentrée (box.x +134 px) ; barre haute décalée de 268 px ; hauteurs, largeurs et typographie comparées.'
  const exceptions = {
    'appr-topbar': {
      skip: ['text', 'box.x', 'box.y', 'box.width'],
      reason: `Conteneur — textes comparés sur les enfants annotés ; ${SHELL}`,
    },
    'appr-breadcrumb-current': { skip: ['box.x'], reason: SHELL },
    'appr-circuit': { skip: ['text', 'box.x'], reason: `Conteneur — textes comparés sur les enfants annotés (A1) ; ${SHELL}` },
    'appr-rail': { skip: ['text'], reason: 'Conteneur — textes comparés sur les enfants annotés' },
    'appr-field': { skip: ['text', 'box.x'], reason: `A3 : textarea réel, placeholder non comparé en structure ; ${SHELL}` },
    'appr-compare-link': { skip: ['text'], reason: 'A5 : conteneur, libellé et compteurs comparés séparément' },
    ...Object.fromEntries(
      [
        'appr-badge',
        'appr-title',
        'appr-lede',
        'appr-circuit-label',
        'appr-step-1-node',
        'appr-step-1-name',
        'appr-step-1-status',
        'appr-step-2-node',
        'appr-step-2-name',
        'appr-step-2-status',
        'appr-publication-name',
        'appr-publication-status',
        'appr-justif-label',
        'appr-approve',
        'appr-reject',
      ].map((id) => [id, { skip: ['box.x'], reason: SHELL }]),
    ),
  }

  test('desktop Approbation structural match', async ({ page }) => {
    await prepApproval(page)
    await page.setViewportSize({ width: 1440, height: 900 })

    await page.goto(`${MOCK}/Approval.dc.html`)
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
    expectNoStructuralDiffs(results)
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
