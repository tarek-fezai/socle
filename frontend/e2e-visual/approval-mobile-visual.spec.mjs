// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
/**
 * Règles : docs/visual-parity.md
 *
 * Approbation mobile (/approvals/:requestId @ 390×844) vs MobileApproval.dc.html.
 *
 * Pixel honnête (texte inclus, ≤ 1 % par section, surfaces rapportées à 390×844) ; aucun masque.
 * Annotation maquette / app : data-* uniquement (aucune modification de style / DOM avant capture).
 * Mobile : pas de sidebar des deux côtés → aucune SIZE_EXCEPTION attendue.
 *
 * Exceptions documentées (numérotées, reprises dans la PR) :
 *  - M1  « Étape 1 sur 2 » : le total vient du circuit applicable (`applicable-workflow`) ; sans lui
 *        l'app affiche « Étape 1 — En attente de vous » (repli A1, couvert en test unitaire).
 *  - M2  Chaîne d'approbation : les étapes atteintes (précédentes et courante) sont cochées, les suivantes
 *        vides, comme la maquette (N1 « vous » cochée). Historique par étape absent de l'API (A1).
 *  - M3  « Documents liés impactés » : absents de la maquette mobile ; rendus sous la citation seulement
 *        si la demande en a (fixture mobile : aucun lien → aucun écart).
 *  - M4  Justification : pas de champ dans la maquette. Un premier appui sur « Rejeter » ouvre le champ,
 *        le second envoie (refus bloqué côté client sans texte). « Approuver » n'exige rien.
 *  - M5  Boutons de la barre du bas : police IBM Plex (app) vs police système (maquette, `<button>` sans
 *        `font-family`) — familles non comparées en structure ; comparées au pixel (texte inclus).
 *  - M6  Pas de barre d'onglets du bas : la maquette n'en a pas (le shell la masque sur cet écran).
 *
 * Mesure avant polish : `VISUAL_BASELINE=1 npx playwright test e2e-visual/approval-mobile-visual.spec.mjs`
 * (journalise tous les % / tailles, écrit test-results/approval-mobile-before.json, n'échoue pas).
 */
import { test, expect } from '@playwright/test'
import {
  APPROVAL_MOBILE_IDS,
  annotateMobileApprovalMockup,
  collectMetrics,
  compareMetrics,
} from './structural-compare.mjs'
import {
  APPR_DETAIL_MOBILE,
  APPR_ITEM_MOBILE,
  APPR_REQUEST_ID,
  APPR_VERSIONS_MOBILE,
  APPR_WORKFLOW_MOBILE,
} from './approval-fixtures.mjs'
import { MOCK, prepApproval, settleFonts, writeStructural } from './approval-helpers.mjs'
import {
  compareSectionShots,
  expectNoStructuralDiffs,
  finishPixelResults,
  shotSection,
} from './visual-parity-kit.mjs'

test.use({ timezoneId: 'Europe/Paris' })

const URL = `/approvals/${APPR_REQUEST_ID}`
const MOBILE_AREA = 390 * 844
const MOBILE_OPTS = {
  items: [APPR_ITEM_MOBILE],
  detail: APPR_DETAIL_MOBILE,
  workflow: APPR_WORKFLOW_MOBILE,
  versions: APPR_VERSIONS_MOBILE,
}

/**
 * Zones maquette sans donnée / fonctionnalité produit — aucune pour l'instant. Liste conservée vide pour
 * que toute absence future soit déclarée.
 * @type {import('./visual-parity-kit.mjs').NotImplementedZone[]}
 */
export const NOT_IMPLEMENTED = []

/** Aucune sidebar sur mobile : tailles attendues identiques, aucune exception. */
const SIZE_EXCEPTIONS = {}

const PIXEL_SECTIONS = [
  { id: 'appr-m-page', name: 'page' },
  { id: 'appr-mobile-top', name: 'top' },
  { id: 'appr-m-badge', name: 'badge' },
  { id: 'appr-m-title', name: 'title' },
  { id: 'appr-m-sub', name: 'sub' },
  { id: 'appr-m-chain', name: 'chain' },
  { id: 'appr-m-diff-link', name: 'diff-link' },
  { id: 'appr-m-quote', name: 'quote' },
  { id: 'appr-m-bar', name: 'bar' },
]

async function openMobile(page, opts = {}) {
  await prepApproval(page, { ...MOBILE_OPTS, ...opts })
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto(URL)
  await page.waitForSelector('[data-mock-id="appr-m-step-2"]')
  await page.waitForSelector('[data-mock-id="appr-m-quote-text"]')
  await settleFonts(page)
}

/** Annotation MobileApproval.dc.html — data-* uniquement. */
async function annotateMock(page) {
  await annotateMobileApprovalMockup(page)
  await page.evaluate(() => {
    const root = Array.from(document.querySelectorAll('div')).find((d) =>
      (d.getAttribute('style') || '').includes('390px'),
    )
    root?.setAttribute('data-mock-id', 'appr-m-page')
    root?.children[0]?.setAttribute('data-mock-id', 'appr-mobile-top')
  })
}

/** Annotation app — data-* uniquement (la page entière = `body`, 390×844 attendus). */
async function annotateApp(page) {
  await page.evaluate(() => {
    document.body.setAttribute('data-mock-id', 'appr-m-page')
  })
}

test.describe('Approbation mobile — parité visuelle', () => {
  test('mobile Approbation vs MobileApproval.dc.html — sections pixel ≤ 1 % (texte inclus)', async ({ page }, testInfo) => {
    await prepApproval(page, MOBILE_OPTS)
    await page.setViewportSize({ width: 390, height: 844 })

    await page.goto(`${MOCK}/MobileApproval.dc.html`)
    await annotateMock(page)
    await settleFonts(page)

    const mockSec = {}
    for (const s of PIXEL_SECTIONS) {
      mockSec[s.name] = await shotSection(page, `[data-mock-id="${s.id}"]`, {
        label: `approval-mobile-sec-${s.name}-mock`,
        pageArea: MOBILE_AREA,
      })
    }

    await page.goto(URL)
    await page.waitForSelector('[data-mock-id="appr-m-quote-text"]')
    await page.mouse.move(1, 1)
    await settleFonts(page)
    await annotateApp(page)

    const compare = compareSectionShots(SIZE_EXCEPTIONS)
    const results = []
    for (const s of PIXEL_SECTIONS) {
      const app = await shotSection(page, `[data-mock-id="${s.id}"]`, {
        label: `approval-mobile-sec-${s.name}-app`,
        pageArea: MOBILE_AREA,
      })
      results.push(compare(`approval-mobile-${s.name}`, mockSec[s.name].shot, app.shot, testInfo))
    }
    finishPixelResults('approval-mobile', results, { notImplemented: NOT_IMPLEMENTED })
  })
})

test.describe('Approbation mobile — structure', () => {
  const exceptions = {
    'appr-m-chain': { skip: ['text'], reason: 'Conteneur — étapes comparées séparément' },
    'appr-m-diff-link': { skip: ['text'], reason: 'Conteneur — libellé comparé séparément' },
    'appr-m-quote': { skip: ['text'], reason: 'Conteneur — paragraphe comparé séparément' },
    'appr-m-reject': { skip: ['fontFamily'], reason: 'M5 : police système (maquette) vs IBM Plex' },
    'appr-m-approve': { skip: ['fontFamily'], reason: 'M5 : police système (maquette) vs IBM Plex' },
    'appr-m-bar': { skip: ['text'], reason: 'Conteneur — boutons comparés séparément' },
  }

  test('mobile Approbation structural match', async ({ page }) => {
    await prepApproval(page, MOBILE_OPTS)
    await page.setViewportSize({ width: 390, height: 844 })

    await page.goto(`${MOCK}/MobileApproval.dc.html`)
    await settleFonts(page)
    await annotateMobileApprovalMockup(page)
    const mockMap = await collectMetrics(page, APPROVAL_MOBILE_IDS)

    await page.goto(URL)
    await page.waitForSelector('[data-mock-id="appr-m-quote-text"]')
    await settleFonts(page)
    const appMap = await collectMetrics(page, APPROVAL_MOBILE_IDS)

    const results = compareMetrics(mockMap, appMap, APPROVAL_MOBILE_IDS, { pageExceptions: exceptions })
    writeStructural('structural-approval-mobile.json', results)
    expectNoStructuralDiffs(results)
  })
})

test.describe('Approbation mobile — comportement', () => {
  test('« Voir les modifications proposées » ouvre la comparaison de la demande', async ({ page }) => {
    await openMobile(page)
    await page.getByRole('link', { name: 'Voir les modifications proposées' }).click()
    await expect(page).toHaveURL(new RegExp(`/approvals/${APPR_REQUEST_ID}/diff$`))
  })

  test('Rejeter : premier appui ouvre la justification, refus bloqué sans texte, puis envoyé', async ({ page }) => {
    const decisions = { calls: [] }
    await openMobile(page, { decisions })
    await expect(page.getByTestId('approval-comment')).toHaveCount(0)
    await page.getByRole('button', { name: 'Rejeter' }).click()
    await expect(page.getByTestId('approval-comment')).toBeVisible()
    await page.getByRole('button', { name: 'Rejeter' }).click()
    await expect(page.getByTestId('approval-error')).toHaveText('Justification obligatoire pour un refus')
    expect(decisions.calls).toHaveLength(0)
    await page.getByTestId('approval-comment').fill('Hors périmètre')
    await page.getByRole('button', { name: 'Rejeter' }).click()
    await expect.poll(() => decisions.calls.length).toBe(1)
    expect(decisions.calls[0].body).toEqual({ decision: 'rejete', comment: 'Hors périmètre', expectedStepOrder: 1 })
  })

  test('Approuver : décision directe', async ({ page }) => {
    const decisions = { calls: [] }
    await openMobile(page, { decisions })
    await page.getByRole('button', { name: 'Approuver' }).click()
    await expect.poll(() => decisions.calls.length).toBe(1)
    expect(decisions.calls[0].body).toEqual({ decision: 'approuve', comment: null, expectedStepOrder: 1 })
  })

  test('étape, demandeur et SLA réels', async ({ page }) => {
    await openMobile(page)
    await expect(page.locator('[data-mock-id="appr-m-badge"]')).toHaveText('Étape 1 sur 2 — En attente de vous')
    await expect(page.locator('[data-mock-id="appr-m-sub"]')).toHaveText('Demandé par Claire Dubois · SLA 22h restantes')
  })
})
