// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
/**
 * Règles : docs/visual-parity.md
 *
 * /docs/new vs NewDocument.dc.html @ 1440×900 (desktop uniquement).
 *
 * Formulaire unique des deux côtés (aucun assistant) : fil d'Ariane / fermer / titre / intro,
 * en-tête « Modèle » + 5 cartes, « Espace » (liste native), « Emplacement dans l'arborescence »
 * (liste native), champ « Titre du document », « Tags (optionnel) », « Annuler » + CTA.
 *
 * Pixel honnête (texte inclus, ≤ 1 % par section) ; seul exclu : `mask` Playwright nommé.
 * Annotation maquette : data-* uniquement. « Fiabilité cible » et la mention de visibilité du
 * brouillon restent masquées (NOT_IMPLEMENTED) ; emplacement et tags sont comparés.
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
      'Champ « Fiabilité cible » (Revue annuelle obligatoire…) : pas de paramètre de fiabilité à la création (createDocument) — l’app affiche un champ désactivé « Bientôt »',
    backlog: 'NEWDOC-TARGET-RELIABILITY',
    mockMask: 'newdoc-fiabilite',
    appPlaceholderSelector: '[data-visual-mask="newdoc-fiabilite"]',
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

/** Arbre de l'espace avec deux dossiers racine : l'aide sous la liste cite « (Procédures, Référence…) ». */
const NEWDOC_TREE_SPEC = {
  ...NEWDOC_TREE,
  folders: [
    ...NEWDOC_TREE.folders,
    {
      id: 'f0000001-0000-4000-8000-000000000002',
      name: 'Référence',
      parentFolderId: null,
      position: 1,
      documentCount: 0,
      folderCount: 0,
      documents: [],
    },
  ],
}

/** Étiquette présente dans la maquette (puce « IAM »). */
const MOCK_TAG = 'IAM'
const TAGS_SEED = [{ id: 'g0000001-0000-4000-8000-000000000001', name: MOCK_TAG }]

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

/** withTitle : le CTA est comparé avec un titre saisi (maquette : bouton actif). */
const PIXEL_SECTIONS = [
  { id: 'newdoc-breadcrumb', name: 'breadcrumb' },
  { id: 'newdoc-close', name: 'close' },
  { id: 'newdoc-title', name: 'title' },
  { id: 'newdoc-lead', name: 'lead' },
  { id: 'newdoc-models-head', name: 'models-head' },
  ...TPL_SLUGS.map((s) => ({ id: `newdoc-tpl-${s}`, name: `tpl-${s}` })),
  { id: 'newdoc-space-select', name: 'space-select' },
  { id: 'newdoc-folder-select', name: 'folder-select' },
  { id: 'newdoc-title-field', name: 'title-field' },
  { id: 'newdoc-tags', name: 'tags', withTag: true },
  { id: 'newdoc-cancel', name: 'cancel' },
  { id: 'newdoc-cta', name: 'cta', withTitle: true },
]

const STRUCTURAL_IDS = [
  'newdoc-breadcrumb',
  'newdoc-title',
  'newdoc-lead',
  'newdoc-models-head',
  'newdoc-tpl-politique',
  'newdoc-tpl-fiche-fournisseur-externe',
  'newdoc-space-select',
  'newdoc-folder-select',
  'newdoc-title-field',
  'newdoc-tags',
]

async function mockApis(page) {
  await mockBaseApis(page, { me: ME_TAREK, notifications: NOTIFICATIONS_SEED, spaces: SPACES_SEED })
  await page.route(/\/api\/v1\/spaces\/[^/]+\/tree/, (route) => json(route, NEWDOC_TREE_SPEC))
  await page.route('**/api/v1/templates**', (route) => {
    if (route.request().method() !== 'GET') return route.fallback()
    if (route.request().url().includes('creation-warnings')) return json(route, { warnings: [] })
    return json(route, TEMPLATES_SEED)
  })
  await page.route('**/api/v1/tags**', (route) => {
    if (route.request().method() !== 'GET') return route.fallback()
    return json(route, TAGS_SEED)
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
    // Espace et emplacement : comparés (listes natives côté app). Fiabilité cible : NOT_IMPLEMENTED.
    selects?.children[0]?.setAttribute('data-mock-id', 'newdoc-space-select')
    selects?.children[1]?.setAttribute('data-visual-mask', 'newdoc-fiabilite')
    location?.setAttribute('data-mock-id', 'newdoc-folder-select')
    titleBlock?.setAttribute('data-mock-id', 'newdoc-title-field')
    tags?.setAttribute('data-mock-id', 'newdoc-tags')
    footer?.children[0]?.setAttribute('data-visual-mask', 'newdoc-footer-note')
    const [ghost, cta] = [...(footer?.children[1]?.children ?? [])]
    ghost?.setAttribute('data-mock-id', 'newdoc-cancel')
    cta?.setAttribute('data-mock-id', 'newdoc-cta')
  })
}

/** Page app : modèle « Politique » sélectionné et puce « IAM » ajoutée, comme dans la maquette. */
async function prepareApp(page) {
  await page.goto(`/docs/new?spaceId=${SPACE_IDENTITE.id}`)
  await page.waitForSelector('[data-mock-id="newdoc-tpl-fiche-fournisseur-externe"]')
  await expect(page.getByLabel('Espace')).toHaveValue(SPACE_IDENTITE.id)
  await page.locator('[data-mock-id="newdoc-tpl-politique"]').click()
  await expect(page.getByRole('radio', { name: /Politique/ })).toBeChecked()
}

async function addMockTag(page) {
  const input = page.locator('#newdoc-tags-input')
  await input.fill(MOCK_TAG)
  await input.press('Enter')
  await expect(page.getByRole('button', { name: `Retirer le tag ${MOCK_TAG}` })).toBeVisible()
  await input.blur()
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

    await prepareApp(page)
    await settleFonts(page)
    await assertNotImplementedGuards(page, NOT_IMPLEMENTED, 'new-document')

    const compare = compareSectionShots(SIZE_EXCEPTIONS)
    const results = []
    const title = page.getByLabel('Titre du document')
    let tagAdded = false
    for (const s of PIXEL_SECTIONS) {
      if (s.withTag && !tagAdded) {
        await addMockTag(page)
        tagAdded = true
      }
      if (s.withTitle) {
        // CTA activé (titre saisi) comme dans la maquette.
        await title.fill('Politique de classification')
        await expect(page.locator('[data-mock-id="newdoc-cta"]')).toBeEnabled()
      } else {
        await title.fill('')
        await title.blur()
      }
      await page.mouse.move(1, 1)
      const app = await shotSection(page, `[data-mock-id="${s.id}"]`, {
        label: `new-document-sec-${s.name}-app`,
      })
      results.push(compare(`new-document-${s.name}`, mockSec[s.name].shot, app.shot, testInfo))
    }
    finishPixelResults('new-document', results)
  })

  test('Nouveau document structural — en-tête, cartes, listes, champ titre, tags', async ({
    page,
  }) => {
    await injectOidcSession(page, VISUAL_NOW)
    await mockApis(page)
    await page.setViewportSize({ width: 1440, height: 900 })

    await page.goto(`${MOCK_ORIGIN}/NewDocument.dc.html`)
    await annotateNewDocMockup(page)
    await settleFonts(page)
    const mockMetrics = await collectMetrics(page, STRUCTURAL_IDS)

    await prepareApp(page)
    await addMockTag(page)
    await page.mouse.move(1, 1)
    await settleFonts(page)
    const appMetrics = await collectMetrics(page, STRUCTURAL_IDS)

    const FLOW =
      'AppShell : colonne centrée dans la zone à droite de la sidebar 268 px (box.x décalé de 134 px) ; ordre des cartes propre à l’app (box.y / box.x des cartes non comparables) ; typographie et largeur comparées.'
    const CONTAINER_TEXT =
      'text : conteneur — l’app n’insère pas d’espace entre blocs (textContent concaténé) ; le texte est vérifié par la comparaison pixel (texte inclus).'
    const BTN_LH =
      'lineHeight : <button> (app) épinglé à 1 par collectMetrics, <div> (maquette) à 1.2.'
    const FIELD_TEXT =
      'text : maquette = libellé + faux champ ; app = libellé + <select>/<input> natif (options / placeholder hors textContent) — vérifié par pixel.'
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
      'newdoc-space-select': {
        skip: ['text', 'box.x', 'box.y'],
        reason: `${FLOW} ${FIELD_TEXT}`,
      },
      'newdoc-folder-select': {
        skip: ['text', 'box.x', 'box.y'],
        reason: `${FLOW} ${FIELD_TEXT}`,
      },
      'newdoc-title-field': {
        skip: ['text', 'box.x', 'box.y'],
        reason: `${FLOW} text : maquette = libellé + faux champ (placeholder) ; app = libellé + <input> (placeholder hors textContent) — vérifié par pixel.`,
      },
      'newdoc-tags': {
        skip: ['text', 'box.x', 'box.y'],
        reason: `${FLOW} ${FIELD_TEXT}`,
      },
    }
    const results = compareMetrics(mockMetrics, appMetrics, STRUCTURAL_IDS, { pageExceptions })
    expectNoStructuralDiffs(results)
  })

  test('formulaire unique : modèle, espace, dossier, titre, tag → création + rattachement', async ({
    page,
  }) => {
    await injectOidcSession(page, VISUAL_NOW)
    await mockApis(page)
    const DOC_ID = 'd0000001-0000-4000-8000-000000000001'
    const posts = []
    await page.route('**/api/v1/documents', (route) => {
      if (route.request().method() !== 'POST') return route.fallback()
      posts.push({ url: route.request().url(), body: route.request().postDataJSON() })
      return json(route, { id: DOC_ID, title: 'Politique de test', spaceId: SPACE_IDENTITE.id }, 201)
    })
    await page.route(`**/api/v1/documents/${DOC_ID}/tags`, (route) => {
      posts.push({ url: route.request().url(), body: route.request().postDataJSON() })
      return json(route, { id: TAGS_SEED[0].id, name: MOCK_TAG }, 201)
    })

    await page.goto('/docs/new')
    // Un seul formulaire : aucun assistant.
    await expect(page.getByRole('button', { name: 'Continuer' })).toHaveCount(0)
    await expect(page.getByRole('radio', { name: /Document vierge/ })).toBeChecked()

    // Les modèles (spécifiques à l'espace) se chargent à la sélection de l'espace.
    await expect(page.getByLabel('Espace').locator(`option[value="${SPACE_IDENTITE.id}"]`)).toHaveCount(1)
    await page.getByLabel('Espace').selectOption(SPACE_IDENTITE.id)
    await expect(page.getByRole('radio', { name: /Politique/ })).toBeVisible()
    await page.getByRole('radio', { name: /Politique/ }).click()
    await expect(page.getByRole('radio', { name: /Politique/ })).toBeChecked()
    await page.getByLabel("Emplacement dans l'arborescence").selectOption({ label: 'Procédures' })
    await page.getByLabel('Titre du document').fill('Politique de test')

    const tagInput = page.locator('#newdoc-tags-input')
    await tagInput.focus()
    await page.getByRole('option', { name: MOCK_TAG }).getByRole('button').click()
    await expect(page.getByRole('button', { name: `Retirer le tag ${MOCK_TAG}` })).toBeVisible()

    await page.getByRole('button', { name: 'Créer le document' }).click()
    await expect.poll(() => posts.length).toBe(2)
    expect(posts[0].body).toMatchObject({
      title: 'Politique de test',
      spaceId: SPACE_IDENTITE.id,
      folderId: NEWDOC_TREE_SPEC.folders[0].id,
    })
    expect(posts[1].url).toContain(`/documents/${DOC_ID}/tags`)
    expect(posts[1].body).toEqual({ tagId: TAGS_SEED[0].id })
  })
})
