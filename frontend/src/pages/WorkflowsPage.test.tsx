import { describe, expect, it, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import type { ReactNode } from 'react'

const getMock = vi.fn()
const postMock = vi.fn()
const putMock = vi.fn()
const deleteMock = vi.fn()

vi.mock('../lib/api', () => ({
  api: {
    get: (...args: unknown[]) => getMock(...args),
    post: (...args: unknown[]) => postMock(...args),
    put: (...args: unknown[]) => putMock(...args),
    delete: (...args: unknown[]) => deleteMock(...args),
  },
}))

import { WorkflowsPage } from './WorkflowsPage'

const ROLE = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1'
const WF = {
  id: 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',
  name: 'Politique 3 étapes',
  scopeSpaceId: '00000000-0000-0000-0000-000000000001',
  scopeDocType: 'politique',
  status: 'active' as const,
  createdAt: new Date().toISOString(),
  inProgressCount: 0,
  steps: [
    {
      id: 's1',
      stepOrder: 1,
      slaHours: 24,
      approverRoleId: ROLE,
      approverRoleName: 'Éditeur de documents',
      escalatesToStepOrder: 2,
    },
    {
      id: 's2',
      stepOrder: 2,
      slaHours: 48,
      approverRoleId: ROLE,
      approverRoleName: 'Analyste conformité',
      escalatesToStepOrder: 3,
    },
    {
      id: 's3',
      stepOrder: 3,
      slaHours: 24,
      approverRoleId: ROLE,
      approverRoleName: 'Analyste conformité',
      escalatesToStepOrder: null,
    },
  ],
}

function wrap(ui: ReactNode) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return (
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/admin/workflows']}>
        <Routes>
          <Route path="/admin/workflows" element={ui} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  )
}

describe('WorkflowsPage', () => {
  beforeEach(() => {
    getMock.mockReset()
    postMock.mockReset()
    putMock.mockReset()
    deleteMock.mockReset()
    getMock.mockImplementation((url: string) => {
      if (url.includes('/global-roles')) {
        return Promise.resolve({
          data: [{ id: ROLE, name: 'Éditeur de documents', description: 'editeur' }],
        })
      }
      return Promise.resolve({ data: [WF] })
    })
  })

  it('liste les workflows avec le nombre d’étapes', async () => {
    render(wrap(<WorkflowsPage />))
    await waitFor(() => expect(screen.getByText('Politique 3 étapes')).toBeTruthy())
    expect(screen.getByText(/^3 étapes$/)).toBeTruthy()
    expect(screen.getByText(/Type : politique/)).toBeTruthy()
  })

  it('crée un workflow multi-étapes via le formulaire', async () => {
    postMock.mockResolvedValue({
      data: { ...WF, id: 'cccccccc-cccc-cccc-cccc-cccccccccccc', name: 'Chaîne N' },
    })
    render(wrap(<WorkflowsPage />))
    await waitFor(() => expect(screen.getByText('Nouveau workflow')).toBeTruthy())
    fireEvent.click(screen.getByText('Nouveau workflow'))

    const nameInput = screen.getByRole('textbox', { name: /Nom/i })
    fireEvent.change(nameInput, { target: { value: 'Chaîne N' } })
    fireEvent.click(screen.getByText('+ Étape'))
    fireEvent.click(screen.getByText('Enregistrer'))

    await waitFor(() => {
      expect(postMock).toHaveBeenCalled()
      const body = postMock.mock.calls[0][1] as { name: string; steps: unknown[] }
      expect(body.name).toBe('Chaîne N')
      expect(body.steps.length).toBeGreaterThanOrEqual(2)
    })
  })
})
