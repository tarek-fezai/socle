// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import type { ReactNode } from 'react'
import { formatNotificationMessage } from '../lib/notifications'

vi.mock('../lib/api', () => ({ api: {} }))

const listNotifications = vi.fn()
const markNotificationRead = vi.fn()

vi.mock('../lib/notifications', async () => {
  const actual = await vi.importActual<typeof import('../lib/notifications')>(
    '../lib/notifications',
  )
  return {
    ...actual,
    listNotifications: (...args: unknown[]) => listNotifications(...args),
    markNotificationRead: (...args: unknown[]) => markNotificationRead(...args),
  }
})

vi.mock('../auth/AuthProvider', () => ({
  useAuth: () => ({
    authenticated: true,
    me: { id: 'u1', email: 'a@example.com', displayName: 'Alice', avatarInitials: 'A' },
    organizationName: 'Socle',
    logout: vi.fn(),
    login: vi.fn(),
    loading: false,
    refreshMe: vi.fn(),
  }),
}))

import { NotificationsPage, relativeTimeLabel } from './NotificationsPage'
import { AppNav } from '../components/AppNav'

const NOTIF_ID = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'
const DOC = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb'

function wrap(ui: ReactNode, path = '/notifications') {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return (
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/notifications" element={ui} />
          <Route path="*" element={ui} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  )
}

describe('formatNotificationMessage', () => {
  it('formule un texte humain pour approval_chain_exhausted', () => {
    const text = formatNotificationMessage({
      id: NOTIF_ID,
      type: 'approval_chain_exhausted',
      payload: { document_id: DOC, steps_traversed: 2 },
      documentTitle: 'Politique accès',
      readAt: null,
      createdAt: '2026-09-28T12:00:00Z',
    })
    expect(text).toContain('Politique accès')
    expect(text).toMatch(/restée sans décision/i)
    expect(text).not.toContain('document_id')
  })
})

describe('NotificationsPage', () => {
  beforeEach(() => {
    listNotifications.mockReset()
    markNotificationRead.mockReset()
  })

  it('affiche le message humain et marque comme lue après action (rafraîchissement backend)', async () => {
    listNotifications
      .mockResolvedValueOnce({
        items: [
          {
            id: NOTIF_ID,
            type: 'approval_chain_exhausted',
            payload: { document_id: DOC, steps_traversed: 2 },
            documentTitle: 'Politique accès',
            readAt: null,
            createdAt: '2026-09-28T12:00:00Z',
          },
        ],
        offset: 0,
        limit: 50,
        total: 1,
        unreadCount: 1,
      })
      .mockResolvedValueOnce({
        items: [
          {
            id: NOTIF_ID,
            type: 'approval_chain_exhausted',
            payload: { document_id: DOC, steps_traversed: 2 },
            documentTitle: 'Politique accès',
            readAt: '2026-09-28T13:00:00Z',
            createdAt: '2026-09-28T12:00:00Z',
          },
        ],
        offset: 0,
        limit: 50,
        total: 1,
        unreadCount: 0,
      })
    markNotificationRead.mockResolvedValue({
      id: NOTIF_ID,
      type: 'approval_chain_exhausted',
      payload: {},
      documentTitle: 'Politique accès',
      readAt: '2026-09-28T13:00:00Z',
      createdAt: '2026-09-28T12:00:00Z',
    })

    render(wrap(<NotificationsPage />))

    await waitFor(() => {
      expect(screen.getByText(/Politique accès/)).toBeTruthy()
      expect(screen.getByText(/restée sans décision/i)).toBeTruthy()
      expect(screen.getByText(/1 non lue/)).toBeTruthy()
    })

    fireEvent.click(screen.getByRole('button', { name: 'Marquer comme lue' }))

    await waitFor(() => {
      expect(markNotificationRead).toHaveBeenCalledWith(expect.anything(), NOTIF_ID)
    })
    await waitFor(() => {
      expect(screen.getByText(/0 non lue/)).toBeTruthy()
      expect(screen.queryByRole('button', { name: 'Marquer comme lue' })).toBeNull()
    })
  })
})

describe('AppNav badge', () => {
  beforeEach(() => {
    listNotifications.mockReset()
  })

  it('affiche le badge avec le compte non-lues puis le retire à 0', async () => {
    let calls = 0
    listNotifications.mockImplementation(() => {
      calls += 1
      return Promise.resolve({
        items: [],
        offset: 0,
        limit: 1,
        total: calls === 1 ? 2 : 0,
        unreadCount: calls === 1 ? 2 : 0,
      })
    })

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

    await waitFor(() => expect(screen.getByTestId('notif-badge').textContent).toBe('2'))

    await client.invalidateQueries({ queryKey: ['notifications'] })

    await waitFor(() => expect(screen.queryByTestId('notif-badge')).toBeNull())
  })
})

describe('relativeTimeLabel', () => {
  const now = new Date('2026-09-10T12:00:00Z')

  it('libellés relatifs fr-FR', () => {
    expect(relativeTimeLabel('2026-09-10T11:55:00Z', now)).toBe('Il y a 5 minutes')
    expect(relativeTimeLabel('2026-09-10T11:59:00Z', now)).toBe('Il y a 1 minute')
    expect(relativeTimeLabel('2026-09-10T10:00:00Z', now)).toBe('Il y a 2 heures')
    expect(relativeTimeLabel('2026-09-10T11:59:50Z', now)).toBe("À l'instant")
  })

  it('Hier / jour de semaine avec heure locale', () => {
    expect(relativeTimeLabel('2026-09-09T12:00:00Z', now)).toMatch(/^Hier à \d{2}:\d{2}$/)
    expect(relativeTimeLabel('2026-09-07T12:00:00Z', now)).toMatch(/^Lundi à \d{2}:\d{2}$/)
  })
})

describe('NotificationsPage — maquette', () => {
  beforeEach(() => {
    listNotifications.mockReset()
    markNotificationRead.mockReset()
  })

  it('« Tout marquer comme lu » est un placeholder désactivé ; lien Préférences → /account', async () => {
    listNotifications.mockResolvedValue({
      items: [
        {
          id: NOTIF_ID,
          type: 'comment_mention',
          payload: { document_id: DOC },
          documentTitle: 'Politique accès',
          readAt: null,
          createdAt: new Date(Date.now() - 20 * 60_000).toISOString(),
        },
      ],
      offset: 0,
      limit: 50,
      total: 1,
      unreadCount: 1,
    })
    render(wrap(<NotificationsPage />))
    const readAll = (await screen.findByRole('button', {
      name: 'Tout marquer comme lu',
    })) as HTMLButtonElement
    expect(readAll.disabled).toBe(true)
    expect(readAll.getAttribute('data-visual-mask')).toBe('notif-read-all')
    expect(screen.getByRole('link', { name: /Préférences/ }).getAttribute('href')).toBe('/account')
    expect(await screen.findByText("Aujourd'hui")).toBeTruthy()
    expect(await screen.findByText('Il y a 20 minutes')).toBeTruthy()
  })
})
