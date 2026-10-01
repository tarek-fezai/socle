// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import type { ReactNode } from 'react'

vi.mock('../lib/api', () => ({ api: {} }))

const getDocument = vi.fn()
const listVersions = vi.fn()
const fetchVersionDiff = vi.fn()
const restoreVersion = vi.fn()

vi.mock('../lib/documents', async () => {
  const actual = await vi.importActual<typeof import('../lib/documents')>('../lib/documents')
  return {
    ...actual,
    getDocument: (...args: unknown[]) => getDocument(...args),
    listVersions: (...args: unknown[]) => listVersions(...args),
    fetchVersionDiff: (...args: unknown[]) => fetchVersionDiff(...args),
    restoreVersion: (...args: unknown[]) => restoreVersion(...args),
  }
})

import { DocumentHistoryPage } from './DocumentHistoryPage'

const DOC = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'

const versions = {
  items: [
    {
      versionNo: 2,
      authorId: '11111111-1111-1111-1111-111111111111',
      changeSummary: 'Ajout section',
      createdAt: '2026-09-28T10:00:00Z',
    },
    {
      versionNo: 1,
      authorId: '11111111-1111-1111-1111-111111111111',
      changeSummary: null,
      createdAt: '2026-09-27T09:00:00Z',
    },
  ],
  offset: 0,
  limit: 50,
  total: 2,
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
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  )
}

describe('DocumentHistoryPage', () => {
  beforeEach(() => {
    getDocument.mockReset()
    listVersions.mockReset()
    fetchVersionDiff.mockReset()
    restoreVersion.mockReset()
  })

  it('affiche archivé par seulement s\'il diffère de l\'auteur', async () => {
    getDocument.mockResolvedValue({
      id: DOC,
      spaceId: '00000000-0000-0000-0000-000000000001',
      title: 'Politique',
      body: {},
      status: 'brouillon',
      currentVersionNo: 2,
      createdAt: '2026-09-01T00:00:00Z',
      updatedAt: '2026-09-28T10:00:00Z',
    })
    listVersions.mockResolvedValue({
      items: [
        {
          versionNo: 2,
          authorId: '11111111-1111-1111-1111-111111111111',
          archivedBy: '22222222-2222-2222-2222-222222222222',
          changeSummary: 'Soumission pour approbation',
          createdAt: '2026-09-28T10:00:00Z',
        },
        {
          versionNo: 1,
          authorId: '11111111-1111-1111-1111-111111111111',
          archivedBy: '11111111-1111-1111-1111-111111111111',
          changeSummary: 'edit',
          createdAt: '2026-09-27T09:00:00Z',
        },
      ],
      offset: 0,
      limit: 50,
      total: 2,
    })

    render(wrap(<DocumentHistoryPage />))

    await waitFor(() => expect(screen.getByText('Soumission pour approbation')).toBeTruthy())
    expect(screen.getByText(/archivé par 22222222/i)).toBeTruthy()
    // Même auteur / archiveur → pas de second libellé
    expect(screen.getAllByText(/archivé par/i)).toHaveLength(1)
  })

  it('affiche la liste et identifie la version courante', async () => {
    getDocument.mockResolvedValue({
      id: DOC,
      spaceId: '00000000-0000-0000-0000-000000000001',
      title: 'Politique',
      body: {},
      status: 'en_revue',
      currentVersionNo: 2,
      createdAt: '2026-09-01T00:00:00Z',
      updatedAt: '2026-09-28T10:00:00Z',
    })
    listVersions.mockResolvedValue(versions)

    render(wrap(<DocumentHistoryPage />))

    await waitFor(() => expect(screen.getByText('Ajout section')).toBeTruthy())
    expect(screen.getByText('Aucun résumé')).toBeTruthy()
    expect(screen.getAllByText(/version courante/i).length).toBeGreaterThanOrEqual(1)
    expect(screen.getByText((content, el) => el?.tagName === 'SPAN' && content === 'version courante')).toBeTruthy()
  })

  it('sélection de deux versions → diff via composant partagé', async () => {
    getDocument.mockResolvedValue({
      id: DOC,
      spaceId: '00000000-0000-0000-0000-000000000001',
      title: 'Politique',
      body: {},
      status: 'brouillon',
      currentVersionNo: 3,
      createdAt: '2026-09-01T00:00:00Z',
      updatedAt: '2026-09-28T10:00:00Z',
    })
    listVersions.mockResolvedValue(versions)
    fetchVersionDiff.mockResolvedValue({
      documentId: DOC,
      fromVersion: 1,
      toVersion: 2,
      changes: [
        { path: 'content[1]', op: 'added', before: null, after: { type: 'paragraph' } },
        { path: 'content[0].text', op: 'modified', before: 'a', after: 'b' },
      ],
    })

    render(wrap(<DocumentHistoryPage />))
    await waitFor(() => expect(screen.getAllByText('Comparer').length).toBeGreaterThan(0))

    const compareButtons = screen.getAllByRole('button', { name: 'Comparer' })
    fireEvent.click(compareButtons[0])
    fireEvent.click(compareButtons[1])

    await waitFor(() => {
      expect(fetchVersionDiff).toHaveBeenCalledWith(expect.anything(), DOC, 1, 2)
      expect(screen.getByText('[added]')).toBeTruthy()
      expect(screen.getByText('[modified]')).toBeTruthy()
    })
  })

  it('restore sur document valide → avertissement puis statut en_revue', async () => {
    getDocument.mockResolvedValue({
      id: DOC,
      spaceId: '00000000-0000-0000-0000-000000000001',
      title: 'Politique',
      body: {},
      status: 'valide',
      currentVersionNo: 3,
      createdAt: '2026-09-01T00:00:00Z',
      updatedAt: '2026-09-28T10:00:00Z',
    })
    listVersions
      .mockResolvedValueOnce(versions)
      .mockResolvedValueOnce({
        ...versions,
        items: [
          {
            versionNo: 3,
            authorId: '11111111-1111-1111-1111-111111111111',
            changeSummary: 'Avant restore',
            createdAt: '2026-09-28T11:00:00Z',
          },
          ...versions.items,
        ],
        total: 3,
      })
    restoreVersion.mockResolvedValue({
      id: DOC,
      spaceId: '00000000-0000-0000-0000-000000000001',
      title: 'Politique',
      body: {},
      status: 'en_revue',
      currentVersionNo: 4,
      createdAt: '2026-09-01T00:00:00Z',
      updatedAt: '2026-09-28T12:00:00Z',
    })

    render(wrap(<DocumentHistoryPage />))
    await waitFor(() => expect(screen.getAllByText('Restaurer').length).toBeGreaterThan(0))

    fireEvent.click(screen.getAllByRole('button', { name: 'Restaurer' })[0])

    await waitFor(() => {
      expect(screen.getByText(/repasser en/i)).toBeTruthy()
      expect(screen.getByText(/nouvelle approbation/i)).toBeTruthy()
    })

    fireEvent.click(screen.getByRole('button', { name: 'Confirmer la restauration' }))

    await waitFor(() => {
      expect(restoreVersion).toHaveBeenCalledWith(expect.anything(), DOC, 2, 3)
    })
    await waitFor(() => {
      expect(screen.getByText(/statut en_revue/i)).toBeTruthy()
    })
  })

  it('restore sur archive → bouton désactivé, pas d’appel API', async () => {
    getDocument.mockResolvedValue({
      id: DOC,
      spaceId: '00000000-0000-0000-0000-000000000001',
      title: 'Archivé',
      body: {},
      status: 'archive',
      currentVersionNo: 2,
      createdAt: '2026-09-01T00:00:00Z',
      updatedAt: '2026-09-28T10:00:00Z',
    })
    listVersions.mockResolvedValue(versions)

    render(wrap(<DocumentHistoryPage />))
    await waitFor(() => expect(screen.getByText(/Document archivé/i)).toBeTruthy())
    await waitFor(() => expect(screen.getByText('Ajout section')).toBeTruthy())

    const restoreButtons = screen.getAllByRole('button', { name: 'Restaurer' })
    expect(restoreButtons.length).toBeGreaterThan(0)
    for (const btn of restoreButtons) {
      expect((btn as HTMLButtonElement).disabled).toBe(true)
    }
    expect(restoreVersion).not.toHaveBeenCalled()
  })

  it('403 → message clair, pas de fuite de l’historique', async () => {
    getDocument.mockRejectedValue({ response: { status: 403 } })

    render(wrap(<DocumentHistoryPage />))

    await waitFor(() => {
      expect(screen.getByText(/Accès refusé|n'est pas accessible/i)).toBeTruthy()
    })
    expect(listVersions).not.toHaveBeenCalled()
    expect(screen.queryByText('Ajout section')).toBeNull()
    expect(screen.queryByText('v1')).toBeNull()
  })
})
