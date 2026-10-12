// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import type { ReactNode } from 'react'

vi.mock('../lib/api', () => ({
  api: { get: () => Promise.resolve({ data: { instanceName: 'Organisation Démo' } }) },
}))

vi.mock('../lib/spaces', () => ({
  getSpace: () => Promise.resolve({ id: '00000000-0000-0000-0000-000000000001', name: 'Identité & accès' }),
}))

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
        createdBy: null,
        createdAt: '2026-01-01T00:00:00Z',
        memberCount: 2,
        canManage: false,
        members: [],
      },
    ]),
}))

const searchDirectoryUsers = vi.fn()
vi.mock('../lib/directory', () => ({
  searchDirectoryUsers: (...args: unknown[]) => searchDirectoryUsers(...args),
}))

const { getDocument, updateDocumentVisibility } = vi.hoisted(() => ({
  getDocument: vi.fn(),
  updateDocumentVisibility: vi.fn(),
}))

vi.mock('../lib/documents', () => ({
  getDocument: (...args: unknown[]) => getDocument(...args),
  updateDocumentVisibility: (...args: unknown[]) => updateDocumentVisibility(...args),
}))

vi.mock('../lib/trash', () => ({
  softDeleteDocument: vi.fn(),
}))

import { AccessPage } from './AccessPage'

const SPACE = '00000000-0000-0000-0000-000000000001'
const DOC = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'
const USER = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'

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

async function pickPerson(name = 'Alice Martin') {
  fireEvent.click(screen.getByRole('button', { name: '+ Inviter' }))
  const input = screen.getByPlaceholderText('Rechercher un nom…')
  fireEvent.change(input, { target: { value: 'al' } })
  await waitFor(() => expect(screen.getByText('Alice Martin')).toBeTruthy())
  fireEvent.click(screen.getByText('Alice Martin'))
  expect((input as HTMLInputElement).value).toBe(name)
}

describe('AccessPage', () => {
  beforeEach(() => {
    listAccess.mockReset()
    grantAccess.mockReset()
    revokeAccess.mockReset()
    getDocument.mockReset()
    updateDocumentVisibility.mockReset()
    searchDirectoryUsers.mockReset()
    searchDirectoryUsers.mockResolvedValue([
      { id: USER, displayName: 'Alice Martin', email: 'alice@example.com', initials: 'AM' },
    ])
  })

  it('affiche les personnes par nom et rôle, sans UUID', async () => {
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
          displayName: 'Tarek Fezai',
          initials: 'TF',
        },
        {
          subject: 'group:22222222-2222-2222-2222-222222222222#as_editor',
          subjectType: 'group',
          subjectId: '22222222-2222-2222-2222-222222222222',
          relation: 'editor',
          source: 'group',
          inheritedFrom: null,
          revocable: true,
          displayName: 'Équipe sécu',
          initials: '⬡',
          memberCount: 2,
        },
      ],
    })

    render(wrap(<AccessPage objectType="space" />))

    await waitFor(() => expect(screen.getByText('Tarek Fezai')).toBeTruthy())
    expect(screen.getByText('Équipe sécu')).toBeTruthy()
    expect(screen.getByText('Propriétaire')).toBeTruthy()
    expect(screen.getByText('Éditeur')).toBeTruthy()
    expect(screen.queryByPlaceholderText(/Identifiant/i)).toBeNull()
    expect(document.body.textContent).not.toContain('11111111-1111-1111-1111-111111111111')
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
            subject: `user:${USER}`,
            subjectType: 'user',
            subjectId: USER,
            relation: 'editor',
            source: 'direct',
            inheritedFrom: null,
            revocable: true,
            displayName: 'Alice Martin',
            initials: 'AM',
          },
        ],
      })
    grantAccess.mockResolvedValue(undefined)

    render(wrap(<AccessPage objectType="space" />))
    await waitFor(() => expect(screen.getByText(/Aucun accès/)).toBeTruthy())
    await pickPerson()
    fireEvent.click(screen.getByRole('button', { name: 'Accorder' }))

    await waitFor(() => {
      expect(grantAccess).toHaveBeenCalledWith(
        expect.anything(),
        'space',
        SPACE,
        expect.objectContaining({
          relation: 'editor',
          subjectType: 'user',
          subjectId: USER,
        }),
      )
    })
    await waitFor(() => expect(listAccess).toHaveBeenCalledTimes(2))
    await waitFor(() => expect(screen.getByText('Alice Martin')).toBeTruthy())
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
    await pickPerson()
    fireEvent.click(screen.getByRole('button', { name: 'Accorder' }))

    await waitFor(() => expect(screen.getByRole('alert').textContent).toMatch(/audit down/i))
    expect(listAccess).toHaveBeenCalledTimes(1)
    expect(screen.queryByText('Alice Martin')).toBeNull()
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
    expect(screen.getByText('Lire')).toBeTruthy()
    expect(screen.getByText('Déplacer ce document vers la corbeille')).toBeTruthy()

    fireEvent.click(screen.getByTestId('visibility-restricted'))
    await waitFor(() =>
      expect(updateDocumentVisibility).toHaveBeenCalledWith(expect.anything(), DOC, 'restricted'),
    )
  })
})
