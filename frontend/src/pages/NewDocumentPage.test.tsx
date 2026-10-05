// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
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
  },
}))

import { NewDocumentPage } from './NewDocumentPage'

const SPACES = [
  { id: 's1', name: 'Identité & accès', color: null, canManage: true, isOwner: true, isResponsible: false },
  { id: 's2', name: 'Finance', color: null, canManage: true, isOwner: false, isResponsible: false },
]

const TREE = {
  spaceId: 's1',
  spaceName: 'Identité & accès',
  folders: [
    { id: 'proc', name: 'Procédures', parentFolderId: null, position: 0, documentCount: 0, folderCount: 0 },
  ],
  documents: [],
}

const TEMPLATES = [
  {
    id: 't-pol',
    name: 'Politique',
    description: 'Règles et principes normatifs',
    docType: 'politique',
    defaultTagIds: [],
    scope: 'global',
    spaceId: null,
    version: 1,
    canManage: false,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
  },
  {
    id: 't-fiche',
    name: 'Fiche fournisseur',
    description: 'Coordonnées et contact sécurité',
    docType: null,
    defaultTagIds: [],
    scope: 'space',
    spaceId: 's1',
    version: 1,
    canManage: true,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
  },
]

function renderPage(url = '/docs/new') {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[url]}>
        <Routes>
          <Route path="/docs/new" element={<NewDocumentPage />} />
          <Route path="/docs/:id/edit" element={<div>Page document</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('NewDocumentPage', () => {
  let warnings: string[]

  beforeEach(() => {
    getMock.mockReset()
    postMock.mockReset()
    warnings = []
    getMock.mockImplementation((url: string) => {
      if (url === '/api/v1/spaces') return Promise.resolve({ data: SPACES })
      if (url === '/api/v1/spaces/s1/tree') return Promise.resolve({ data: TREE })
      if (url === '/api/v1/templates') return Promise.resolve({ data: TEMPLATES })
      if (/\/api\/v1\/templates\/[^/]+\/creation-warnings/.test(url))
        return Promise.resolve({
          data: {
            templateId: 't-pol',
            spaceId: 's1',
            warnings: warnings.map((message) => ({
              code: 'transclusion_restricted',
              message,
              targetDocumentIds: ['doc-x'],
            })),
          },
        })
      return Promise.reject(new Error(`unexpected ${url}`))
    })
  })

  it('étape 1 : espace + dossier, « Continuer » désactivé sans espace', async () => {
    renderPage()
    const next = screen.getByRole('button', { name: 'Continuer' }) as HTMLButtonElement
    expect(next.disabled).toBe(true)

    fireEvent.click(await screen.findByRole('radio', { name: 'Identité & accès' }))
    expect(next.disabled).toBe(false)

    // Dossiers de l'espace sélectionné
    expect(await screen.findByRole('radio', { name: 'Procédures' })).toBeTruthy()
    expect(screen.getByRole('radio', { name: 'Identité & accès (racine)' }).getAttribute('aria-checked')).toBe(
      'true',
    )
  })

  it('présélectionne espace et dossier depuis la query string', async () => {
    renderPage('/docs/new?spaceId=s1&folderId=proc')
    const folder = await screen.findByRole('radio', { name: 'Procédures' })
    expect(folder.getAttribute('aria-checked')).toBe('true')
    expect(screen.getByRole('radio', { name: 'Identité & accès' }).getAttribute('aria-checked')).toBe('true')
  })

  it('parcourt les 3 étapes et crée le document depuis un modèle', async () => {
    postMock.mockResolvedValue({ data: { id: 'new-doc', spaceId: 's1', title: 'Ma politique' } })
    renderPage('/docs/new?spaceId=s1&folderId=proc')

    // Étape 1 → 2
    await screen.findByRole('radio', { name: 'Procédures' })
    fireEvent.click(screen.getByRole('button', { name: 'Continuer' }))

    // Étape 2 : modèles, recherche, page vierge
    expect(await screen.findByTestId('step-2')).toBeTruthy()
    expect(await screen.findByRole('radio', { name: /Politique/ })).toBeTruthy()
    expect(screen.getByRole('radio', { name: /Page vierge/ }).getAttribute('aria-checked')).toBe('true')
    expect(getMock).toHaveBeenCalledWith('/api/v1/templates', { params: { spaceId: 's1' } })

    fireEvent.change(screen.getByLabelText('Rechercher un modèle'), { target: { value: 'fournisseur' } })
    expect(screen.queryByRole('radio', { name: /Politique/ })).toBeNull()
    expect(screen.getByRole('radio', { name: /Fiche fournisseur/ })).toBeTruthy()

    fireEvent.change(screen.getByLabelText('Rechercher un modèle'), { target: { value: '' } })
    fireEvent.click(await screen.findByRole('radio', { name: /Politique/ }))
    fireEvent.click(screen.getByRole('button', { name: 'Continuer' }))

    // Étape 3 : titre + récapitulatif
    const step3 = await screen.findByTestId('step-3')
    expect(within(step3).getByTestId('recap-template').textContent).toBe('Politique')
    expect(within(step3).getByTestId('recap-location').textContent).toContain('Procédures')
    const submit = within(step3).getByRole('button', { name: 'Créer le document' }) as HTMLButtonElement
    expect(submit.disabled).toBe(true)

    fireEvent.change(within(step3).getByLabelText('Titre du document'), {
      target: { value: 'Ma politique' },
    })
    await waitFor(() => expect(submit.disabled).toBe(false))
    fireEvent.click(submit)

    await waitFor(() => expect(postMock).toHaveBeenCalledTimes(1))
    const [url, payload] = postMock.mock.calls[0]
    expect(url).toBe('/api/v1/documents')
    expect(payload).toEqual({
      title: 'Ma politique',
      spaceId: 's1',
      folderId: 'proc',
      templateId: 't-pol',
    })
    expect(await screen.findByText('Page document')).toBeTruthy()
  })

  it('page vierge : corps vide, pas de templateId', async () => {
    postMock.mockResolvedValue({ data: { id: 'blank', spaceId: 's1', title: 'Note' } })
    renderPage('/docs/new?spaceId=s1')
    await screen.findByRole('radio', { name: 'Procédures' })
    fireEvent.click(screen.getByRole('button', { name: 'Continuer' }))
    await screen.findByTestId('step-2')
    fireEvent.click(screen.getByRole('button', { name: 'Continuer' }))
    fireEvent.change(await screen.findByLabelText('Titre du document'), { target: { value: 'Note' } })
    fireEvent.click(screen.getByRole('button', { name: 'Créer le document' }))

    await waitFor(() => expect(postMock).toHaveBeenCalled())
    const payload = postMock.mock.calls[0][1]
    expect(payload.templateId).toBeUndefined()
    expect(payload.body).toEqual({ type: 'doc', content: [{ type: 'paragraph' }] })
    expect(payload.folderId).toBeUndefined()
  })

  it('affiche les avertissements de création avant de soumettre', async () => {
    warnings = ['Le modèle contient une transclusion restreinte.']
    renderPage('/docs/new?spaceId=s1')
    await screen.findByRole('radio', { name: 'Procédures' })
    fireEvent.click(screen.getByRole('button', { name: 'Continuer' }))
    fireEvent.click(await screen.findByRole('radio', { name: /Politique/ }))
    fireEvent.click(screen.getByRole('button', { name: 'Continuer' }))

    const alert = await screen.findByTestId('creation-warnings')
    expect(alert.textContent).toContain('transclusion restreinte')
    expect(getMock).toHaveBeenCalledWith('/api/v1/templates/t-pol/creation-warnings', {
      params: { spaceId: 's1' },
    })
    expect(screen.getByRole('button', { name: 'Créer quand même' })).toBeTruthy()
  })

  it('« Retour » revient à l’étape précédente', async () => {
    renderPage('/docs/new?spaceId=s1')
    await screen.findByRole('radio', { name: 'Procédures' })
    fireEvent.click(screen.getByRole('button', { name: 'Continuer' }))
    await screen.findByTestId('step-2')
    fireEvent.click(screen.getByRole('button', { name: 'Retour' }))
    expect(await screen.findByTestId('step-1')).toBeTruthy()
  })
})
