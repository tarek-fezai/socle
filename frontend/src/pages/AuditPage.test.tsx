// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import type { ReactNode } from 'react'
import {
  formatAuditMetadataSummary,
  actorLabel,
  type AuditEvent,
} from '../lib/audit'

vi.mock('../lib/api', () => ({ api: {} }))

const listAuditEvents = vi.fn()

vi.mock('../lib/audit', async () => {
  const actual = await vi.importActual<typeof import('../lib/audit')>('../lib/audit')
  return {
    ...actual,
    listAuditEvents: (...args: unknown[]) => listAuditEvents(...args),
  }
})

const authState = {
  authenticated: true,
  me: {
    id: '44444444-4444-4444-4444-444444444444',
    email: 'auditeur@example.com',
    displayName: 'Awa Auditeur',
    avatarInitials: 'AA',
    roles: ['AUDITEUR'] as string[],
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

import { AuditPage } from './AuditPage'

const sample: AuditEvent = {
  id: 1,
  actorId: '11111111-1111-1111-1111-111111111111',
  actorIsSystem: false,
  actorDisplayName: 'Camille Contributeur',
  actorEmail: 'contributeur@example.com',
  action: 'access.granted',
  resourceType: 'space',
  resourceId: '00000000-0000-0000-0000-000000000001',
  metadata: JSON.stringify({
    relation: 'editor',
    subjectId: '22222222-2222-2222-2222-222222222222',
    outcome: 'applied',
  }),
  ipAddress: null,
  createdAt: '2026-09-28T12:00:00Z',
}

function wrap(ui: ReactNode) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return (
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/audit']}>
        <Routes>
          <Route path="/audit" element={ui} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  )
}

describe('audit metadata rendering', () => {
  it('résume access.granted et document.updated avec transition', () => {
    expect(formatAuditMetadataSummary(sample)).toMatch(/Accordé editor/)
    expect(
      formatAuditMetadataSummary({
        ...sample,
        action: 'document.updated',
        metadata: JSON.stringify({ status_before: 'valide', status_after: 'en_revue' }),
      }),
    ).toBe('Statut valide → en_revue')
    expect(actorLabel(sample)).toBe('Camille Contributeur')
  })
})

describe('AuditPage', () => {
  beforeEach(() => {
    listAuditEvents.mockReset()
    authState.me.roles = ['AUDITEUR']
  })

  it('appelle l’API avec les filtres appliqués (pas de filtre mémoire)', async () => {
    listAuditEvents.mockResolvedValue({ items: [sample], offset: 0, limit: 50, total: 1 })

    render(wrap(<AuditPage />))
    await waitFor(() => expect(listAuditEvents).toHaveBeenCalled())

    fireEvent.change(screen.getByPlaceholderText(/document \| space/), {
      target: { value: 'document' },
    })
    fireEvent.change(screen.getByPlaceholderText(/access\.\*/), {
      target: { value: 'access.*' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Appliquer les filtres' }))

    await waitFor(() => {
      expect(listAuditEvents).toHaveBeenCalledWith(
        expect.anything(),
        expect.objectContaining({
          resourceType: 'document',
          action: 'access.*',
          offset: 0,
          limit: 50,
        }),
      )
    })
    expect(screen.getByText('access.granted')).toBeTruthy()
    expect(screen.getByText(/Accordé editor/)).toBeTruthy()
  })

  it('utilisateur sans rôle AUDITEUR → écran inaccessible', async () => {
    authState.me.roles = ['CONTRIBUTEUR']
    render(wrap(<AuditPage />))
    await waitFor(() => {
      expect(screen.getByText(/Accès réservé au rôle/i)).toBeTruthy()
      expect(screen.getByText('AUDITEUR')).toBeTruthy()
    })
    expect(listAuditEvents).not.toHaveBeenCalled()
  })

  it('INTEGRATEUR seul → écran audit inaccessible (SoD)', async () => {
    authState.me.roles = ['INTEGRATEUR']
    render(wrap(<AuditPage />))
    await waitFor(() => {
      expect(screen.getByText(/Accès réservé au rôle/i)).toBeTruthy()
    })
    expect(listAuditEvents).not.toHaveBeenCalled()
  })
})
