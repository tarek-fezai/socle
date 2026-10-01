// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import type { ReactNode } from 'react'
import { formatDeletedAgo, formatPurgeRemaining } from '../lib/trash'

vi.mock('../lib/api', () => ({ api: {} }))

const listTrash = vi.fn()
const restoreTrashItem = vi.fn()

vi.mock('../lib/trash', async () => {
  const actual = await vi.importActual<typeof import('../lib/trash')>('../lib/trash')
  return {
    ...actual,
    listTrash: (...args: unknown[]) => listTrash(...args),
    restoreTrashItem: (...args: unknown[]) => restoreTrashItem(...args),
  }
})

vi.mock('../lib/notifications', () => ({
  listNotifications: () =>
    Promise.resolve({ items: [], offset: 0, limit: 1, total: 0, unreadCount: 0 }),
}))

vi.mock('../auth/AuthProvider', () => ({
  useAuth: () => ({
    authenticated: true,
    me: {
      id: 'u1',
      email: 'a@example.com',
      displayName: 'Alice',
      avatarInitials: 'A',
      roles: ['INTEGRATEUR'],
    },
    organizationName: 'Socle',
    logout: vi.fn(),
    login: vi.fn(),
    loading: false,
    refreshMe: vi.fn(),
  }),
}))

import { TrashPage } from './TrashPage'
import { AppNav } from '../components/AppNav'

const ITEM_ID = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'
const ITEM2 = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb'

function wrap(ui: ReactNode, path = '/trash') {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return (
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/trash" element={ui} />
          <Route path="*" element={ui} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  )
}

const sampleItem = {
  id: ITEM_ID,
  resourceType: 'document',
  resourceId: 'cccccccc-cccc-cccc-cccc-cccccccccccc',
  title: 'Registre des accès à privilèges',
  snapshot: null,
  deletedBy: 'u1',
  deletedByName: 'Claire Dubois',
  deletedAt: '2026-09-24T10:00:00Z',
  purgeAt: '2026-10-24T10:00:00Z',
}

describe('trash helpers', () => {
  it('formate le temps restant avant purge', () => {
    const now = new Date('2026-09-28T10:00:00Z')
    expect(formatPurgeRemaining('2026-10-10T10:00:00Z', now).label).toBe('purgé dans 12 jours')
    expect(formatPurgeRemaining('2026-09-30T10:00:00Z', now).urgent).toBe(true)
  })

  it('formate la date de suppression relative', () => {
    const now = new Date('2026-09-28T10:00:00Z')
    expect(formatDeletedAgo('2026-09-24T10:00:00Z', now)).toBe('il y a 4 jours')
  })
})

describe('TrashPage', () => {
  beforeEach(() => {
    listTrash.mockReset()
    restoreTrashItem.mockReset()
  })

  it('affiche la liste et filtre par type via l’API (pas en mémoire)', async () => {
    listTrash
      .mockResolvedValueOnce({
        items: [sampleItem],
        offset: 0,
        limit: 50,
        total: 1,
      })
      .mockResolvedValueOnce({
        items: [
          {
            ...sampleItem,
            id: ITEM2,
            resourceType: 'folder',
            title: 'Anciennes procédures',
          },
        ],
        offset: 0,
        limit: 50,
        total: 1,
      })

    render(wrap(<TrashPage />))

    await waitFor(() => {
      expect(screen.getByText('Registre des accès à privilèges')).toBeTruthy()
    })
    expect(listTrash).toHaveBeenCalledWith(
      expect.anything(),
      expect.objectContaining({ resourceType: undefined }),
    )

    fireEvent.click(screen.getByRole('button', { name: 'Dossiers' }))

    await waitFor(() => {
      expect(listTrash).toHaveBeenLastCalledWith(
        expect.anything(),
        expect.objectContaining({ resourceType: 'folder' }),
      )
      expect(screen.getByText('Anciennes procédures')).toBeTruthy()
    })
  })

  it('restaure puis rafraîchit depuis le backend (pas d’optimistic update)', async () => {
    listTrash
      .mockResolvedValueOnce({
        items: [sampleItem],
        offset: 0,
        limit: 50,
        total: 1,
      })
      .mockResolvedValueOnce({
        items: [],
        offset: 0,
        limit: 50,
        total: 0,
      })
    restoreTrashItem.mockResolvedValue({ documents: 1, folders: 0, spaces: 0 })

    render(wrap(<TrashPage />))

    await waitFor(() => expect(screen.getByText('Registre des accès à privilèges')).toBeTruthy())

    fireEvent.click(screen.getByRole('button', { name: 'Restaurer' }))

    await waitFor(() => {
      expect(restoreTrashItem).toHaveBeenCalledWith(expect.anything(), ITEM_ID)
    })
    await waitFor(() => {
      expect(screen.getByText(/La corbeille est vide/)).toBeTruthy()
      expect(screen.queryByText('Registre des accès à privilèges')).toBeNull()
    })
  })

  it('affiche le message 409 parent manquant sans contourner', async () => {
    listTrash.mockResolvedValue({
      items: [sampleItem],
      offset: 0,
      limit: 50,
      total: 1,
    })
    restoreTrashItem.mockRejectedValue({
      response: {
        status: 409,
        data: {
          detail: "Restaurez d'abord le dossier parent (encore en corbeille)",
        },
      },
    })

    render(wrap(<TrashPage />))

    await waitFor(() => expect(screen.getByText('Registre des accès à privilèges')).toBeTruthy())
    fireEvent.click(screen.getByRole('button', { name: 'Restaurer' }))

    await waitFor(() => {
      expect(screen.getByText(/Restaurez d'abord le dossier parent/)).toBeTruthy()
    })
    expect(screen.getByText('Registre des accès à privilèges')).toBeTruthy()
  })

  it('affiche exactement les items renvoyés par le backend (pas de filtre client)', async () => {
    listTrash.mockResolvedValue({
      items: [
        sampleItem,
        {
          ...sampleItem,
          id: ITEM2,
          title: 'Visible uniquement car le BE l’a renvoyé',
          resourceType: 'space',
        },
      ],
      offset: 0,
      limit: 50,
      total: 2,
    })

    render(wrap(<TrashPage />))

    await waitFor(() => {
      expect(screen.getByText('Registre des accès à privilèges')).toBeTruthy()
      expect(screen.getByText('Visible uniquement car le BE l’a renvoyé')).toBeTruthy()
    })
    expect(listTrash.mock.calls).toHaveLength(1)
  })
})

describe('AppNav trash / integrations links', () => {
  it('expose Espaces, Équipes, Corbeille et Intégrations', async () => {
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    })
    render(
      <QueryClientProvider client={client}>
        <MemoryRouter>
          <AppNav />
        </MemoryRouter>
      </QueryClientProvider>,
    )

    await waitFor(() => {
      expect((screen.getByRole('link', { name: 'Espaces' }) as HTMLAnchorElement).href).toMatch(
        /\/spaces$/,
      )
      expect((screen.getByRole('link', { name: 'Équipes' }) as HTMLAnchorElement).href).toMatch(
        /\/team$/,
      )
      const trash = screen.getByRole('link', { name: 'Corbeille' }) as HTMLAnchorElement
      const integ = screen.getByRole('link', { name: 'Intégrations' }) as HTMLAnchorElement
      expect(trash.getAttribute('href')).toBe('/trash')
      expect(integ.getAttribute('href')).toBe('/integrations')
    })
  })
})
