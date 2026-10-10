// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
/**
 * Règles : docs/visual-parity.md
 *
 * /docs/new vs NewDocument.dc.html @ 1440×900 (desktop uniquement).
 *
 * La maquette est un formulaire unique ; l'app est un assistant en 3 étapes (emplacement →
 * modèle → titre). Chaque section de la maquette est donc comparée à son équivalent sur l'étape
 * où il apparaît : fil d'Ariane / fermer / titre / intro / « Annuler » (étape 1), en-tête
 * « Modèle » + 5 cartes de modèle (étape 2), champ « Titre du document » + CTA (étape 3).
 *
 * Pixel honnête (texte inclus, ≤ 1 % par section) ; seul exclu : `mask` Playwright nommé.
 * Annotation maquette : data-* uniquement.
 *
 * Mesure avant polish : `VISUAL_BASELINE=1 npx playwright test e2e-visual/new-document-visual.spec.mjs`
 * (journalise tous les % / tailles, écrit test-results/new-document-before.json, n'échoue pas).
 */
import { test, expect } from '@playwright/test'
import { collectMetrics, compareMetrics } from './structural-compare.mjs'
import { ME_TAREK, NOTIFICATIONS_SEED, SPACE_IDENTITE, VISUAL_NOW } from './dashboard-fixtures.mjs'
import { NEWDOC_TREE, SPACES_SEED, TEMPLATES_SEED } from './five-screens-fixtures.mjs'
import {
  MOCK_ORIGIN,
  assertNotImplementedGuards,
  compareSectionShots,
  expectNoStructuralDiffs,
  finishPixelResults,
  injectOidcSession,
  json,
  mockBaseApis,
  reportNotImplemented,
  settleFonts,
  shotSection,
} from './visual-parity-kit.mjs'

/**
 * Zones maquette sans équivalent app — NON comparées, jamais « passées ».
 * @type {import('./visual-parity-kit.mjs').NotImplementedZone[]}
 */
export const NOT_IMPLEMENTED = [
  {
    id: 'newdoc-fiabilite',
    page: 'new-document',
    reason:
      'Champ « Fiabilité cible » (Revue annuelle obligatoire…) : pas de paramètre de fiabilité à la création (createDocument)',
    backlog: 'NEWDOC-TARGET-RELIABILITY',
    mockMask: 'newdoc-fiabilite',
    appImplementedProbe: 'text=/Fiabilité cible/',
  },
  {
    id: 'newdoc-tags',
    page: 'new-document',
    reason: 'Champ « Tags (optionnel) » : la création ne prend pas de tags (ajout après création)',
    backlog: 'NEWDOC-TAGS',
    mockMask: 'newdoc-tags',
    appImplementedProbe: 'text=/Ajouter un tag existant/',
  },
  {
    id: 'newdoc-location-selects',
    page: 'new-document',
    reason:
      'Sélecteurs « Espace » et « Emplacement dans l’arborescence » en liste déroulante sur la même page : l’app les présente en étape 1 (listes à choix) — mise en page différente par conception',
    backlog: 'NEWDOC-SINGLE-PAGE-FORM',
    mockMask: 'newdoc-location-selects',
  },
  {
    id: 'newdoc-footer-note',
    page: 'new-document',
    reason:
      'Mention « visible uniquement par vous jusqu’à publication » : règle de visibilité des brouillons non garantie par le backend — l’app affiche « Le document sera créé en brouillon. »',
    backlog: 'NEWDOC-DRAFT-VISIBILITY-COPY',
    mockMask: 'newdoc-footer-note',
    appImplementedProbe: 'text=/visible uniquement par vous/',
  },
]

const SIZE_EXCEPTIONS = {}

/**
 * Anti-crénelage : le texte blanc sur fond accent du bouton « Créer le document » est rendu
 * en niveaux de gris dans l'app (bouton désactivé→activé, calque composité) et en LCD dans la
 * maquette. Même rendu des deux côtés (--disable-lcd-text), uniquement pour ce fichier ;
 * le texte reste comparé pixel à pixel.
 */
test.use({ launchOptions: { args: ['--disable-lcd-text'] } })

/** Cartes de modèle : slug du nom (data-mock-id `newdoc-tpl-<slug>`). */
const TPL_SLUGS = [
  'politique',
  'procedure',
  'guide-utilisateur',
  'document-vierge',
  'fiche-fournisseur-externe',
]

/** step : étape de l'assistant sur laquelle la section apparaît dans l'app. */
const PIXEL_SECTIONS = [
  { id: 'newdoc-breadcrumb', name: 'breadcrumb', step: 1 },
  { id: 'newdoc-close', name: 'close', step: 1 },
  { id: 'newdoc-title', name: 'title', step: 1 },
  { id: 'newdoc-lead', name: 'lead', step: 1 },
  { id: 'newdoc-models-head', name: 'models-head', step: 2 },
  ...TPL_SLUGS.map((s) => ({ id: `newdoc-tpl-${s}`, name: `tpl-${s}`, step: 2 })),
  { id: 'newdoc-cancel', name: 'cancel', step: 3 },
  { id: 'newdoc-cta', name: 'cta', step: 3, withTitle: true },
  { id: 'newdoc-title-field', name: 'title-field', step: 3 },
]

const STRUCTURAL_IDS = [
  'newdoc-breadcrumb',
  'newdoc-title',
  'newdoc-lead',
  'newdoc-models-head',
  'newdoc-tpl-politique',
  'newdoc-tpl-fiche-fournisseur-externe',
  'newdoc-title-field',
]

async function mockApis(page) {
  await mockBaseApis(page, { me: ME_TAREK, notifications: NOTIFICATIONS_SEED, spaces: SPACES_SEED })
  await page.route(/\/api\/v1\/spaces\/[^/]+\/tree/, (route) => json(route, NEWDOC_TREE))
  await page.route('**/api/v1/templates**', (route) => {
    if (route.request().method() !== 'GET') return route.fallback()
    if (route.request().url().includes('creation-warnings')) return json(route, { warnings: [] })
    return json(route, TEMPLATES_SEED)
  })
}

/** Annotation NewDocument.dc.html — data-* uniquement. */
async function annotateNewDocMockup(page) {
  await page.evaluate(() => {
    const slug = (s) =>
      s
        .normalize('NFD')
        .replace(/[\u0300-\u036f]/g, '')
        .toLowerCase()
        .replace(/[^a-z0-9]+/g, '-')
        .replace(/^-|-$/g, '')
    const close = document.querySelector('a[aria-label="Fermer"]')
    close?.setAttribute('data-mock-id', 'newdoc-close')
    close?.parentElement?.firstElementChild?.setAttribute('data-mock-id', 'newdoc-breadcrumb')
    const h1 = document.querySelector('h1')
    h1?.setAttribute('data-mock-id', 'newdoc-title')
    h1?.nextElementSibling?.setAttribute('data-mock-id', 'newdoc-lead')

    // Colonne 760 px : [h1, p, models-head, grid, row(Espace|Fiabilité), emplacement, titre, tags, footer]
    const col = h1?.parentElement
    const [, , modelsHead, grid, selects, location, titleBlock, tags, footer] = [
      ...(col?.children ?? []),
    ]
    modelsHead?.setAttribute('data-mock-id', 'newdoc-models-head')
    ;[...(grid?.children ?? [])].forEach((card) => {
      const nameBlock = card.children[1]
      const nameEl = nameBlock?.querySelector('span') ?? nameBlock
      card.setAttribute('data-mock-id', `newdoc-tpl-${slug(nameEl?.textContent ?? '')}`)
    })
    // Espace (colonne 1) + Emplacement : sélecteurs remplacés par l'étape 1 (NOT_IMPLEMENTED).
    selects?.children[0]?.setAttribute('data-visual-mask', 'newdoc-location-selects')
    selects?.children[1]?.setAttribute('data-visual-mask', 'newdoc-fiabilite')
    location?.setAttribute('data-visual-mask', 'newdoc-location-selects')
    titleBlock?.setAttribute('data-mock-id', 'newdoc-title-field')
    tags?.setAttribute('data-visual-mask', 'newdoc-tags')
    footer?.children[0]?.setAttribute('data-visual-mask', 'newdoc-footer-note')
    const [ghost, cta] = [...(footer?.children[1]?.children ?? [])]
    ghost?.setAttribute('data-mock-id', 'newdoc-cancel')
    cta?.setAttribute('data-mock-id', 'newdoc-cta')
  })
}

/** Amène l'assistant (app) à l'étape demandée ; Politique sélectionné en étape 2. */
async function gotoStep(page, step, state) {
  if (state.step === step) return
  if (step >= 2 && state.step < 2) {
    await page.getByRole('button', { name: 'Continuer' }).click()
    await page.waitForSelector('[data-mock-id="newdoc-tpl-fiche-fournisseur-externe"]')
    await page.locator('[data-mock-id="newdoc-tpl-politique"]').click()
    state.step = 2
  }
  if (step >= 3 && state.step < 3) {
    await page.getByRole('button', { name: 'Continuer' }).click()
    await page.waitForSelector('[data-mock-id="newdoc-title-field"]')
    state.step = 3
  }
  await page.mouse.move(1, 1)
  await settleFonts(page)
}

test.describe('new document visual', () => {
  test.use({ timezoneId: 'Europe/Paris' })

  test('desktop Nouveau document — sections pixel ≤ 1 % (texte inclus)', async ({
    page,
  }, testInfo) => {
    await injectOidcSession(page, VISUAL_NOW)
    await mockApis(page)
    await page.setViewportSize({ width: 1440, height: 900 })

    await page.goto(`${MOCK_ORIGIN}/NewDocument.dc.html`)
    await annotateNewDocMockup(page)
    await settleFonts(page)
    await reportNotImplemented(page, testInfo, NOT_IMPLEMENTED, 'new-document')

    const mockSec = {}
    for (const s of PIXEL_SECTIONS) {
      mockSec[s.name] = await shotSection(page, `[data-mock-id="${s.id}"]`, {
        label: `new-document-sec-${s.name}-mock`,
      })
    }

    await page.goto(`/docs/new?spaceId=${SPACE_IDENTITE.id}`)
    await page.waitForSelector('[data-testid="step-1"]')
    await page.waitForSelector('[role="radio"][aria-checked="true"]')
    await settleFonts(page)
    await assertNotImplementedGuards(page, NOT_IMPLEMENTED, 'new-document')

    const compare = compareSectionShots(SIZE_EXCEPTIONS)
    const results = []
    const state = { step: 1 }
    for (const s of PIXEL_SECTIONS) {
      await gotoStep(page, s.step, state)
      if (s.withTitle) {
        // CTA activé (titre saisi) comme dans la maquette ; le champ est vidé ensuite.
        await page.getByLabel('Titre du document').fill('Politique de classification')
        await expect(page.locator('[data-mock-id="newdoc-cta"]')).toBeEnabled()
        await page.mouse.move(1, 1)
      } else if (s.step === 3) {
        await page.getByLabel('Titre du document').fill('')
        await page.getByLabel('Titre du document').blur()
        await page.mouse.move(1, 1)
      }
      const app = await shotSection(page, `[data-mock-id="${s.id}"]`, {
        label: `new-document-sec-${s.name}-app`,
      })
      results.push(compare(`new-document-${s.name}`, mockSec[s.name].shot, app.shot, testInfo))
    }
    finishPixelResults('new-document', results)
  })

  test('Nouveau document structural — en-tête, cartes, champ titre', async ({ page }) => {
    await injectOidcSession(page, VISUAL_NOW)
    await mockApis(page)
    await page.setViewportSize({ width: 1440, height: 900 })

    await page.goto(`${MOCK_ORIGIN}/NewDocument.dc.html`)
    await annotateNewDocMockup(page)
    await settleFonts(page)
    const mockMetrics = await collectMetrics(page, STRUCTURAL_IDS)

    await page.goto(`/docs/new?spaceId=${SPACE_IDENTITE.id}`)
    await page.waitForSelector('[data-testid="step-1"]')
    await page.waitForSelector('[role="radio"][aria-checked="true"]')
    const state = { step: 1 }
    const appMetrics = {}
    const stepOf = (id) => PIXEL_SECTIONS.find((s) => s.id === id)?.step ?? 1
    for (const step of [1, 2, 3]) {
      await gotoStep(page, step, state)
      const ids = STRUCTURAL_IDS.filter((id) => stepOf(id) === step)
      if (ids.length) Object.assign(appMetrics, await collectMetrics(page, ids))
    }

    const FLOW =
      'AppShell : colonne centrée dans la zone à droite de la sidebar 268 px (box.x décalé de 134 px) ; assistant 3 étapes et ordre des cartes propres à l’app (box.y / box.x des cartes non comparables) ; typographie et largeur comparées.'
    const CONTAINER_TEXT =
      'text : conteneur — l’app n’insère pas d’espace entre blocs (textContent concaténé) ; le texte est vérifié par la comparaison pixel (texte inclus).'
    const BTN_LH =
      'lineHeight : <button> (app) épinglé à 1 par collectMetrics, <div> (maquette) à 1.2.'
    const pageExceptions = {
      'newdoc-breadcrumb': { skip: ['box.x', 'box.y'], reason: FLOW },
      'newdoc-title': { skip: ['box.x', 'box.y'], reason: FLOW },
      'newdoc-lead': { skip: ['box.x', 'box.y'], reason: FLOW },
      'newdoc-models-head': {
        skip: ['text', 'box.x', 'box.y'],
        reason: `${FLOW} ${CONTAINER_TEXT}`,
      },
      'newdoc-tpl-politique': {
        skip: ['text', 'lineHeight', 'box.x', 'box.y'],
        reason: `${FLOW} ${CONTAINER_TEXT} ${BTN_LH}`,
      },
      'newdoc-tpl-fiche-fournisseur-externe': {
        skip: ['text', 'lineHeight', 'box.x', 'box.y'],
        reason: `${FLOW} ${CONTAINER_TEXT} ${BTN_LH}`,
      },
      'newdoc-title-field': {
        skip: ['text', 'box.x', 'box.y'],
        reason: `${FLOW} text : maquette = libellé + faux champ (placeholder) ; app = libellé + <input> (placeholder hors textContent) — vérifié par pixel.`,
      },
    }
    const results = compareMetrics(mockMetrics, appMetrics, STRUCTURAL_IDS, { pageExceptions })
    expectNoStructuralDiffs(results)
  })

  test('assistant : Document vierge / modèle → titre → création', async ({ page }) => {
    await injectOidcSession(page, VISUAL_NOW)
    await mockApis(page)
    await page.goto(`/docs/new?spaceId=${SPACE_IDENTITE.id}`)
    await page.waitForSelector('[data-testid="step-1"]')
    await page.getByRole('button', { name: 'Continuer' }).click()
    await expect(page.getByRole('radio', { name: /Document vierge/ })).toBeChecked()
    await page.getByRole('radio', { name: /Politique/ }).click()
    await page.getByRole('button', { name: 'Continuer' }).click()
    await expect(page.getByTestId('recap-template')).toHaveText('Politique')
  })
})
