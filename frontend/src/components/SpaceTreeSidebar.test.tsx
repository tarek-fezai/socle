// SPDX-License-Identifier: AGPL-3.0-or-later
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import type { ReactNode } from 'react'

const getMock = vi.fn()
const postMock = vi.fn()
vi.mock('../lib/api', () => ({
  api: {
    get: (...args: unknown[]) => getMock(...args),
    post: (...args: unknown[]) => postMock(...args),
  },
}))

import { SpaceTreeSidebar } from './SpaceTreeSidebar'

const SPACE = 's1'

const rawTree = {
  spaceId: SPACE,
  spaceName: 'Identité & accès',
  folders: [
    {
      id: 'proc',
      name: 'Procédures',
      parentFolderId: null,
      position: 0,
      documentCount: 2,
      folderCount: 0,
    },
    {
      id: 'ref',
      name: 'Référence',
      parentFolderId: null,
      position: 1,
      documentCount: 1,
      folderCount: 1,
    },
    {
      id: 'sub',
      name: 'Annexes',
      parentFolderId: 'ref',
      position: 0,
      documentCount: 1,
      folderCount: 0,
    },
  ],
  documents: [
    {
      id: 'pol',
      title: 'Politique de gestion des accès',
      folderId: null,
      position: 0,
      status: 'valide',
    },
    {
      id: 'prov',
      title: 'Provisioning des comptes',
      folderId: 'proc',
      position: 0,
      status: 'valide',
    },
    { id: 'reg', title: 'Registre des accès', folderId: 'proc', position: 1, status: 'brouillon' },
    { id: 'gloss', title: 'Glossaire', folderId: 'sub', position: 0, status: 'valide' },
  ],
}

function wrap(ui: ReactNode) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return (
    <QueryClientProvider client={client}>
      <MemoryRouter>{ui}</MemoryRouter>
    </QueryClientProvider>
  )
}

describe('SpaceTreeSidebar', () => {
  beforeEach(() => {
    getMock.mockReset()
    postMock.mockReset()
    getMock.mockResolvedValue({ data: rawTree })
  })

  it('affiche dossiers et documents racine, dossiers repliés par défaut', async () => {
    render(wrap(<SpaceTreeSidebar spaceId={SPACE} />))
    await waitFor(() => expect(screen.getByText('Procédures')).toBeTruthy())

    expect(getMock).toHaveBeenCalledWith(`/api/v1/spaces/${SPACE}/tree`, expect.anything())
    expect(screen.getByText('Référence')).toBeTruthy()
    expect(screen.getByText('Politique de gestion des accès')).toBeTruthy()
    expect(screen.queryByText('Provisioning des comptes')).toBeNull()
    expect(screen.getByRole('link', { name: 'Procédures' }).getAttribute('href')).toBe(
      '/folders/proc',
    )
    expect(screen.getByRole('button', { name: 'Document' })).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Dossier' })).toBeTruthy()
  })

  it('déplie / replie un dossier', async () => {
    render(wrap(<SpaceTreeSidebar spaceId={SPACE} />))
    await waitFor(() => expect(screen.getByText('Procédures')).toBeTruthy())

    const toggle = screen.getByRole('button', { name: 'Déplier Procédures' })
    expect(toggle.getAttribute('aria-expanded')).toBe('false')
    fireEvent.click(toggle)

    expect(screen.getByText('Provisioning des comptes')).toBeTruthy()
    expect(screen.getByText('brouillon')).toBeTruthy()
    expect(
      screen.getByRole('button', { name: 'Replier Procédures' }).getAttribute('aria-expanded'),
    ).toBe('true')

    fireEvent.click(screen.getByRole('button', { name: 'Replier Procédures' }))
    expect(screen.queryByText('Provisioning des comptes')).toBeNull()
  })

  it('surligne le document courant et déplie ses dossiers parents', async () => {
    render(wrap(<SpaceTreeSidebar spaceId={SPACE} currentDocumentId="gloss" />))
    const link = await screen.findByRole('link', { name: 'Glossaire' })
    expect(link.getAttribute('aria-current')).toBe('page')
    expect(link.getAttribute('href')).toBe('/docs/gloss')
    // Référence > Annexes dépliés
    expect(screen.getByRole('link', { name: 'Annexes' })).toBeTruthy()
    expect(screen.queryByText('Provisioning des comptes')).toBeNull()
  })

  it('surligne le dossier courant', async () => {
    render(wrap(<SpaceTreeSidebar spaceId={SPACE} currentFolderId="sub" />))
    const link = await screen.findByRole('link', { name: 'Annexes' })
    expect(link.getAttribute('aria-current')).toBe('page')
    expect(screen.getByRole('link', { name: 'Référence' }).getAttribute('aria-current')).toBeNull()
  })

  it('+ Document crée dans le dossier courant', async () => {
    postMock.mockResolvedValue({ data: { id: 'new-doc', spaceId: SPACE, title: 'Sans titre' } })
    render(wrap(<SpaceTreeSidebar spaceId={SPACE} currentFolderId="proc" />))
    await screen.findByText('Procédures')

    fireEvent.click(screen.getByRole('button', { name: /Document/ }))
    await waitFor(() =>
      expect(postMock).toHaveBeenCalledWith(
        '/api/v1/documents',
        expect.objectContaining({ spaceId: SPACE, folderId: 'proc', title: 'Sans titre' }),
      ),
    )
  })

  it('+ Dossier ouvre le dialogue avec le dossier courant présélectionné', async () => {
    render(wrap(<SpaceTreeSidebar spaceId={SPACE} currentFolderId="ref" />))
    await screen.findByText('Procédures')

    fireEvent.click(screen.getByRole('button', { name: /Dossier$/ }))
    const select = (await screen.findByTestId('new-folder-location')) as HTMLSelectElement
    expect(select.value).toBe('ref')
    expect(screen.getByText('Nouveau dossier')).toBeTruthy()
  })

  it('peut être réduit', async () => {
    render(wrap(<SpaceTreeSidebar spaceId={SPACE} />))
    await screen.findByText('Procédures')
    fireEvent.click(screen.getByRole('button', { name: "Réduire l'arborescence" }))
    expect(screen.queryByText('Procédures')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: "Agrandir l'arborescence" }))
    await screen.findByText('Procédures')
  })
})
