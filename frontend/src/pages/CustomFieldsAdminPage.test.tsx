// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'

const getMock = vi.fn()
const postMock = vi.fn()
const putMock = vi.fn()

vi.mock('../lib/api', () => ({
  api: {
    get: (...args: unknown[]) => getMock(...args),
    post: (...args: unknown[]) => postMock(...args),
    put: (...args: unknown[]) => putMock(...args),
    delete: vi.fn(),
  },
}))

vi.mock('../auth/AuthProvider', () => ({
  useAuth: () => ({ organizationName: 'Organisation Démo' }),
}))

import { CustomFieldsAdminPage } from './CustomFieldsAdminPage'

const FIELDS = {
  fields: [
    {
      id: 'f1',
      name: 'Référence réglementaire',
      slug: 'reference_reglementaire',
      helpText: null,
      fieldType: 'texte',
      scope: 'all_spaces',
      scopeSpaceName: null,
      required: true,
      options: null,
      status: 'active',
      documentCount: 4,
      createdAt: '2026-01-01T00:00:00Z',
    },
  ],
}

function renderPage() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <CustomFieldsAdminPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('CustomFieldsAdminPage', () => {
  beforeEach(() => {
    getMock.mockReset()
    postMock.mockReset()
    putMock.mockReset()
    getMock.mockImplementation((url: string) => {
      if (url.includes('/admin/custom-fields')) return Promise.resolve({ data: FIELDS })
      if (url.includes('/spaces'))
        return Promise.resolve({
          data: [{ id: 's1', name: 'Conformité', color: null, canManage: true, isOwner: false, isResponsible: false }],
        })
      return Promise.reject(new Error(`unexpected GET ${url}`))
    })
  })

  it('liste les champs actifs', async () => {
    renderPage()
    expect(await screen.findByRole('heading', { name: 'Champs personnalisés' })).toBeTruthy()
    expect(await screen.findByText('Référence réglementaire')).toBeTruthy()
    expect(screen.getByText('reference_reglementaire')).toBeTruthy()
  })

  it('ouvre le constructeur pour un nouveau champ', async () => {
    postMock.mockResolvedValue({ data: { ...FIELDS.fields[0], id: 'new' } })
    renderPage()
    await screen.findByText('Référence réglementaire')
    fireEvent.click(screen.getByRole('button', { name: /Nouveau champ/ }))
    expect(screen.getByLabelText('Nom du champ')).toBeTruthy()
    fireEvent.change(screen.getByLabelText('Nom du champ'), { target: { value: 'Criticité' } })
    fireEvent.click(screen.getByRole('button', { name: 'Créer le champ' }))
    await waitFor(() => expect(postMock).toHaveBeenCalled())
    expect(postMock.mock.calls[0][0]).toBe('/api/v1/admin/custom-fields')
  })
})
