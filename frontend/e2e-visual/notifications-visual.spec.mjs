// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
/**
 * Règles : docs/visual-parity.md
 *
 * /notifications vs Notifications.dc.html @ 1440×900 (desktop uniquement).
 *
 * La maquette montre 8 notifications (types non produits par le backend, auteurs, extraits,
 * boutons Accepter/Décliner…). L'app (types réels : approval_chain_exhausted, comment_mention,
 * pat_expiring, external_reference_first) est comparée ÉLÉMENT PAR ÉLÉMENT sur les parties
 * dérivables des données : fil d'Ariane, « Préférences → », titre, titres de groupe, pastille de
 * non-lu, pastille d'icône (type approbation), libellé de temps relatif. Correspondance
 * maquette → app : items 0, 1, 4, 5 de la maquette ↔ items 0..3 de l'app.
 *
 * Pixel honnête (texte inclus, ≤ 1 % par section) ; seul exclu : `mask` Playwright nommé.
 * Annotation maquette : data-* uniquement.
 *
 * Mesure avant polish : `VISUAL_BASELINE=1 npx playwright test e2e-visual/notifications-visual.spec.mjs`
 * (journalise tous les % / tailles, écrit test-results/notifications-before.json, n'échoue pas).
 */
import { test, expect } from '@playwright/test'
import { collectMetrics, compareMetrics } from './structural-compare.mjs'
import { ME_TAREK, VISUAL_NOW } from './dashboard-fixtures.mjs'
import { NOTIFICATIONS_LIST_SEED } from './five-screens-fixtures.mjs'
import {
  MOCK_ORIGIN,
  assertNotImplementedGuards,
  compareSectionShots,
  expectNoStructuralDiffs,
  finishPixelResults,
  injectOidcSession,
  mockBaseApis,
  reportNotImplemented,
  settleFonts,
  shotSection,
} from './visual-parity-kit.mjs'

/**
 * Zones maquette sans donnée produit — NON comparées, jamais « passées ».
 * @type {import('./visual-parity-kit.mjs').NotImplementedZone[]}
 */
export const NOT_IMPLEMENTED = [
  {
    id: 'notif-read-all',
    page: 'notifications',
    reason: '« Tout marquer comme lu » : aucune API de marquage en masse — bouton désactivé dans l’app',
    backlog: 'NOTIF-READ-ALL',
    mockMask: 'notif-read-all',
    appPlaceholderSelector: '[data-visual-mask="notif-read-all"][disabled]',
  },
  {
    id: 'notif-item-text',
    page: 'notifications',
    reason:
      'Phrase de la notification (auteur en gras, titre du document, version) : le backend ne fournit ni acteur ni phrase structurée — l’app affiche formatNotificationMessage()',
    backlog: 'NOTIF-ACTOR-RICH-TEXT',
    mockMask: 'notif-item-text',
    appImplementedProbe: 'text=/Claire Dubois|Yanis M\\./',
  },
  {
    id: 'notif-item-extra',
    page: 'notifications',
    reason: 'Ligne complémentaire (« SLA 48h — 22h restantes », extrait de commentaire) : absente des charges utiles',
    backlog: 'NOTIF-EXTRA-LINE',
    mockMask: 'notif-item-extra',
    appImplementedProbe: 'text=/SLA 48h/',
  },
  {
    id: 'notif-item-icon-actor',
    page: 'notifications',
    reason:
      'Pastille « initiales de l’auteur » / coche « publié » : pas d’acteur dans l’API — l’app affiche l’icône du type de notification',
    backlog: 'NOTIF-ACTOR-AVATAR',
    mockMask: 'notif-item-icon',
    appPlaceholderSelector: '[data-visual-mask="notif-item-icon"]',
  },
  {
    id: 'notif-unproduced-rows',
    page: 'notifications',
    reason:
      'Notifications de la maquette sans type backend (proposition de modification, tag ajouté, restauration, retrait d’approbateur, invitation avec Accepter/Décliner) + groupe « Cette semaine » : non rendues',
    backlog: 'NOTIF-TYPES',
    mockMask: 'notif-unproduced-rows',
    appImplementedProbe: 'text=/Accepter|Décliner/',
  },
]

/** Maquette (index de ligne) → app (index de ligne). */
const MOCK_TO_APP = { 0: 0, 1: 1, 4: 2, 5: 3 }

const SIZE_EXCEPTIONS = {}

const APP_ITEMS = [0, 1, 2, 3]

const PIXEL_SECTIONS = [
  { id: 'notif-breadcrumb', name: 'breadcrumb', masks: [] },
  { id: 'notif-prefs', name: 'prefs', masks: [] },
  { id: 'notif-title', name: 'title', masks: [] },
  { id: 'notif-read-all', name: 'read-all', masks: ['notif-read-all'] },
  { id: 'notif-group-0', name: 'group-0', masks: [] },
  { id: 'notif-group-1', name: 'group-1', masks: [] },
  { id: 'notif-item-0-dot', name: 'item-0-dot', masks: [] },
  { id: 'notif-item-1-dot', name: 'item-1-dot', masks: [] },
  { id: 'notif-item-0-icon', name: 'item-0-icon', masks: [] },
  ...[1, 2, 3].map((i) => ({
    id: `notif-item-${i}-icon`,
    name: `item-${i}-icon`,
    masks: ['notif-item-icon'],
  })),
  ...APP_ITEMS.map((i) => ({ id: `notif-item-${i}-time`, name: `item-${i}-time`, masks: [] })),
]

const STRUCTURAL_IDS = [
  'notif-breadcrumb',
  'notif-prefs',
  'notif-title',
  'notif-group-0',
  'notif-group-1',
  'notif-item-0-icon',
  'notif-item-0-time',
  'notif-item-2-time',
]

async function mockApis(page) {
  await mockBaseApis(page, {
    me: ME_TAREK,
    notifications: NOTIFICATIONS_LIST_SEED,
    spaces: [],
  })
}

/** Annotation Notifications.dc.html — data-* uniquement. */
async function annotateNotificationsMockup(page, mockToApp) {
  await page.evaluate((map) => {
    const prefs = document.querySelector('a[href="Account.dc.html"]')
    prefs?.setAttribute('data-mock-id', 'notif-prefs')
    prefs?.parentElement?.firstElementChild?.setAttribute('data-mock-id', 'notif-breadcrumb')
    document.querySelector('h1')?.setAttribute('data-mock-id', 'notif-title')
    document.querySelector('span.link')?.setAttribute('data-mock-id', 'notif-read-all')
    document.querySelector('span.link')?.setAttribute('data-visual-mask', 'notif-read-all')

    const leaf = (txt) =>
      [...document.querySelectorAll('div')].find(
        (d) => d.children.length === 0 && d.textContent.trim() === txt,
      )
    leaf("Aujourd'hui")?.setAttribute('data-mock-id', 'notif-group-0')
    leaf('Hier')?.setAttribute('data-mock-id', 'notif-group-1')
    const week = leaf('Cette semaine')
    week?.setAttribute('data-visual-mask', 'notif-unproduced-rows')

    const items = [...document.querySelectorAll('.item')]
    items.forEach((item, m) => {
      const i = map[m]
      if (i === undefined) {
        item.setAttribute('data-visual-mask', 'notif-unproduced-rows')
        return
      }
      item.setAttribute('data-mock-id', `notif-item-${i}`)
      item.querySelector(':scope > span')?.setAttribute('data-mock-id', `notif-item-${i}-dot`)
      const [icon, content] = [...item.children].filter((c) => c.tagName === 'DIV')
      icon.setAttribute('data-mock-id', `notif-item-${i}-icon`)
      if (i >= 1) icon.setAttribute('data-visual-mask', 'notif-item-icon')
      const lines = [...content.children]
      lines[0].setAttribute('data-visual-mask', 'notif-item-text')
      if (lines.length === 3) lines[1].setAttribute('data-visual-mask', 'notif-item-extra')
      lines[lines.length - 1].setAttribute('data-mock-id', `notif-item-${i}-time`)
    })
  }, mockToApp)
}

test.describe('notifications visual', () => {
  test.use({ timezoneId: 'Europe/Paris' })

  test('desktop Notifications — sections pixel ≤ 1 % (texte inclus)', async ({ page }, testInfo) => {
    await injectOidcSession(page, VISUAL_NOW)
    await mockApis(page)
    await page.setViewportSize({ width: 1440, height: 900 })

    await page.goto(`${MOCK_ORIGIN}/Notifications.dc.html`)
    await annotateNotificationsMockup(page, MOCK_TO_APP)
    await settleFonts(page)
    await reportNotImplemented(page, testInfo, NOT_IMPLEMENTED, 'notifications')

    const mockSec = {}
    for (const s of PIXEL_SECTIONS) {
      mockSec[s.name] = await shotSection(page, `[data-mock-id="${s.id}"]`, {
        maskNames: s.masks,
        label: `notifications-sec-${s.name}-mock`,
      })
    }

    await page.goto('/notifications')
    await page.waitForSelector('[data-mock-id="notif-item-3"]')
    await page.mouse.move(1, 1)
    await settleFonts(page)
    await assertNotImplementedGuards(page, NOT_IMPLEMENTED, 'notifications')

    const compare = compareSectionShots(SIZE_EXCEPTIONS)
    const results = []
    for (const s of PIXEL_SECTIONS) {
      const app = await shotSection(page, `[data-mock-id="${s.id}"]`, {
        maskNames: s.masks,
        label: `notifications-sec-${s.name}-app`,
      })
      results.push(compare(`notifications-${s.name}`, mockSec[s.name].shot, app.shot, testInfo))
    }
    finishPixelResults('notifications', results)
  })

  test('Notifications structural — fil d’Ariane, titre, groupes, éléments d’item', async ({
    page,
  }) => {
    await injectOidcSession(page, VISUAL_NOW)
    await mockApis(page)
    await page.setViewportSize({ width: 1440, height: 900 })

    await page.goto(`${MOCK_ORIGIN}/Notifications.dc.html`)
    await annotateNotificationsMockup(page, MOCK_TO_APP)
    await settleFonts(page)
    const mockMetrics = await collectMetrics(page, STRUCTURAL_IDS)

    await page.goto('/notifications')
    await page.waitForSelector('[data-mock-id="notif-item-3"]')
    await settleFonts(page)
    const appMetrics = await collectMetrics(page, STRUCTURAL_IDS)

    const SHELL =
      'AppShell : sidebar 268 px absente de la maquette (barre haute seule) et colonne centrée dans une zone plus étroite → box.x décalé ; box.y en cascade (lignes NOT_IMPLEMENTED intercalées).'
    const pageExceptions = Object.fromEntries(
      STRUCTURAL_IDS.map((id) => [id, { skip: ['box.x', 'box.y'], reason: SHELL }]),
    )
    const results = compareMetrics(mockMetrics, appMetrics, STRUCTURAL_IDS, { pageExceptions })
    expectNoStructuralDiffs(results)
  })

  test('« Tout marquer comme lu » est désactivé ; « Préférences → » mène à /account', async ({
    page,
  }) => {
    await injectOidcSession(page, VISUAL_NOW)
    await mockApis(page)
    await page.goto('/notifications')
    await page.waitForSelector('[data-mock-id="notif-item-0"]')
    await expect(page.getByRole('button', { name: 'Tout marquer comme lu' })).toBeDisabled()
    await expect(page.getByRole('link', { name: 'Préférences →' })).toHaveAttribute('href', '/account')
  })
})
