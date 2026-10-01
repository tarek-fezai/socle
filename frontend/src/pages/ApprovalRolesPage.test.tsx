// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import type { ReactNode } from 'react'

const getMock = vi.fn()

vi.mock('../lib/api', () => ({
  api: {
    get: (...args: unknown[]) => getMock(...args),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
  },
}))

import { ApprovalRolesPage } from './ApprovalRolesPage'

function wrap(ui: ReactNode) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return (
    <QueryClientProvider client={qc}>
      <MemoryRouter>{ui}</MemoryRouter>
    </QueryClientProvider>
  )
}

describe('ApprovalRolesPage', () => {
  beforeEach(() => {
    getMock.mockImplementation((url: string) => {
      if (String(url).includes('global-roles')) {
        return Promise.resolve({
          data: [{ id: 'r1', name: 'Reviewer', description: null }],
        })
      }
      if (String(url).includes('approval-role-assignments')) {
        return Promise.resolve({
          data: [
            {
              id: 'a1',
              roleId: 'r1',
              roleName: 'Reviewer',
              subjectType: 'user',
              subjectId: '11111111-1111-1111-1111-111111111111',
              scopeType: 'space',
              scopeRef: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
              grantedBy: null,
              grantedAt: null,
            },
          ],
        })
      }
      return Promise.resolve({ data: [] })
    })
  })

  it('lists scoped approval role assignments', async () => {
    render(wrap(<ApprovalRolesPage />))
    await waitFor(() => expect(screen.getAllByText('Reviewer').length).toBeGreaterThan(0))
    expect(screen.getByText(/Espace aaaaaaaa/)).toBeTruthy()
    expect(screen.getByRole('button', { name: /Ajouter l.attribution/ })).toBeTruthy()
  })
})
