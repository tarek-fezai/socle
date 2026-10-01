// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import type { ReactNode } from 'react'
import { SocleRole } from '../lib/auth'

vi.mock('../lib/api', () => ({ api: {} }))
vi.mock('../lib/notifications', () => ({
  listNotifications: () =>
    Promise.resolve({ items: [], offset: 0, limit: 1, total: 0, unreadCount: 0 }),
}))

const authState = {
  authenticated: true,
  me: {
    id: 'u1',
    email: 'a@example.com',
    displayName: 'Alice',
    avatarInitials: 'A',
    roles: [SocleRole.CONTRIBUTEUR] as string[],
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

import { AppNav } from '../components/AppNav'

function wrap(ui: ReactNode) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return (
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <Routes>
          <Route path="*" element={ui} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  )
}

describe('AppNav role-gated links (/me)', () => {
  beforeEach(() => {
    authState.me.roles = [SocleRole.CONTRIBUTEUR]
  })

  it('n’affiche pas Audit / Intégrations pour CONTRIBUTEUR', async () => {
    render(wrap(<AppNav />))
    await waitFor(() => expect(screen.getByRole('link', { name: 'Documents' })).toBeTruthy())
    expect(screen.queryByRole('link', { name: 'Audit' })).toBeNull()
    expect(screen.queryByRole('link', { name: 'Intégrations' })).toBeNull()
  })

  it('affiche Audit pour AUDITEUR', async () => {
    authState.me.roles = [SocleRole.AUDITEUR]
    render(wrap(<AppNav />))
    await waitFor(() => expect(screen.getByRole('link', { name: 'Audit' })).toBeTruthy())
    expect(screen.queryByRole('link', { name: 'Intégrations' })).toBeNull()
  })

  it('affiche Intégrations pour INTEGRATEUR', async () => {
    authState.me.roles = [SocleRole.INTEGRATEUR]
    render(wrap(<AppNav />))
    await waitFor(() => expect(screen.getByRole('link', { name: 'Intégrations' })).toBeTruthy())
    expect(screen.queryByRole('link', { name: 'Audit' })).toBeNull()
  })
})
