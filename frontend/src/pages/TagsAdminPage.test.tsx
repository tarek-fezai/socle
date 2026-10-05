// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'

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

vi.mock('../auth/AuthProvider', () => ({
  useAuth: () => ({ organizationName: 'Organisation Démo' }),
}))

import { TagsAdminPage } from './TagsAdminPage'

const TAGS_PAYLOAD = {
  summary: {
    tagCount: 2,
    taggedDocumentCount: 5,
    totalDocumentCount: 10,
    tagCreationPolicy: 'any_editor' as const,
  },
  tags: [
    {
      id: 't1',
      name: 'IAM',
      color: '#3730E0',
      documentCount: 3,
      createdByDisplayName: 'Tarek Fezai',
      createdAt: '2026-01-01T00:00:00Z',
      governed: false,
    },
    {
      id: 't2',
      name: 'Obsolète',
      color: '#9B9BA1',
      documentCount: 0,
      createdByDisplayName: null,
      createdAt: null,
      governed: true,
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
        <TagsAdminPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('TagsAdminPage', () => {
  beforeEach(() => {
    getMock.mockReset()
    postMock.mockReset()
    putMock.mockReset()
    deleteMock.mockReset()
    getMock.mockResolvedValue({ data: TAGS_PAYLOAD })
  })

  it('affiche le titre, les stats et le tableau', async () => {
    renderPage()
    expect(await screen.findByRole('heading', { name: 'Tags' })).toBeTruthy()
    expect(await screen.findByText('IAM')).toBeTruthy()
    expect(screen.getByText('Obsolète')).toBeTruthy()
    await waitFor(() => {
      const stats = document.querySelector('[data-mock-id="tags-stats"]')
      expect(stats?.textContent).toContain('2 tags')
      expect(stats?.textContent).toContain('5 documents étiquetés')
    })
    expect(getMock).toHaveBeenCalledWith('/api/v1/admin/tags')
  })

  it('crée un tag via le modal', async () => {
    postMock.mockResolvedValue({ data: TAGS_PAYLOAD.tags[0] })
    renderPage()
    await screen.findByText('IAM')
    fireEvent.click(screen.getByRole('button', { name: '+ Nouveau tag' }))
    fireEvent.change(screen.getByLabelText('Nom'), { target: { value: 'SSO' } })
    fireEvent.click(screen.getByRole('button', { name: 'Créer' }))
    await waitFor(() => expect(postMock).toHaveBeenCalledWith('/api/v1/admin/tags', { name: 'SSO' }))
  })
})
