// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { CommandPalette } from './CommandPalette'

const searchDocuments = vi.fn()
const navigate = vi.fn()

vi.mock('../../lib/search', () => ({
  searchDocuments: (...args: unknown[]) => searchDocuments(...args),
}))

vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom')
  return {
    ...actual,
    useNavigate: () => navigate,
  }
})

vi.mock('../../lib/api', () => ({
  api: {},
}))

function wrap(ui: React.ReactNode) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return (
    <QueryClientProvider client={client}>
      <MemoryRouter>{ui}</MemoryRouter>
    </QueryClientProvider>
  )
}

describe('CommandPalette', () => {
  beforeEach(() => {
    searchDocuments.mockReset()
    navigate.mockReset()
  })

  afterEach(() => {
    vi.clearAllMocks()
  })

  it('ferme avec Escape', () => {
    const onClose = vi.fn()
    render(wrap(<CommandPalette open onClose={onClose} />))
    fireEvent.keyDown(window, { key: 'Escape' })
    expect(onClose).toHaveBeenCalled()
  })

  it('navigue au clavier (flèches + Enter)', async () => {
    searchDocuments.mockResolvedValue({
      query: 'pol',
      total: 2,
      results: [
        {
          id: 'doc-1',
          title: 'Politique A',
          excerpt: '',
          spaceId: 's1',
          spaceName: 'Identité',
          docType: null,
          status: 'valide',
          updatedAt: null,
          rank: 1,
        },
        {
          id: 'doc-2',
          title: 'Politique B',
          excerpt: '',
          spaceId: 's1',
          spaceName: 'Identité',
          docType: null,
          status: 'brouillon',
          updatedAt: null,
          rank: 0.5,
        },
      ],
    })
    const onClose = vi.fn()
    render(wrap(<CommandPalette open onClose={onClose} />))
    const input = screen.getByTestId('command-palette-input')
    fireEvent.change(input, { target: { value: 'pol' } })

    await waitFor(() => expect(searchDocuments).toHaveBeenCalled())
    await screen.findByText('Politique A')

    fireEvent.keyDown(input, { key: 'ArrowDown' })
    fireEvent.keyDown(input, { key: 'Enter' })

    expect(navigate).toHaveBeenCalledWith('/docs/doc-2')
    expect(onClose).toHaveBeenCalled()
  })

  it('ne rend rien si fermé', () => {
    const { container } = render(wrap(<CommandPalette open={false} onClose={() => {}} />))
    expect(container.querySelector('[data-testid="command-palette"]')).toBeNull()
  })

  it('affiche le statut SearchHit dans la ligne méta (ex. brouillon)', async () => {
    searchDocuments.mockResolvedValue({
      query: 'reg',
      total: 1,
      results: [
        {
          id: 'doc-3',
          title: 'Registre',
          excerpt: '',
          spaceId: 's1',
          spaceName: 'Identité & accès',
          docType: null,
          status: 'brouillon',
          updatedAt: null,
          rank: 1,
        },
      ],
    })
    render(wrap(<CommandPalette open onClose={() => {}} />))
    fireEvent.change(screen.getByTestId('command-palette-input'), { target: { value: 'reg' } })
    await screen.findByText('Registre')
    expect(screen.getByText(/Identité & accès · brouillon/)).toBeTruthy()
  })
})
