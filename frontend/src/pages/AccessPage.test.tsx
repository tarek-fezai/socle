import { describe, expect, it, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import type { ReactNode } from 'react'

vi.mock('../lib/api', () => ({ api: {} }))

const listAccess = vi.fn()
const grantAccess = vi.fn()
const revokeAccess = vi.fn()

vi.mock('../lib/access', async () => {
  const actual = await vi.importActual<typeof import('../lib/access')>('../lib/access')
  return {
    ...actual,
    listAccess: (...args: unknown[]) => listAccess(...args),
    grantAccess: (...args: unknown[]) => grantAccess(...args),
    revokeAccess: (...args: unknown[]) => revokeAccess(...args),
  }
})

vi.mock('../lib/groups', () => ({
  listGroups: () =>
    Promise.resolve([
      {
        id: '22222222-2222-2222-2222-222222222222',
        name: 'Équipe sécu',
        description: null,
        createdAt: '2026-01-01T00:00:00Z',
        memberCount: 2,
      },
    ]),
}))

const { getDocument, updateDocumentVisibility } = vi.hoisted(() => ({
  getDocument: vi.fn(),
  updateDocumentVisibility: vi.fn(),
}))

vi.mock('../lib/documents', () => ({
  getDocument: (...args: unknown[]) => getDocument(...args),
  updateDocumentVisibility: (...args: unknown[]) => updateDocumentVisibility(...args),
}))

import { AccessPage } from './AccessPage'

const SPACE = '00000000-0000-0000-0000-000000000001'
const DOC = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'

function wrap(ui: ReactNode) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return (
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[`/spaces/${SPACE}/access`]}>
        <Routes>
          <Route path="/spaces/:spaceId/access" element={ui} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  )
}

describe('AccessPage', () => {
  beforeEach(() => {
    listAccess.mockReset()
    grantAccess.mockReset()
    revokeAccess.mockReset()
    getDocument.mockReset()
    updateDocumentVisibility.mockReset()
  })

  it('affiche direct vs hérité depuis les données API', async () => {
    listAccess.mockResolvedValue({
      objectType: 'space',
      objectId: SPACE,
      canManage: true,
      entries: [
        {
          subject: 'user:11111111-1111-1111-1111-111111111111',
          subjectType: 'user',
          subjectId: '11111111-1111-1111-1111-111111111111',
          relation: 'owner',
          source: 'direct',
          inheritedFrom: null,
          revocable: true,
        },
        {
          subject: 'group:22222222-2222-2222-2222-222222222222#member',
          subjectType: 'group',
          subjectId: '22222222-2222-2222-2222-222222222222',
          relation: 'editor',
          source: 'group',
          inheritedFrom: null,
          revocable: true,
        },
        {
          subject: 'user:33333333-3333-3333-3333-333333333333',
          subjectType: 'user',
          subjectId: '33333333-3333-3333-3333-333333333333',
          relation: 'viewer',
          source: 'inherited',
          inheritedFrom: 'organisation:00000000-0000-0000-0000-000000000010',
          revocable: false,
        },
      ],
    })

    render(wrap(<AccessPage objectType="space" />))

    await waitFor(() => {
      expect(screen.getByText('Direct')).toBeTruthy()
      expect(screen.getByText('Via groupe')).toBeTruthy()
      expect(screen.getByText('Hérité')).toBeTruthy()
    })
    expect(screen.getByText(/Hérité de/)).toBeTruthy()
    expect(screen.getByText('Non révocable ici')).toBeTruthy()
  })

  it('après grant réussi, invalide et recharge la liste (pas d’optimistic)', async () => {
    listAccess
      .mockResolvedValueOnce({
        objectType: 'space',
        objectId: SPACE,
        canManage: true,
        entries: [],
      })
      .mockResolvedValueOnce({
        objectType: 'space',
        objectId: SPACE,
        canManage: true,
        entries: [
          {
            subject: 'user:aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
            subjectType: 'user',
            subjectId: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
            relation: 'editor',
            source: 'direct',
            inheritedFrom: null,
            revocable: true,
          },
        ],
      })
    grantAccess.mockResolvedValue(undefined)

    render(wrap(<AccessPage objectType="space" />))
    await waitFor(() => expect(screen.getByText(/Aucun accès/)).toBeTruthy())

    fireEvent.change(screen.getByPlaceholderText(/id utilisateur/i), {
      target: { value: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Accorder' }))

    await waitFor(() => {
      expect(grantAccess).toHaveBeenCalledWith(
        expect.anything(),
        'space',
        SPACE,
        expect.objectContaining({
          relation: 'editor',
          subjectType: 'user',
          subjectId: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
        }),
      )
    })
    await waitFor(() => expect(listAccess).toHaveBeenCalledTimes(2))
    await waitFor(() => expect(screen.getByText('Direct')).toBeTruthy())
  })

  it('affiche une erreur claire sur 503 sans mettre à jour la liste', async () => {
    listAccess.mockResolvedValue({
      objectType: 'space',
      objectId: SPACE,
      canManage: true,
      entries: [],
    })
    grantAccess.mockRejectedValue({
      response: { status: 503, data: { message: 'audit down' } },
    })

    render(wrap(<AccessPage objectType="space" />))
    await waitFor(() => expect(screen.getByText(/Aucun accès/)).toBeTruthy())

    fireEvent.change(screen.getByPlaceholderText(/id utilisateur/i), {
      target: { value: 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Accorder' }))

    await waitFor(() => expect(screen.getByRole('alert').textContent).toMatch(/audit down/i))
    expect(listAccess).toHaveBeenCalledTimes(1)
    expect(screen.queryByText('Direct')).toBeNull()
  })

  it('affiche le bloc Visibilité sur un document — owner peut changer', async () => {
    listAccess.mockResolvedValue({
      objectType: 'document',
      objectId: DOC,
      canManage: true,
      entries: [],
    })
    getDocument.mockResolvedValue({
      id: DOC,
      spaceId: SPACE,
      title: 'Page',
      body: { type: 'doc' },
      status: 'brouillon',
      createdAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:00:00Z',
      visibility: 'organisation',
    })
    updateDocumentVisibility.mockResolvedValue({
      id: DOC,
      spaceId: SPACE,
      title: 'Page',
      body: { type: 'doc' },
      status: 'brouillon',
      createdAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:00:00Z',
      visibility: 'restricted',
    })

    const client = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    render(
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={[`/documents/${DOC}/access`]}>
          <Routes>
            <Route path="/documents/:documentId/access" element={<AccessPage />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    )

    await waitFor(() => expect(screen.getByText('Tous les utilisateurs')).toBeTruthy())
    expect(screen.getByText("Membres de l'espace")).toBeTruthy()
    expect(screen.getByText('Personnes et équipes listées')).toBeTruthy()

    fireEvent.click(screen.getByTestId('visibility-restricted'))
    await waitFor(() =>
      expect(updateDocumentVisibility).toHaveBeenCalledWith(
        expect.anything(),
        DOC,
        'restricted',
      ),
    )
  })
})