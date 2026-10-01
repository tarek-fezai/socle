// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import type { ReactNode } from 'react'

vi.mock('../lib/api', () => ({ api: {} }))

const listSiemConnectors = vi.fn()
const createSiemConnector = vi.fn()
const testSiemConnector = vi.fn()
const updateSiemConnector = vi.fn()
const listWebhookEndpoints = vi.fn()
const createWebhookEndpoint = vi.fn()

vi.mock('../lib/integrations', async () => {
  const actual = await vi.importActual<typeof import('../lib/integrations')>('../lib/integrations')
  return {
    ...actual,
    listSiemConnectors: (...a: unknown[]) => listSiemConnectors(...a),
    createSiemConnector: (...a: unknown[]) => createSiemConnector(...a),
    testSiemConnector: (...a: unknown[]) => testSiemConnector(...a),
    updateSiemConnector: (...a: unknown[]) => updateSiemConnector(...a),
    deleteSiemConnector: vi.fn(),
    listWebhookEndpoints: (...a: unknown[]) => listWebhookEndpoints(...a),
    createWebhookEndpoint: (...a: unknown[]) => createWebhookEndpoint(...a),
    updateWebhookEndpoint: vi.fn(),
    deleteWebhookEndpoint: vi.fn(),
  }
})

const authState = {
  authenticated: true,
  me: {
    id: 'u1',
    email: 'integrateur@example.com',
    displayName: 'Intégrateur',
    avatarInitials: 'I',
    roles: ['INTEGRATEUR'] as string[],
  },
  organizationName: 'Socle',
  logout: vi.fn(),
  login: vi.fn(),
  loading: false,
  refreshMe: vi.fn(),
}

vi.mock('../auth/AuthProvider', () => ({
  useAuth: () => authState,
}))

import { IntegrationsPage } from './IntegrationsPage'

const CONN = 'cccccccc-cccc-cccc-cccc-cccccccccccc'

function wrap(ui: ReactNode) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return (
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/integrations']}>
        <Routes>
          <Route path="/integrations" element={ui} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  )
}

describe('IntegrationsPage', () => {
  beforeEach(() => {
    authState.me.roles = ['INTEGRATEUR']
    listSiemConnectors.mockReset()
    createSiemConnector.mockReset()
    testSiemConnector.mockReset()
    updateSiemConnector.mockReset()
    listWebhookEndpoints.mockReset()
    createWebhookEndpoint.mockReset()
    listSiemConnectors.mockResolvedValue([])
    listWebhookEndpoints.mockResolvedValue([])
  })

  it('refuse l’accès sans rôle INTEGRATEUR', async () => {
    authState.me.roles = ['CONTRIBUTEUR']
    render(wrap(<IntegrationsPage />))
    await waitFor(() => {
      expect(screen.getByText(/Accès réservé au rôle/)).toBeTruthy()
      expect(screen.getByText(/INTEGRATEUR/)).toBeTruthy()
    })
    expect(listSiemConnectors).not.toHaveBeenCalled()
  })

  it('refuse un AUDITEUR seul (SoD — pas de configuration des destinations)', async () => {
    authState.me.roles = ['AUDITEUR']
    render(wrap(<IntegrationsPage />))
    await waitFor(() => {
      expect(screen.getByText(/séparée de la supervision/i)).toBeTruthy()
    })
    expect(listSiemConnectors).not.toHaveBeenCalled()
  })

  it('crée un connecteur SIEM via le formulaire', async () => {
    listSiemConnectors
      .mockResolvedValueOnce([])
      .mockResolvedValueOnce([
        {
          id: CONN,
          provider: 'splunk',
          config: { endpoint: 'https://hec.example', hec_token_prefix: 'tok_xxxx…' },
          status: 'disconnected',
          connectedAt: null,
        },
      ])
    createSiemConnector.mockResolvedValue({
      id: CONN,
      provider: 'splunk',
      config: { endpoint: 'https://hec.example', hec_token_prefix: 'tok_xxxx…' },
      status: 'disconnected',
      connectedAt: null,
    })

    render(wrap(<IntegrationsPage />))
    await waitFor(() => expect(screen.getByText(/Aucun connecteur SIEM/)).toBeTruthy())

    fireEvent.click(screen.getByRole('button', { name: /\+ Ajouter un connecteur SIEM/ }))
    fireEvent.change(screen.getByPlaceholderText('https://…'), {
      target: { value: 'https://hec.example' },
    })
    const tokenField = document.querySelector('input[type="password"]') as HTMLInputElement
    fireEvent.change(tokenField, { target: { value: 'secret-token' } })
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer' }))

    await waitFor(() => {
      expect(createSiemConnector).toHaveBeenCalledWith(
        expect.anything(),
        expect.objectContaining({
          provider: 'splunk',
          config: expect.objectContaining({
            endpoint: 'https://hec.example',
            hec_token: 'secret-token',
          }),
        }),
      )
    })
    await waitFor(() => {
      expect(screen.getByText(/Splunk — HTTP Event Collector/)).toBeTruthy()
    })
  })

  it('affiche le résultat du test SIEM sans exposer de secret', async () => {
    listSiemConnectors.mockResolvedValue([
      {
        id: CONN,
        provider: 'splunk',
        config: { endpoint: 'https://hec.example', hec_token_prefix: 'tok_xxxx…' },
        status: 'disconnected',
        connectedAt: null,
      },
    ])
    testSiemConnector.mockResolvedValue({ ok: true, httpStatus: 200, message: 'Connexion OK' })

    render(wrap(<IntegrationsPage />))
    await waitFor(() => expect(screen.getByText(/Splunk/)).toBeTruthy())

    fireEvent.click(screen.getByRole('button', { name: 'Tester' }))

    await waitFor(() => {
      expect(testSiemConnector).toHaveBeenCalledWith(expect.anything(), CONN)
      expect(screen.getByTestId(`siem-test-${CONN}`).textContent).toMatch(/Connexion OK/)
    })
    expect(document.body.textContent).not.toMatch(/secret-token/)
    expect(document.body.textContent).toMatch(/https:\/\/hec\.example/)
  })

  it('affiche le secret webhook une seule fois à la création', async () => {
    createWebhookEndpoint.mockResolvedValue({
      endpoint: {
        id: 'wwwwwwww-wwww-wwww-wwww-wwwwwwwwwwww',
        url: 'https://hooks.example/socle',
        subscribedEvents: ['document.published'],
        status: 'active',
        createdAt: null,
      },
      secret: 'whsec_ONLY_ONCE_SECRET',
      secretPrefix: 'whsec_ON…',
    })
    listWebhookEndpoints
      .mockResolvedValueOnce([])
      .mockResolvedValueOnce([
        {
          id: 'wwwwwwww-wwww-wwww-wwww-wwwwwwwwwwww',
          url: 'https://hooks.example/socle',
          subscribedEvents: ['document.published'],
          status: 'active',
          createdAt: null,
        },
      ])

    render(wrap(<IntegrationsPage />))
    await waitFor(() => expect(screen.getByText(/Aucun endpoint webhook/)).toBeTruthy())

    fireEvent.click(screen.getByRole('button', { name: /\+ Ajouter un endpoint webhook/ }))
    fireEvent.change(screen.getByPlaceholderText('https://hooks.example.com/socle'), {
      target: { value: 'https://hooks.example/socle' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Créer' }))

    await waitFor(() => {
      expect(screen.getByTestId('webhook-secret-oneshot')).toBeTruthy()
      expect(screen.getByText('whsec_ONLY_ONCE_SECRET')).toBeTruthy()
      expect(screen.getByText(/ne sera plus jamais affiché/i)).toBeTruthy()
    })

    fireEvent.click(screen.getByRole('button', { name: /J'ai copié le secret/ }))
    await waitFor(() => {
      expect(screen.queryByTestId('webhook-secret-oneshot')).toBeNull()
      expect(screen.queryByText('whsec_ONLY_ONCE_SECRET')).toBeNull()
    })
  })
})
