// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
/**
 * Règles : docs/visual-parity.md
 *
 * /spaces vs Spaces.dc.html @ 1440×900 (desktop uniquement).
 *
 * Pixel honnête (texte inclus, ≤ 1 % par section) ; seul exclu : `mask` Playwright nommé.
 * Annotation maquette : data-* uniquement (aucune modification de style / DOM).
 *
 * Mesure avant polish : `VISUAL_BASELINE=1 npx playwright test e2e-visual/spaces-visual.spec.mjs`
 * (journalise tous les % / tailles, écrit test-results/spaces-before.json, n'échoue pas).
 */
import { test, expect } from '@playwright/test'
import { collectMetrics, compareMetrics } from './structural-compare.mjs'
import { ME_TAREK, NOTIFICATIONS_SEED, VISUAL_NOW } from './dashboard-fixtures.mjs'
import { SPACES_SEED } from './five-screens-fixtures.mjs'
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
export const NOT_IMPLEMENTED = []

/**
 * Exceptions de taille déclarées (Δ mesurés) : la maquette n'a pas de sidebar (grille 1344 px) ;
 * l'app garde le shell (sidebar 268 px) → grille 1076 px. Contenu ancré à gauche → crop TL.
 */
const SIZE_EXCEPTIONS = {
  'spaces-title': {
    dw: -268,
    dh: 0,
    reason:
      'docs/visual-parity.md § Décisions produit — Spaces : shell avec sidebar conservé ; 1076 px vs 1344 px maquette',
  },
  'spaces-stats': {
    dw: -268,
    dh: 0,
    reason:
      'docs/visual-parity.md § Décisions produit — Spaces : shell avec sidebar conservé ; 1076 px vs 1344 px maquette',
  },
  // Grille app : 3 colonnes de 346 px (1076 px de contenu, colonnes arrondies au pixel entier) ; la maquette fait 436 px.
  // Δ mesuré, déclaré carte par carte — même décision produit (sidebar).
  ...Object.fromEntries(
    [
      [0, -90],
      [1, -90],
      [2, -90],
      [3, -90],
      [4, -90],
    ].map(([i, dw]) => [
      `spaces-card-${i}`,
      {
        dw,
        dh: 0,
        reason:
          'docs/visual-parity.md § Décisions produit — Spaces : shell avec sidebar conservé ; carte ≈347 px vs 436 px maquette',
      },
    ]),
  ),
}

/** [section pixel] id data-mock-id ↔ nom, masques nommés. */
const PIXEL_SECTIONS = [
  { id: 'spaces-title', name: 'title', masks: [] },
  { id: 'spaces-stats', name: 'stats', masks: [] },
  ...[0, 1, 2, 3, 4].map((i) => ({
    id: `spaces-card-${i}`,
    name: `card-${i}`,
    masks: [],
  })),
  { id: 'spaces-new-icon', name: 'new-icon', masks: [] },
  { id: 'spaces-new-title', name: 'new-title', masks: [] },
  { id: 'spaces-new-sub', name: 'new-sub', masks: [] },
]

const STRUCTURAL_IDS = [
  'spaces-title',
  'spaces-stats',
  'spaces-grid',
  'spaces-card-0',
  'spaces-card-0-tile',
  'spaces-card-0-name',
  'spaces-card-4',
  'spaces-new-card',
  'spaces-new-title',
  'spaces-new-sub',
]

async function mockApis(page) {
  await mockBaseApis(page, { me: ME_TAREK, notifications: NOTIFICATIONS_SEED, spaces: SPACES_SEED })
}

/** Annotation Spaces.dc.html — data-* uniquement. */
async function annotateSpacesMockup(page) {
  await page.evaluate(() => {
    const h1 = document.querySelector('h1')
    h1?.setAttribute('data-mock-id', 'spaces-title')
    const stats = h1?.nextElementSibling
    stats?.setAttribute('data-mock-id', 'spaces-stats')
    const grid = stats?.nextElementSibling
    grid?.setAttribute('data-mock-id', 'spaces-grid')
    ;[...(grid?.children ?? [])].forEach((c, i) => {
      if (c.classList.contains('new-card')) {
        c.setAttribute('data-mock-id', 'spaces-new-card')
        const [icon, title, sub] = c.children
        icon?.setAttribute('data-mock-id', 'spaces-new-icon')
        title?.setAttribute('data-mock-id', 'spaces-new-title')
        sub?.setAttribute('data-mock-id', 'spaces-new-sub')
        return
      }
      c.setAttribute('data-mock-id', `spaces-card-${i}`)
      c.children[0]?.setAttribute('data-mock-id', `spaces-card-${i}-tile`)
      c.children[1]?.setAttribute('data-mock-id', `spaces-card-${i}-name`)
      c.children[2]?.setAttribute('data-mock-id', `spaces-card-${i}-desc`)
      c.children[3]?.setAttribute('data-mock-id', `spaces-card-${i}-meta`)
    })
  })
}

test.describe('spaces visual', () => {
  test.use({ timezoneId: 'Europe/Paris' })

  test('desktop Spaces — sections pixel ≤ 1 % (texte inclus)', async ({ page }, testInfo) => {
    await injectOidcSession(page, VISUAL_NOW)
    await mockApis(page)
    await page.setViewportSize({ width: 1440, height: 900 })

    await page.goto(`${MOCK_ORIGIN}/Spaces.dc.html`)
    await annotateSpacesMockup(page)
    await settleFonts(page)
    await reportNotImplemented(page, testInfo, NOT_IMPLEMENTED, 'spaces')

    const mockSec = {}
    for (const s of PIXEL_SECTIONS) {
      mockSec[s.name] = await shotSection(page, `[data-mock-id="${s.id}"]`, {
        maskNames: s.masks,
        label: `spaces-sec-${s.name}-mock`,
      })
    }

    await page.goto('/spaces')
    await page.waitForSelector('[data-mock-id="spaces-card-4"]')
    await page.mouse.move(1, 1)
    await settleFonts(page)
    await assertNotImplementedGuards(page, NOT_IMPLEMENTED, 'spaces')

    const compare = compareSectionShots(SIZE_EXCEPTIONS)
    const results = []
    for (const s of PIXEL_SECTIONS) {
      const app = await shotSection(page, `[data-mock-id="${s.id}"]`, {
        maskNames: s.masks,
        label: `spaces-sec-${s.name}-app`,
      })
      results.push(compare(`spaces-${s.name}`, mockSec[s.name].shot, app.shot, testInfo))
    }
    finishPixelResults('spaces', results)
  })

  test('Spaces structural — titre, grille, cartes, carte « Nouvel espace »', async ({ page }) => {
    await injectOidcSession(page, VISUAL_NOW)
    await mockApis(page)
    await page.setViewportSize({ width: 1440, height: 900 })

    await page.goto(`${MOCK_ORIGIN}/Spaces.dc.html`)
    await annotateSpacesMockup(page)
    await settleFonts(page)
    const mockMetrics = await collectMetrics(page, STRUCTURAL_IDS)

    await page.goto('/spaces')
    await page.waitForSelector('[data-mock-id="spaces-card-4"]')
    await settleFonts(page)
    const appMetrics = await collectMetrics(page, STRUCTURAL_IDS)

    const SHELL =
      'AppShell : sidebar 268 px absente de la maquette (barre haute seule) → box.x / box.width décalés, box.y en cascade ; hauteur et typographie comparées.'
    const pageExceptions = {
      'spaces-title': { skip: ['box.x', 'box.y', 'box.width'], reason: SHELL },
      'spaces-stats': {
        skip: ['box.x', 'box.y', 'box.width'],
        reason: SHELL,
      },
      'spaces-grid': {
        skip: ['text', 'box.x', 'box.y', 'box.width', 'box.height'],
        reason: `${SHELL} Hauteur de grille : cartes comparées une à une. Texte : concaténation des cartes (blocs).`,
      },
      'spaces-card-0': {
        skip: ['text', 'box.x', 'box.y', 'box.width'],
        reason: `${SHELL} Texte : concaténation nom/desc/méta ; nom comparé via -name, carte en pixel.`,
      },
      'spaces-card-0-tile': { skip: ['box.x', 'box.y'], reason: SHELL },
      'spaces-card-0-name': { skip: ['box.x', 'box.y', 'box.width'], reason: SHELL },
      'spaces-card-4': {
        skip: ['text', 'box.x', 'box.y', 'box.width'],
        reason: `${SHELL} Texte : concaténation nom/desc/méta ; carte en pixel.`,
      },
      'spaces-new-card': {
        skip: ['text', 'box.x', 'box.y', 'box.width'],
        reason: `${SHELL} Texte comparé via spaces-new-title / spaces-new-sub.`,
      },
      'spaces-new-title': { skip: ['box.x', 'box.y'], reason: SHELL },
      'spaces-new-sub': { skip: ['box.x', 'box.y'], reason: SHELL },
    }
    const results = compareMetrics(mockMetrics, appMetrics, STRUCTURAL_IDS, { pageExceptions })
    expectNoStructuralDiffs(results)
  })

  test('« Nouvel espace » ouvre le formulaire de création', async ({ page }) => {
    await injectOidcSession(page, VISUAL_NOW)
    await mockApis(page)
    await page.goto('/spaces')
    await page.waitForSelector('[data-mock-id="spaces-new-card"]')
    await expect(page.getByRole('button', { name: 'Créer un espace' })).toHaveCount(0)
    await page.locator('[data-mock-id="spaces-new-card"]').click()
    await expect(page.getByRole('button', { name: 'Créer un espace' })).toBeVisible()
  })
})
