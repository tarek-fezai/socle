// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
/**
 * Règles : docs/visual-parity.md
 *
 * Access.dc.html est un écran document (onglets Lire/Modifier/… + visibilité + personnes).
 * Route comparable : /documents/:id/access (pas /spaces/:id/access — ACL espace, autre UI).
 * Pas de variante dossier/tag dans la maquette.
 *
 * Mesure : `VISUAL_BASELINE=1 npx playwright test e2e-visual/access-visual.spec.mjs`
 */
import { test } from '@playwright/test'
import { VISUAL_NOW } from './dashboard-fixtures.mjs'
import { PAGE_DOC_ID } from './parity-screens-fixtures.mjs'
import { annotateAccessMockup, mockParityApis } from './parity-screens-kit.mjs'
import {
  MOCK_ORIGIN,
  assertNotImplementedGuards,
  compareSectionShots,
  finishPixelResults,
  injectOidcSession,
  reportNotImplemented,
  settleFonts,
  shotSection,
} from './visual-parity-kit.mjs'

export const NOT_IMPLEMENTED = [
  {
    id: 'access-doc-tabs',
    page: 'access',
    reason:
      'Chrome document (onglets Lire / Modifier / Historique / Commentaires / Accès) : AccessPage est une route isolée, pas DocumentChrome',
    backlog: 'ACCESS-DOC-CHROME',
    mockMask: 'access-doc-tabs',
  },
  {
    id: 'access-invite',
    page: 'access',
    reason: 'Bouton « + Inviter » : pas d’API d’invitation nominative (grant = UUID)',
    backlog: 'ACCESS-INVITE',
    mockMask: 'access-invite',
  },
  {
    id: 'access-people-rows',
    page: 'access',
    reason:
      'Lignes personnes : AccessEntry n’a ni displayName, ni initiales, ni last-active, ni rôle « Approbatrice »',
    backlog: 'ACCESS-ENTRY-META',
    mockMask: 'access-people-rows',
  },
  {
    id: 'access-delete-box',
    page: 'access',
    reason: 'Encart « Déplacer ce document vers la corbeille » absent de AccessPage',
    backlog: 'ACCESS-DELETE-BOX',
    mockMask: 'access-delete-box',
  },
]

const SIZE_EXCEPTIONS = {}

const PIXEL_SECTIONS = [
  { id: 'access-title', name: 'title', masks: [] },
  { id: 'access-vis-label', name: 'vis-label', masks: [] },
  { id: 'access-vis-organisation', name: 'vis-organisation', masks: [] },
  { id: 'access-vis-space', name: 'vis-space', masks: [] },
  { id: 'access-vis-restricted', name: 'vis-restricted', masks: [] },
  { id: 'access-people-label', name: 'people-label', masks: [] },
]

test.describe('access visual', () => {
  test.use({ timezoneId: 'Europe/Paris' })

  test('desktop Access document — sections pixel ≤ 1 % (texte inclus)', async ({ page }, testInfo) => {
    await injectOidcSession(page, VISUAL_NOW)
    await mockParityApis(page)
    await page.setViewportSize({ width: 1440, height: 900 })

    await page.goto(`${MOCK_ORIGIN}/Access.dc.html`)
    await annotateAccessMockup(page)
    await settleFonts(page)
    await reportNotImplemented(page, testInfo, NOT_IMPLEMENTED, 'access')

    const mockSec = {}
    for (const s of PIXEL_SECTIONS) {
      mockSec[s.name] = await shotSection(page, `[data-mock-id="${s.id}"]`, {
        maskNames: s.masks,
        label: `access-sec-${s.name}-mock`,
      })
    }

    await page.goto(`/documents/${PAGE_DOC_ID}/access`)
    await page.waitForSelector('[data-mock-id="access-vis-restricted"]')
    await page.mouse.move(1, 1)
    await settleFonts(page)
    await assertNotImplementedGuards(page, NOT_IMPLEMENTED, 'access')

    const compare = compareSectionShots(SIZE_EXCEPTIONS)
    const results = []
    for (const s of PIXEL_SECTIONS) {
      const app = await shotSection(page, `[data-mock-id="${s.id}"]`, {
        maskNames: s.masks,
        label: `access-sec-${s.name}-app`,
      })
      results.push(compare(`access-${s.name}`, mockSec[s.name].shot, app.shot, testInfo))
    }
    finishPixelResults('access', results)
  })
})
