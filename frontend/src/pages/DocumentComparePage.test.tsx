// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, fireEvent, within, cleanup } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import type { ReactNode } from 'react'

vi.mock('../lib/api', () => ({ api: {} }))
vi.mock('../auth/AuthProvider', () => ({
  useAuth: () => ({
    authenticated: true,
    me: { id: 'u-me', email: 'tarek@example.com', displayName: 'Tarek Fezai', avatarInitials: 'TF' },
  }),
}))

const getDocument = vi.fn()
const listAllVersions = vi.fn()
const fetchVersionCompare = vi.fn()
const restoreVersion = vi.fn()

vi.mock('../lib/documents', async () => {
  const actual = await vi.importActual<typeof import('../lib/documents')>('../lib/documents')
  return {
    ...actual,
    getDocument: (...args: unknown[]) => getDocument(...args),
    listAllVersions: (...args: unknown[]) => listAllVersions(...args),
    fetchVersionCompare: (...args: unknown[]) => fetchVersionCompare(...args),
    restoreVersion: (...args: unknown[]) => restoreVersion(...args),
  }
})
vi.mock('../lib/comments', async () => {
  const actual = await vi.importActual<typeof import('../lib/comments')>('../lib/comments')
  return { ...actual, listComments: vi.fn().mockResolvedValue({ openThreadCount: 0, threads: [] }) }
})

import { DocumentComparePage } from './DocumentComparePage'

const DOC = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'
const local = (y: number, m: number, d: number, h: number, min: number) =>
  new Date(y, m - 1, d, h, min).toISOString()

const perms = {
  canEdit: true,
  canPublish: true,
  canManageAccess: false,
  canComment: true,
  canManageAttestations: false,
}

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
    permissions: perms,
    ...over,
  }
}

const summary = (n: number, author: string | null, name: string | null, day: number) => ({
  versionNo: n,
  authorId: author,
  authorDisplayName: name,
  authorInitials: null,
  changeSummary: n === 12 ? "Clarification du circuit d'approbation N2" : null,
  createdAt: local(2026, 9, day, 9, 41),
})

const VERSIONS = {
  items: [summary(12, 'u-me', 'Tarek Fezai', 12), summary(11, 'u-claire', 'Claire Dubois', 3), summary(10, 'u-me', 'Tarek Fezai', 1)],
  offset: 0,
  limit: 100,
  total: 3,
}

const COMPARE = {
  documentId: DOC,
  fromVersion: 11,
  toVersion: 12,
  added: 18,
  removed: 4,
  hunks: [
    {
      header: '1. Principe du moindre privilège',
      collapsedUnchanged: 0,
      lines: [
        { kind: 'context', oldNo: 1, newNo: 1, text: 'Tout accès accordé doit être strictement' },
        {
          kind: 'del',
          oldNo: 2,
          newNo: null,
          text: 'par le responsable hiérarchique ou un membre',
          spans: [
            { kind: 'eq', text: 'par le responsable hiérarchique ' },
            { kind: 'del', text: 'ou un membre' },
          ],
        },
        {
          kind: 'add',
          oldNo: null,
          newNo: 2,
          text: 'par le responsable hiérarchique et le propriétaire',
          spans: [
            { kind: 'eq', text: 'par le responsable hiérarchique ' },
            { kind: 'add', text: 'et le propriétaire' },
          ],
        },
      ],
    },
    {
      header: '2. Rôles et périmètres',
      collapsedUnchanged: 2,
      lines: [
        { kind: 'context', oldNo: 6, newNo: 7, text: 'Les rôles suivants sont soumis à un cycle' },
        { kind: 'context', oldNo: 7, newNo: 8, text: 'de revue formalisé.' },
      ],
    },
  ],
}

function LocationProbe() {
  const loc = useLocation()
  return <div data-testid="loc">{loc.pathname + loc.search}</div>
}

function wrap(ui: ReactNode, path = `/docs/${DOC}/history/compare?from=11&to=12`) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return (
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <LocationProbe />
        <Routes>
          <Route path="/docs/:id/history/compare" element={ui} />
          <Route path="/docs/:id/history" element={<div>liste historique</div>} />
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

describe('DocumentComparePage', () => {
  beforeEach(() => {
    getDocument.mockReset().mockResolvedValue(docDetail())
    listAllVersions.mockReset().mockResolvedValue(VERSIONS)
    fetchVersionCompare.mockReset().mockResolvedValue(COMPARE)
    restoreVersion.mockReset()
    localStorage.clear()
  })

  afterEach(() => {
    cleanup()
    // @ts-expect-error — retour à l'absence de matchMedia (jsdom)
    delete window.matchMedia
  })

  it('charge la comparaison from→to et affiche +N / −N', async () => {
    render(wrap(<DocumentComparePage />))
    await screen.findByTestId('diff-view')
    expect(fetchVersionCompare).toHaveBeenCalledWith(expect.anything(), DOC, 11, 12)
    expect(screen.getByTestId('diff-added').textContent).toBe('+18')
    expect(screen.getByTestId('diff-removed').textContent).toBe('\u22124')
    expect(screen.getByText('Comparer v11 → v12')).toBeTruthy()
  })

  it('sélecteurs Depuis / Vers reflètent l’URL', async () => {
    render(wrap(<DocumentComparePage />))
    await screen.findByTestId('diff-view')
    expect((screen.getByTestId('diff-select-from') as HTMLSelectElement).value).toBe('11')
    expect((screen.getByTestId('diff-select-to') as HTMLSelectElement).value).toBe('12')
    expect(within(screen.getByTestId('diff-select-from')).getAllByRole('option').map((o) => o.textContent)).toEqual([
      'v12',
      'v11',
      'v10',
    ])
  })

  it('changer « Depuis » recharge la comparaison et met l’URL à jour', async () => {
    render(wrap(<DocumentComparePage />))
    await screen.findByTestId('diff-view')
    fireEvent.change(screen.getByTestId('diff-select-from'), { target: { value: '10' } })
    await waitFor(() => expect(fetchVersionCompare).toHaveBeenLastCalledWith(expect.anything(), DOC, 10, 12))
    expect(screen.getByTestId('loc').textContent).toContain('from=10')
  })

  it('sans paramètres : « Vers » = version courante, « Depuis » = sa précédente', async () => {
    render(wrap(<DocumentComparePage />, `/docs/${DOC}/history/compare`))
    await screen.findByTestId('diff-view')
    expect(fetchVersionCompare).toHaveBeenCalledWith(expect.anything(), DOC, 11, 12)
  })

  it('même version des deux côtés → message, pas d’appel API', async () => {
    render(wrap(<DocumentComparePage />, `/docs/${DOC}/history/compare?from=12&to=12`))
    await screen.findByTestId('diff-same')
    expect(fetchVersionCompare).not.toHaveBeenCalled()
  })

  it('côte à côte par défaut : lignes appariées, vide hachuré, mots en évidence', async () => {
    render(wrap(<DocumentComparePage />))
    const view = await screen.findByTestId('diff-view')
    expect(view.getAttribute('data-mode')).toBe('side')
    expect(screen.getByTestId('diff-mode-side').getAttribute('aria-pressed')).toBe('true')
    const del = view.querySelector('.diff-side--old.is-del')!
    expect(del.querySelector('strong')!.textContent).toBe('ou un membre')
    const add = view.querySelector('.diff-side--new.is-add')!
    expect(add.querySelector('strong')!.textContent).toBe('et le propriétaire')
    expect(del.querySelector('.diff-ln')!.textContent).toBe('2')
  })

  it('bascule Côte à côte / Unifié en état React (aucun stockage navigateur)', async () => {
    const setItem = vi.spyOn(Storage.prototype, 'setItem')
    render(wrap(<DocumentComparePage />))
    const view = await screen.findByTestId('diff-view')

    fireEvent.click(screen.getByTestId('diff-mode-unified'))
    expect(view.getAttribute('data-mode')).toBe('unified')
    expect(screen.getByTestId('diff-mode-unified').getAttribute('aria-pressed')).toBe('true')
    expect(view.querySelector('.diff-row--unified.is-del .diff-sign')!.textContent).toBe('\u2212')
    expect(view.querySelector('.diff-row--unified.is-add .diff-sign')!.textContent).toBe('+')
    expect(view.querySelector('.diff-side')).toBeNull()

    fireEvent.click(screen.getByTestId('diff-mode-side'))
    expect(view.getAttribute('data-mode')).toBe('side')
    expect(setItem).not.toHaveBeenCalled()
    expect(localStorage.length).toBe(0)
    setItem.mockRestore()
  })

  it('« N lignes inchangées » : replié par défaut, dépliable', async () => {
    render(wrap(<DocumentComparePage />))
    const view = await screen.findByTestId('diff-view')
    const headers = within(view).getAllByTestId('diff-hunk').map((h) => h.textContent)
    expect(headers).toEqual([
      '@@ 1. Principe du moindre privilège @@',
      '@@ 2. Rôles et périmètres — 2 lignes inchangées @@',
    ])
    const toggle = within(view).getByTestId('diff-collapsed')
    expect(toggle.textContent).toContain('2 lignes inchangées')
    expect(toggle.getAttribute('aria-expanded')).toBe('false')
    expect(screen.queryByText('de revue formalisé.')).toBeNull()

    fireEvent.click(toggle)
    // la ligne inchangée apparaît des deux côtés
    expect(screen.getAllByText('de revue formalisé.')).toHaveLength(2)
    expect(within(view).queryByTestId('diff-collapsed')).toBeNull()
  })

  it('les lignes inchangées du premier bloc (collapsedUnchanged = 0) restent visibles', async () => {
    render(wrap(<DocumentComparePage />))
    expect(await screen.findAllByText('Tout accès accordé doit être strictement')).toHaveLength(2)
  })

  it('CTA « Restaurer v11 » (canEdit, from ≠ courante) ouvre la modale puis restaure', async () => {
    restoreVersion.mockResolvedValue(docDetail({ currentVersionNo: 13 }))
    render(wrap(<DocumentComparePage />))
    await screen.findByTestId('diff-view')
    fireEvent.click(screen.getByTestId('compare-restore'))
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByRole('heading', { name: 'Restaurer la v11 ?' })).toBeTruthy()
    expect(dialog.textContent).toContain('publiée sous le numéro v13')
    fireEvent.click(within(dialog).getByTestId('restore-confirm'))
    await waitFor(() => expect(restoreVersion).toHaveBeenCalledWith(expect.anything(), DOC, 11, 12))
    await screen.findByText('liste historique')
  })

  it('pas de CTA sans canEdit', async () => {
    getDocument.mockResolvedValue(docDetail({ permissions: { ...perms, canEdit: false } }))
    render(wrap(<DocumentComparePage />))
    await screen.findByTestId('diff-view')
    expect(screen.queryByTestId('compare-restore')).toBeNull()
  })

  it('pas de CTA quand « Depuis » est la version courante', async () => {
    render(wrap(<DocumentComparePage />, `/docs/${DOC}/history/compare?from=12&to=10`))
    await screen.findByTestId('diff-view')
    expect(screen.queryByTestId('compare-restore')).toBeNull()
  })

  it('mobile : vue unifiée par défaut, retour vers l’historique', async () => {
    mockMobile(true)
    render(wrap(<DocumentComparePage />))
    const view = await screen.findByTestId('diff-view')
    expect(view.getAttribute('data-mode')).toBe('unified')
    expect(screen.getByRole('link', { name: 'Retour' }).getAttribute('href')).toBe(`/docs/${DOC}/history`)
  })

  describe('blocs repliés par le serveur (lignes absentes, seulement collapsedUnchanged)', () => {
    const ctx = (n: number, text: string) => ({ kind: 'context', oldNo: n, newNo: n, text })
    const FOLDED = {
      ...COMPARE,
      hunks: [
        { header: '', lines: [], collapsedUnchanged: 2 },
        {
          header: '1. Principe',
          collapsedUnchanged: 0,
          lines: [{ kind: 'add', oldNo: null, newNo: 3, text: 'ligne ajoutée' }],
        },
        { header: '1. Principe', lines: [], collapsedUnchanged: 1 },
      ],
    }
    const FULL = {
      ...COMPARE,
      hunks: [
        {
          header: '',
          collapsedUnchanged: 0,
          lines: [
            ctx(1, 'premier inchangé'),
            ctx(2, 'second inchangé'),
            { kind: 'add', oldNo: null, newNo: 3, text: 'ligne ajoutée' },
            ctx(3, 'dernier inchangé'),
          ],
        },
      ],
    }

    it('affiche « N lignes inchangées » sans en-tête ni ligne, puis charge le contexte complet au dépliage', async () => {
      fetchVersionCompare.mockImplementation(
        async (_api: unknown, _id: string, _from: number, _to: number, opts?: { fullContext?: boolean }) =>
          opts?.fullContext ? FULL : FOLDED,
      )
      render(wrap(<DocumentComparePage />))
      const view = await screen.findByTestId('diff-view')
      const buttons = within(view).getAllByTestId('diff-collapsed')
      expect(buttons.map((b) => b.textContent)).toEqual(['▸2 lignes inchangées', '▸1 ligne inchangée'])
      // Les blocs repliés n'ont ni en-tête « @@ » ni ligne ; seul le bloc modifié garde le sien.
      expect(within(view).getAllByTestId('diff-hunk').map((h) => h.textContent)).toEqual(['@@ 1. Principe @@'])
      expect(fetchVersionCompare).toHaveBeenCalledTimes(1)

      fireEvent.click(buttons[0]!)
      await waitFor(() => expect(screen.getAllByText('premier inchangé')).toHaveLength(2))
      expect(screen.getAllByText('second inchangé')).toHaveLength(2)
      expect(fetchVersionCompare).toHaveBeenLastCalledWith(expect.anything(), DOC, 11, 12, { fullContext: true })
      // L'autre bloc replié reste replié et se déplie sans nouvel appel.
      expect(screen.queryByText('dernier inchangé')).toBeNull()
      fireEvent.click(within(view).getByTestId('diff-collapsed'))
      expect(screen.getAllByText('dernier inchangé')).toHaveLength(2)
      expect(fetchVersionCompare).toHaveBeenCalledTimes(2)
    })

    it('serveur sans contexte complet (blocs toujours vides) → message, pas de ligne inventée', async () => {
      fetchVersionCompare.mockResolvedValue(FOLDED)
      render(wrap(<DocumentComparePage />))
      const view = await screen.findByTestId('diff-view')
      fireEvent.click(within(view).getAllByTestId('diff-collapsed')[0]!)
      const note = await screen.findByTestId('diff-folded-unavailable')
      expect(note.textContent).toContain('2 lignes inchangées')
    })

    it('échec du chargement du contexte → message d’erreur', async () => {
      fetchVersionCompare.mockImplementation(
        async (_a: unknown, _i: string, _f: number, _t: number, opts?: { fullContext?: boolean }) => {
          if (opts?.fullContext) throw new Error('boom')
          return FOLDED
        },
      )
      render(wrap(<DocumentComparePage />))
      const view = await screen.findByTestId('diff-view')
      fireEvent.click(within(view).getAllByTestId('diff-collapsed')[0]!)
      await screen.findByTestId('diff-folded-error')
    })
  })

  it('comparaison vide → message', async () => {
    fetchVersionCompare.mockResolvedValue({ ...COMPARE, added: 0, removed: 0, hunks: [] })
    render(wrap(<DocumentComparePage />))
    await screen.findByTestId('diff-empty')
  })
})
