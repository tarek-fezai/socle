// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
/**
 * Approbation mobile (/approvals @ 390×844) vs MobileApproval.dc.html.
 * Même schéma que history-mobile-visual : pixel-diff plein écran (glyphes masqués, ≤ 1 %) +
 * comparaison structurelle `data-mock-id`.
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
 *        `font-family`) — familles non comparées ; hauteur de barre épinglée par les boîtes (±3 px).
 *  - M6  Pas de barre d'onglets du bas : la maquette n'en a pas (le shell la masque sur cet écran).
 *  - M7  Glyphes masqués dans les diffs pixel ; le texte est couvert par le test structurel.
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
import { MOCK, diffRatio, maskGlyphs, prepApproval, settleFonts, writeStructural } from './approval-helpers.mjs'

test.use({ timezoneId: 'Europe/Paris' })

const URL = `/approvals/${APPR_REQUEST_ID}`
const MOBILE_OPTS = {
  items: [APPR_ITEM_MOBILE],
  detail: APPR_DETAIL_MOBILE,
  workflow: APPR_WORKFLOW_MOBILE,
  versions: APPR_VERSIONS_MOBILE,
}

async function openMobile(page, opts = {}) {
  await prepApproval(page, { ...MOBILE_OPTS, ...opts })
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto(URL)
  await page.waitForSelector('[data-mock-id="appr-m-step-2"]')
  await page.waitForSelector('[data-mock-id="appr-m-quote-text"]')
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

test.describe('Approbation mobile — parité visuelle', () => {
  test('mobile Approbation vs MobileApproval.dc.html @ 390×844', async ({ page }, testInfo) => {
    await prepApproval(page, MOBILE_OPTS)
    await page.setViewportSize({ width: 390, height: 844 })

    await page.goto(`${MOCK}/MobileApproval.dc.html`)
    await pinMockupRoot(page)
    await settleFonts(page)
    await maskGlyphs(page)
    await page.waitForTimeout(100)
    const clip = { x: 0, y: 0, width: 390, height: 844 }
    const mockShot = await page.screenshot({ fullPage: false, clip })

    await page.goto(URL)
    await page.waitForSelector('[data-mock-id="appr-m-quote-text"]')
    await page.evaluate(() => {
      document.body.style.margin = '0'
    })
    await settleFonts(page)
    await maskGlyphs(page)
    await page.waitForTimeout(100)
    const appShot = await page.screenshot({ fullPage: false, clip })

    await testInfo.attach('maquette-approval-mobile', { body: mockShot, contentType: 'image/png' })
    await testInfo.attach('app-approval-mobile', { body: appShot, contentType: 'image/png' })
    const ratio = diffRatio(mockShot, appShot, 'approval-mobile')
    expect(ratio, `mobile approval diff ${ratio}`).toBeLessThanOrEqual(0.01)
  })
})

test.describe('Approbation mobile — structure', () => {
  const exceptions = {
    'appr-m-chain': { skip: ['text'], reason: 'Conteneur — étapes comparées séparément' },
    'appr-m-diff-link': { skip: ['text'], reason: 'Conteneur — libellé comparé séparément' },
    'appr-m-quote': { skip: ['text'], reason: 'Conteneur — paragraphe comparé séparément' },
    'appr-m-reject': { skip: ['fontFamily', 'box'], reason: 'M5 : police système (maquette) vs IBM Plex' },
    'appr-m-approve': { skip: ['fontFamily', 'box'], reason: 'M5 : police système (maquette) vs IBM Plex' },
    'appr-m-bar': { skip: ['text', 'box'], reason: 'M5 : hauteur induite par la police des boutons' },
  }

  test('mobile Approbation structural match', async ({ page }, testInfo) => {
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
    await testInfo.attach('structural-approval-mobile.json', {
      body: Buffer.from(JSON.stringify(results, null, 2)),
      contentType: 'application/json',
    })
    const failures = results.filter((r) => r.diffs.length)
    expect(failures, JSON.stringify(failures, null, 2)).toEqual([])
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
