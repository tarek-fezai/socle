import { describe, expect, it, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import type { ReactNode } from 'react'

vi.mock('../lib/api', () => ({ api: {} }))

const listWebhookDeliveries = vi.fn()

vi.mock('../lib/webhooks', async () => {
  const actual = await vi.importActual<typeof import('../lib/webhooks')>('../lib/webhooks')
  return {
    ...actual,
    listWebhookDeliveries: (...args: unknown[]) => listWebhookDeliveries(...args),
  }
})

vi.mock('../auth/AuthProvider', () => ({
  useAuth: () => ({
    authenticated: true,
    me: {
      id: 'u1',
      email: 'auditeur@example.com',
      displayName: 'Auditeur',
      avatarInitials: 'A',
      roles: ['AUDITEUR'],
    },
    organizationName: 'Socle',
    logout: vi.fn(),
    login: vi.fn(),
    loading: false,
    refreshMe: vi.fn(),
  }),
}))

import { WebhookDeliveriesPage } from './WebhookDeliveriesPage'

const D1 = 'dddddddd-dddd-dddd-dddd-dddddddddddd'
const D2 = 'eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee'
const EP = 'ffffffff-ffff-ffff-ffff-ffffffffffff'

function wrap(ui: ReactNode) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return (
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/integrations/webhooks/deliveries']}>
        <Routes>
          <Route path="/integrations/webhooks/deliveries" element={ui} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  )
}

describe('WebhookDeliveriesPage', () => {
  beforeEach(() => {
    listWebhookDeliveries.mockReset()
  })

  it('affiche la liste et filtre par statut via l’API (pas en mémoire)', async () => {
    const allItems = [
      {
        id: D1,
        endpointId: EP,
        endpointUrl: 'https://hooks.example/relay',
        eventType: 'tag.added',
        status: 'delivered',
        attemptCount: 1,
        lastResponseCode: 200,
        deliveredAt: '2026-09-27T10:41:02Z',
        createdAt: '2026-09-27T10:41:00Z',
      },
      {
        id: D2,
        endpointId: EP,
        endpointUrl: 'https://hooks.example/relay',
        eventType: 'folder.created',
        status: 'failed',
        attemptCount: 3,
        lastResponseCode: 504,
        deliveredAt: null,
        createdAt: '2026-09-27T09:58:47Z',
      },
    ]

    listWebhookDeliveries.mockImplementation(
      (_api: unknown, opts: { status?: string } = {}) => {
        if (opts.status === 'failed') {
          return Promise.resolve({
            items: [allItems[1]],
            offset: 0,
            limit: 50,
            total: 1,
          })
        }
        return Promise.resolve({
          items: allItems,
          offset: 0,
          limit: 50,
          total: 2,
        })
      },
    )

    render(wrap(<WebhookDeliveriesPage />))

    await waitFor(() => {
      expect(screen.getByText('tag.added')).toBeTruthy()
      expect(screen.getByText('folder.created')).toBeTruthy()
      expect(screen.getAllByText('Livré').length).toBeGreaterThanOrEqual(1)
      expect(screen.getByText('Échec')).toBeTruthy()
    })
    expect(listWebhookDeliveries).toHaveBeenCalledWith(
      expect.anything(),
      expect.objectContaining({ status: undefined }),
    )

    fireEvent.click(screen.getByRole('button', { name: /^Échecs/ }))

    await waitFor(() => {
      expect(listWebhookDeliveries).toHaveBeenCalledWith(
        expect.anything(),
        expect.objectContaining({ status: 'failed' }),
      )
      expect(screen.getByText('folder.created')).toBeTruthy()
      expect(screen.queryByText('tag.added')).toBeNull()
    })
  })

  it('n’expose pas de bouton Relancer (hors scope sans endpoint BE)', async () => {
    listWebhookDeliveries.mockResolvedValue({
      items: [
        {
          id: D2,
          endpointId: EP,
          endpointUrl: 'https://hooks.example/relay',
          eventType: 'trash.restored',
          status: 'failed',
          attemptCount: 3,
          lastResponseCode: 500,
          deliveredAt: null,
          createdAt: '2026-09-27T09:12:15Z',
        },
      ],
      offset: 0,
      limit: 50,
      total: 1,
    })

    render(wrap(<WebhookDeliveriesPage />))

    await waitFor(() => expect(screen.getByText('trash.restored')).toBeTruthy())
    expect(screen.queryByRole('button', { name: /Relancer/i })).toBeNull()
    expect(screen.getByText(/relance manuelle/i)).toBeTruthy()
  })
})
