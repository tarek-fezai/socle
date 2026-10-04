// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, fireEvent, within, cleanup } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import type { ReactNode } from 'react'

vi.mock('../lib/api', () => ({ api: {} }))
vi.mock('../auth/AuthProvider', () => ({
  useAuth: () => ({
    authenticated: true,
    me: { id: 'u-me', email: 'tarek@example.com', displayName: 'Tarek Fezai', avatarInitials: 'TF' },
  }),
}))

const getDocument = vi.fn()
const listVersions = vi.fn()
const restoreVersion = vi.fn()

vi.mock('../lib/documents', async () => {
  const actual = await vi.importActual<typeof import('../lib/documents')>('../lib/documents')
  return {
    ...actual,
    getDocument: (...args: unknown[]) => getDocument(...args),
    listVersions: (...args: unknown[]) => listVersions(...args),
    restoreVersion: (...args: unknown[]) => restoreVersion(...args),
  }
})
vi.mock('../lib/comments', async () => {
  const actual = await vi.importActual<typeof import('../lib/comments')>('../lib/comments')
  return { ...actual, listComments: vi.fn().mockResolvedValue({ openThreadCount: 3, threads: [] }) }
})
vi.mock('../lib/spaces', async () => {
  const actual = await vi.importActual<typeof import('../lib/spaces')>('../lib/spaces')
  return { ...actual, getSpace: vi.fn().mockResolvedValue({ id: 's1', name: 'Identité & accès' }) }
})
vi.mock('../lib/folders', async () => {
  const actual = await vi.importActual<typeof import('../lib/folders')>('../lib/folders')
  return {
    ...actual,
    getSpaceTree: vi.fn().mockResolvedValue({ spaceId: 's1', spaceName: 'Identité & accès', folders: [], documents: [] }),
  }
})

import { DocumentHistoryPage } from './DocumentHistoryPage'

const DOC = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'
const ME = 'u-me'
const CLAIRE = 'u-claire'

/** Instant exprimé en heure locale : le rendu (fuseau du navigateur) reste déterministe. */
const local = (y: number, m: number, d: number, h: number, min: number) =>
  new Date(y, m - 1, d, h, min).toISOString()

const editorPerms = {
  canEdit: true,
  canPublish: true,
  canManageAccess: false,
  canComment: true,
  canManageAttestations: false,
}
const viewerPerms = { ...editorPerms, canEdit: false, canPublish: false, canComment: false }

function docDetail(over: Record<string, unknown> = {}) {
  return {
    id: DOC,
    spaceId: 's1',
    title: 'Politique de gestion des accès',
    body: {},
    status: 'valide',
    currentVersionNo: 12,
    createdAt: local(2026, 7, 14, 11, 0),
    updatedAt: local(2026, 9, 12, 14, 22),
    permissions: editorPerms,
    ...over,
  }
}

const VERSIONS = {
  items: [
    {
      versionNo: 12,
      authorId: ME,
      authorDisplayName: 'Tarek Fezai',
      authorInitials: 'TF',
      changeSummary: "Clarification du circuit d'approbation N2 et ajout du schéma de refus",
      createdAt: local(2026, 9, 12, 14, 22),
      linesAdded: 18,
      linesRemoved: 4,
      current: true,
    },
    {
      versionNo: 11,
      authorId: CLAIRE,
      authorDisplayName: 'Claire Dubois',
      authorInitials: 'CD',
      changeSummary: "Ajout du tableau des rôles et des périmètres d'accès",
      createdAt: local(2026, 9, 3, 9, 41),
      linesAdded: 32,
      linesRemoved: 0,
      current: false,
    },
    {
      versionNo: 9,
      authorId: null,
      authorDisplayName: 'Système (migration)',
      authorInitials: null,
      changeSummary: "Import initial depuis l'ancien wiki IAM",
      createdAt: local(2026, 7, 14, 11, 0),
      linesAdded: 210,
      linesRemoved: 0,
      current: false,
    },
  ],
  offset: 0,
  limit: 50,
  total: 12,
}

function wrap(ui: ReactNode, path = `/docs/${DOC}/history`) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return (
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/docs/:id/history" element={ui} />
          <Route path="/docs/:id" element={<div>page de lecture</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  )
}

function mockMobile(matches: boolean) {
  window.matchMedia = vi.fn().mockImplementation((query: string) => ({
    matches: matches && query.includes('max-width: 767px'),
    media: query,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
  })) as unknown as typeof window.matchMedia
}

describe('DocumentHistoryPage', () => {
  beforeEach(() => {
    getDocument.mockReset()
    listVersions.mockReset()
    restoreVersion.mockReset()
    getDocument.mockResolvedValue(docDetail())
    listVersions.mockResolvedValue(VERSIONS)
  })

  afterEach(() => {
    cleanup()
    // @ts-expect-error — retour à l'absence de matchMedia (jsdom)
    delete window.matchMedia
  })

  it('affiche la timeline : version, date FR, résumé, auteur, +N/−N, compteur', async () => {
    render(wrap(<DocumentHistoryPage />))

    await waitFor(() => expect(screen.getByText('v12')).toBeTruthy())
    expect(screen.getByRole('heading', { name: 'Historique des versions' })).toBeTruthy()
    expect(screen.getByTestId('history-count').textContent).toBe(
      '12 versions publiées depuis la création du document',
    )
    expect(screen.getByText('12 septembre 2026 · 14:22')).toBeTruthy()
    expect(screen.getByText('3 septembre 2026 · 09:41')).toBeTruthy()
    expect(screen.getByText("Ajout du tableau des rôles et des périmètres d'accès")).toBeTruthy()
    expect(screen.getByText('Claire Dubois')).toBeTruthy()
    expect(screen.getByText('+32')).toBeTruthy()
    expect(screen.getAllByText('\u22120')).toHaveLength(2)
    expect(screen.getByText('+210')).toBeTruthy()
    // v12 est l'utilisateur courant : avatar sombre ; CD teinté
    const row12 = screen.getByTestId('history-row-12')
    expect(within(row12).getByText('TF').className).toContain('hist-avatar--self')
    expect(within(screen.getByTestId('history-row-11')).getByText('CD').className).toContain('hist-avatar--other')
  })

  it('auteur null → « Système (migration) » avec l’engrenage ⚙', async () => {
    render(wrap(<DocumentHistoryPage />))
    const row9 = await screen.findByTestId('history-row-9')
    expect(within(row9).getByText('Système (migration)')).toBeTruthy()
    const gear = within(row9).getByTestId('hist-avatar-system')
    expect(gear.textContent).toBe('⚙')
  })

  it('badge « Actuelle » uniquement sur la version courante', async () => {
    render(wrap(<DocumentHistoryPage />))
    await screen.findByTestId('history-row-12')
    const badges = screen.getAllByTestId('history-current-badge')
    expect(badges).toHaveLength(1)
    expect(badges[0]!.textContent).toBe('Actuelle')
    expect(within(screen.getByTestId('history-row-12')).getByTestId('history-current-badge')).toBeTruthy()
  })

  it('canEdit : Comparer / Restaurer jamais sur la version courante', async () => {
    render(wrap(<DocumentHistoryPage />))
    const row12 = await screen.findByTestId('history-row-12')
    expect(within(row12).queryByText('Comparer')).toBeNull()
    expect(within(row12).queryByRole('button', { name: 'Restaurer' })).toBeNull()
    const row11 = screen.getByTestId('history-row-11')
    expect(within(row11).getByText('Comparer')).toBeTruthy()
    expect(within(row11).getByRole('button', { name: 'Restaurer' })).toBeTruthy()
  })

  it('canEdit : la plus ancienne version n’a pas de « Comparer » (rien à comparer) mais « Restaurer »', async () => {
    // Historique complet (total = éléments chargés) : v9 est la première version.
    listVersions.mockResolvedValue({ ...VERSIONS, total: 3 })
    render(wrap(<DocumentHistoryPage />))
    const row9 = await screen.findByTestId('history-row-9')
    expect(within(row9).queryByText('Comparer')).toBeNull()
    expect(within(row9).getByRole('button', { name: 'Restaurer' })).toBeTruthy()
  })

  it('d’autres pages existent : la dernière ligne chargée peut encore être comparée à v-1', async () => {
    render(wrap(<DocumentHistoryPage />))
    const row9 = await screen.findByTestId('history-row-9')
    expect(within(row9).getByText('Comparer').closest('a')!.getAttribute('href')).toBe(
      `/docs/${DOC}/history/compare?from=8&to=9`,
    )
  })

  it('sans canEdit : aucune action Comparer / Restaurer', async () => {
    getDocument.mockResolvedValue(docDetail({ permissions: viewerPerms }))
    render(wrap(<DocumentHistoryPage />))
    await screen.findByTestId('history-row-11')
    expect(screen.queryByText('Comparer')).toBeNull()
    expect(screen.queryByRole('button', { name: 'Restaurer' })).toBeNull()
    // l'onglet Modifier est aussi masqué
    expect(screen.queryByTestId('tab-edit')).toBeNull()
  })

  it('Comparer ouvre la comparaison contre la version précédente', async () => {
    render(wrap(<DocumentHistoryPage />))
    const row11 = await screen.findByTestId('history-row-11')
    const link = within(row11).getByText('Comparer').closest('a')!
    // v11 est suivie de v9 dans la liste (v10 absente) : comparaison v9 → v11
    expect(link.getAttribute('href')).toBe(`/docs/${DOC}/history/compare?from=9&to=11`)
  })

  it('onglets : Historique actif, pas de duplication de la barre d’onglets', async () => {
    render(wrap(<DocumentHistoryPage />))
    await screen.findByTestId('history-row-12')
    const tabs = screen.getAllByRole('tablist', { name: 'Sections du document' })
    // desktop + barre mobile (masquée en CSS)
    expect(tabs.length).toBeLessThanOrEqual(1)
    const active = screen.getAllByRole('tab', { selected: true })
    expect(active.map((t) => t.textContent)).toEqual(['Historique'])
    await waitFor(() => expect(screen.getByTestId('toggle-comments').textContent).toContain('3'))
  })

  it('Restaurer → modale avec le texte exact de la maquette et les vraies valeurs', async () => {
    render(wrap(<DocumentHistoryPage />))
    const row11 = await screen.findByTestId('history-row-11')
    fireEvent.click(within(row11).getByRole('button', { name: 'Restaurer' }))

    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByRole('heading', { name: 'Restaurer la v11 ?' })).toBeTruthy()
    const text = dialog.textContent ?? ''
    expect(text).toContain(
      'Le contenu de la v11 (3 septembre 2026 · 09:41, Claire Dubois) deviendra la nouvelle version courante du document, publiée sous le numéro v13.',
    )
    expect(text).toContain(
      "La version actuelle (v12) n'est pas perdue : elle reste consultable et comparable dans l'historique.",
    )
    expect(text).toContain(
      "Les modifications propres à la v12 (clarification du circuit d'approbation N2 et ajout du schéma de refus) ne seront plus reflétées dans le contenu affiché.",
    )
    expect(within(dialog).getByRole('button', { name: 'Annuler' })).toBeTruthy()
    expect(within(dialog).getByRole('button', { name: 'Restaurer cette version' })).toBeTruthy()
    // statut valide : avertissement de repassage en revue
    expect(within(dialog).getByTestId('restore-status-note').textContent).toMatch(/en_revue/)
  })

  it('modale : résumé absent → « sans résumé »', async () => {
    listVersions.mockResolvedValue({
      ...VERSIONS,
      items: VERSIONS.items.map((v) => (v.versionNo === 12 ? { ...v, changeSummary: null } : v)),
    })
    render(wrap(<DocumentHistoryPage />))
    fireEvent.click(within(await screen.findByTestId('history-row-11')).getByRole('button', { name: 'Restaurer' }))
    const dialog = await screen.findByRole('dialog')
    expect(dialog.textContent).toContain('Les modifications propres à la v12 (sans résumé) ne seront plus reflétées')
  })

  it('Annuler ferme la modale sans appel API', async () => {
    render(wrap(<DocumentHistoryPage />))
    fireEvent.click(within(await screen.findByTestId('history-row-11')).getByRole('button', { name: 'Restaurer' }))
    fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Annuler' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(restoreVersion).not.toHaveBeenCalled()
  })

  it('confirmer la restauration appelle l’API avec expectedVersionNo puis affiche le statut', async () => {
    restoreVersion.mockResolvedValue(docDetail({ status: 'en_revue', currentVersionNo: 13 }))
    render(wrap(<DocumentHistoryPage />))
    fireEvent.click(within(await screen.findByTestId('history-row-11')).getByRole('button', { name: 'Restaurer' }))
    fireEvent.click(within(await screen.findByRole('dialog')).getByTestId('restore-confirm'))

    await waitFor(() => expect(restoreVersion).toHaveBeenCalledWith(expect.anything(), DOC, 11, 12))
    await waitFor(() => expect(screen.getByTestId('history-status-note').textContent).toMatch(/statut en_revue/))
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('échec de la restauration : message dans la modale (409 explicite)', async () => {
    restoreVersion.mockRejectedValue({
      isAxiosError: true,
      response: {
        status: 409,
        data: {
          status: 409,
          detail: "Document en cours d'édition par Bob : restauration impossible",
          code: 'edit_lock_held',
        },
      },
    })
    render(wrap(<DocumentHistoryPage />))
    fireEvent.click(within(await screen.findByTestId('history-row-11')).getByRole('button', { name: 'Restaurer' }))
    fireEvent.click(within(await screen.findByRole('dialog')).getByTestId('restore-confirm'))
    await waitFor(() => expect(screen.getByTestId('restore-error')).toBeTruthy())
    expect(screen.getByTestId('restore-error').textContent).toContain("édition par Bob")
    expect(screen.getByRole('dialog')).toBeTruthy()
  })

  it('échec de la restauration : content_invalid affiche le chemin du nœud', async () => {
    restoreVersion.mockRejectedValue({
      isAxiosError: true,
      response: {
        status: 400,
        data: {
          status: 400,
          detail: '$.content[2].attrs.type: attribut non autorisé : type',
          code: 'content_invalid',
        },
      },
    })
    render(wrap(<DocumentHistoryPage />))
    fireEvent.click(within(await screen.findByTestId('history-row-11')).getByRole('button', { name: 'Restaurer' }))
    fireEvent.click(within(await screen.findByRole('dialog')).getByTestId('restore-confirm'))
    await waitFor(() => expect(screen.getByTestId('restore-error')).toBeTruthy())
    expect(screen.getByTestId('restore-error').textContent).toContain('$.content[2].attrs.type')
    expect(screen.getByTestId('restore-error').textContent).toContain('attribut non autorisé')
    expect(screen.getByRole('dialog')).toBeTruthy()
  })

  it('document archivé → Restaurer désactivé, aucun appel API', async () => {
    getDocument.mockResolvedValue(docDetail({ status: 'archive' }))
    render(wrap(<DocumentHistoryPage />))
    await screen.findByTestId('history-archived')
    const buttons = await screen.findAllByRole('button', { name: 'Restaurer' })
    expect(buttons.length).toBeGreaterThan(0)
    for (const b of buttons) expect((b as HTMLButtonElement).disabled).toBe(true)
    fireEvent.click(buttons[0]!)
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(restoreVersion).not.toHaveBeenCalled()
  })

  it('document de 45 versions : première ligne = courante avec badge, résumé et compteurs', async () => {
    const items = Array.from({ length: 20 }, (_, i) => {
      const versionNo = 45 - i
      return {
        versionNo,
        authorId: ME,
        authorDisplayName: 'Tarek Fezai',
        authorInitials: 'TF',
        changeSummary: versionNo === 45 ? 'Résumé de la version courante' : `Résumé v${versionNo}`,
        createdAt: local(2026, 9, 12, 14, 22),
        linesAdded: versionNo === 45 ? 18 : 2,
        linesRemoved: versionNo === 45 ? 4 : 1,
        current: versionNo === 45,
      }
    })
    listVersions.mockResolvedValue({ items, offset: 0, limit: 50, total: 45 })
    getDocument.mockResolvedValue(docDetail({ currentVersionNo: 45 }))
    render(wrap(<DocumentHistoryPage />))

    const first = await screen.findByTestId('history-row-45')
    expect(within(first).getByTestId('history-current-badge')).toBeTruthy()
    expect(within(first).getByText('Résumé de la version courante')).toBeTruthy()
    expect(within(first).getByText('+18')).toBeTruthy()
    expect(within(first).getByText('\u22124')).toBeTruthy()
    expect(screen.getByTestId('history-count').textContent).toBe(
      '45 versions publiées depuis la création du document',
    )
    // Ordre serveur : la courante est bien la première ligne de la liste.
    const rows = screen.getAllByTestId(/history-row-/)
    expect(rows[0]).toBe(first)
  })

  it('pagination : « Afficher les versions précédentes » charge la page suivante', async () => {
    listVersions
      .mockResolvedValueOnce({ ...VERSIONS, items: VERSIONS.items.slice(0, 2), total: 3 })
      .mockResolvedValueOnce({ ...VERSIONS, items: VERSIONS.items.slice(2), offset: 2, total: 3 })
    render(wrap(<DocumentHistoryPage />))
    fireEvent.click(await screen.findByTestId('history-more'))
    await screen.findByTestId('history-row-9')
    expect(listVersions).toHaveBeenLastCalledWith(expect.anything(), DOC, { offset: 2, limit: 50 })
    expect(screen.queryByTestId('history-more')).toBeNull()
  })

  it('mobile : liste compacte, « Voir les changements → », « Restaurer cette version »', async () => {
    mockMobile(true)
    render(wrap(<DocumentHistoryPage />))
    const row11 = await screen.findByTestId('history-row-11')
    expect(within(row11).getByText('Claire Dubois')).toBeTruthy()
    expect(within(row11).getByText('· v11')).toBeTruthy()
    expect(within(row11).getByText('3 septembre 2026 à 09:41')).toBeTruthy()
    expect(within(row11).getByText('Voir les changements →').closest('a')!.getAttribute('href')).toBe(
      `/docs/${DOC}/history/compare?from=9&to=11`,
    )
    expect(within(row11).getByRole('button', { name: 'Restaurer cette version' })).toBeTruthy()
    // version courante : « (actuelle) », aucune action
    const row12 = screen.getByTestId('history-row-12')
    expect(within(row12).getByText('· v12 (actuelle)')).toBeTruthy()
    expect(within(row12).queryByText('Voir les changements →')).toBeNull()
    // barre d’onglets du bas partagée, Historique actif
    expect(screen.getByRole('navigation', { name: 'Sections du document' })).toBeTruthy()
  })

  it('403 → message clair, pas de fuite de l’historique', async () => {
    getDocument.mockRejectedValue({ response: { status: 403 } })

    render(wrap(<DocumentHistoryPage />))

    await waitFor(() => {
      expect(screen.getByText(/Accès refusé|n'est pas accessible/i)).toBeTruthy()
    })
    expect(listVersions).not.toHaveBeenCalled()
    expect(screen.queryByText('Ajout du tableau des rôles et des périmètres d\'accès')).toBeNull()
    expect(screen.queryByText('v11')).toBeNull()
  })
})
