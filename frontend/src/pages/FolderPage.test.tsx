// SPDX-License-Identifier: AGPL-3.0-or-later
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'

const getMock = vi.fn()
const postMock = vi.fn()
vi.mock('../lib/api', () => ({
  api: {
    get: (...args: unknown[]) => getMock(...args),
    post: (...args: unknown[]) => postMock(...args),
    patch: vi.fn(),
    delete: vi.fn(),
  },
}))

import { FolderPage } from './FolderPage'

const SPACE = 's1'

const tree = {
  spaceId: SPACE,
  spaceName: 'Identité & accès',
  folders: [
    {
      id: 'proc',
      name: 'Procédures',
      parentFolderId: null,
      position: 0,
      documentCount: 2,
      folderCount: 1,
    },
    {
      id: 'sub',
      name: 'Revues',
      parentFolderId: 'proc',
      position: 0,
      documentCount: 0,
      folderCount: 0,
    },
  ],
  documents: [
    {
      id: 'prov',
      title: 'Provisioning des comptes',
      folderId: 'proc',
      position: 0,
      status: 'valide',
    },
    { id: 'reg', title: 'Registre', folderId: 'proc', position: 1, status: 'brouillon' },
    { id: 'pol', title: 'Politique', folderId: null, position: 0, status: 'valide' },
  ],
}

function renderPage() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/folders/proc']}>
        <Routes>
          <Route path="/folders/:id" element={<FolderPage />} />
          <Route path="/docs/:id/edit" element={<div>Page document</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('FolderPage', () => {
  beforeEach(() => {
    getMock.mockReset()
    postMock.mockReset()
    getMock.mockImplementation((url: string) => {
      if (url === '/api/v1/folders/proc') {
        return Promise.resolve({
          data: {
            id: 'proc',
            spaceId: SPACE,
            parentFolderId: null,
            name: 'Procédures',
            position: 0,
          },
        })
      }
      if (url === `/api/v1/spaces/${SPACE}/tree`) return Promise.resolve({ data: tree })
      return Promise.reject(new Error(`unexpected ${url}`))
    })
  })

  it('affiche sous-dossiers, documents du dossier et fil d’Ariane', async () => {
    renderPage()
    expect(await screen.findByRole('heading', { name: 'Procédures' })).toBeTruthy()

    const crumbs = screen.getByRole('navigation', { name: "Fil d'Ariane" })
    const spaceLink = await within(crumbs).findByRole('link', { name: 'Identité & accès' })
    expect(spaceLink.getAttribute('href')).toBe('/spaces/s1/tree')
    expect(within(crumbs).getByText('Procédures').getAttribute('aria-current')).toBe('page')

    const subs = await screen.findByRole('list', { name: 'Sous-dossiers' })
    expect(within(subs).getByText('Revues')).toBeTruthy()
    const docs = screen.getByRole('list', { name: 'Documents' })
    expect(within(docs).getByText('Provisioning des comptes')).toBeTruthy()
    expect(within(docs).getByText('Registre')).toBeTruthy()
    expect(within(docs).queryByText('Politique')).toBeNull() // document racine
    expect(screen.getByText(/2 documents/)).toBeTruthy()
  })

  it('« Nouveau document ici » crée dans ce dossier puis ouvre le document', async () => {
    postMock.mockResolvedValue({ data: { id: 'new-doc', spaceId: SPACE, title: 'Sans titre' } })
    renderPage()
    fireEvent.click(await screen.findByRole('button', { name: 'Nouveau document ici' }))

    await waitFor(() =>
      expect(postMock).toHaveBeenCalledWith(
        '/api/v1/documents',
        expect.objectContaining({ spaceId: SPACE, folderId: 'proc' }),
      ),
    )
    expect(await screen.findByText('Page document')).toBeTruthy()
  })

  it('« + Sous-dossier » présélectionne ce dossier et crée via POST /folders', async () => {
    postMock.mockResolvedValue({
      data: { id: 'new-f', spaceId: SPACE, parentFolderId: 'proc', name: 'Archives', position: 0 },
    })
    renderPage()
    await screen.findByRole('heading', { name: 'Procédures' })
    fireEvent.click(screen.getByRole('button', { name: '+ Sous-dossier' }))

    const dialog = await screen.findByRole('dialog')
    expect((within(dialog).getByTestId('new-folder-location') as HTMLSelectElement).value).toBe(
      'proc',
    )
    fireEvent.change(within(dialog).getByPlaceholderText(/Continuité/), {
      target: { value: 'Archives' },
    })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Créer le dossier' }))

    await waitFor(() =>
      expect(postMock).toHaveBeenCalledWith('/api/v1/folders', {
        spaceId: SPACE,
        parentFolderId: 'proc',
        name: 'Archives',
      }),
    )
  })
})
