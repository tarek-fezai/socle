// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
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
    createdBy: 'u1',
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
  },
]

const TAG_SUGGESTIONS = [{ id: 'tag-iam', name: 'IAM' }]

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

const spaceSelect = () => screen.getByLabelText('Espace') as HTMLSelectElement
const folderSelect = () => screen.getByLabelText("Emplacement dans l'arborescence") as HTMLSelectElement
const submitButton = () => screen.getByRole('button', { name: /Créer/ }) as HTMLButtonElement

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
      if (url === '/api/v1/tags') return Promise.resolve({ data: TAG_SUGGESTIONS })
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

  it('formulaire unique : tout est visible sans assistant', async () => {
    renderPage()
    expect(screen.getByRole('heading', { name: 'Créer un document' })).toBeTruthy()
    expect(screen.getByRole('radio', { name: /Document vierge/ }).getAttribute('aria-checked')).toBe('true')
    expect(spaceSelect()).toBeTruthy()
    expect(folderSelect()).toBeTruthy()
    expect(screen.getByLabelText('Titre du document')).toBeTruthy()
    expect(screen.getByLabelText(/Tags/)).toBeTruthy()
    expect(screen.getByText('Fiabilité cible')).toBeTruthy()
    expect(screen.queryByRole('button', { name: 'Continuer' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Retour' })).toBeNull()
    expect(screen.getByRole('button', { name: 'Annuler' })).toBeTruthy()
  })

  it('crée avec espace + dossier choisis dans les listes', async () => {
    postMock.mockResolvedValue({ data: { id: 'new-doc', spaceId: 's1', title: 'Ma note' } })
    renderPage()

    expect(submitButton().disabled).toBe(true)
    expect(folderSelect().disabled).toBe(true)

    await screen.findByRole('option', { name: 'Finance' })
    fireEvent.change(spaceSelect(), { target: { value: 's1' } })
    await screen.findByRole('option', { name: 'Procédures' })
    expect(folderSelect().disabled).toBe(false)
    expect(getMock).toHaveBeenCalledWith('/api/v1/templates', { params: { spaceId: 's1' } })
    fireEvent.change(folderSelect(), { target: { value: 'proc' } })

    fireEvent.change(screen.getByLabelText('Titre du document'), { target: { value: 'Ma note' } })
    await waitFor(() => expect(submitButton().disabled).toBe(false))
    fireEvent.click(submitButton())

    await waitFor(() => expect(postMock).toHaveBeenCalledTimes(1))
    const [url, payload] = postMock.mock.calls[0]
    expect(url).toBe('/api/v1/documents')
    expect(payload).toEqual({
      title: 'Ma note',
      body: { type: 'doc', content: [{ type: 'paragraph' }] },
      spaceId: 's1',
      folderId: 'proc',
    })
    expect(await screen.findByText('Page document')).toBeTruthy()
  })

  it('présélectionne espace et dossier depuis la query string', async () => {
    renderPage('/docs/new?spaceId=s1&folderId=proc')
    await screen.findByRole('option', { name: 'Procédures' })
    await waitFor(() => expect(folderSelect().value).toBe('proc'))
    expect(spaceSelect().value).toBe('s1')
  })

  it('sélectionne automatiquement l’unique espace disponible', async () => {
    getMock.mockImplementation((url: string) => {
      if (url === '/api/v1/spaces') return Promise.resolve({ data: [SPACES[0]] })
      if (url === '/api/v1/spaces/s1/tree') return Promise.resolve({ data: TREE })
      if (url === '/api/v1/templates') return Promise.resolve({ data: [] })
      return Promise.reject(new Error(`unexpected ${url}`))
    })
    renderPage()
    await waitFor(() => expect(spaceSelect().value).toBe('s1'))
    expect(await screen.findByRole('option', { name: 'Procédures' })).toBeTruthy()
  })

  it('un dossier inconnu retombe sur la racine', async () => {
    postMock.mockResolvedValue({ data: { id: 'd', spaceId: 's1', title: 'T' } })
    renderPage('/docs/new?spaceId=s1&folderId=disparu')
    await screen.findByRole('option', { name: 'Procédures' })
    expect(folderSelect().value).toBe('')
    fireEvent.change(screen.getByLabelText('Titre du document'), { target: { value: 'T' } })
    fireEvent.click(submitButton())
    await waitFor(() => expect(postMock).toHaveBeenCalled())
    expect(postMock.mock.calls[0][1].folderId).toBeUndefined()
  })

  it('crée depuis un modèle : pas de corps, templateId transmis', async () => {
    postMock.mockResolvedValue({ data: { id: 'new-doc', spaceId: 's1', title: 'Ma politique' } })
    renderPage('/docs/new?spaceId=s1&folderId=proc')

    fireEvent.click(await screen.findByRole('radio', { name: /Politique/ }))
    expect(screen.getByRole('radio', { name: /Politique/ }).getAttribute('aria-checked')).toBe('true')
    fireEvent.change(screen.getByLabelText('Titre du document'), { target: { value: 'Ma politique' } })
    await waitFor(() => expect(submitButton().disabled).toBe(false))
    fireEvent.click(submitButton())

    await waitFor(() => expect(postMock).toHaveBeenCalledTimes(1))
    expect(postMock.mock.calls[0][1]).toEqual({
      title: 'Ma politique',
      spaceId: 's1',
      folderId: 'proc',
      templateId: 't-pol',
    })
    expect(await screen.findByText('Page document')).toBeTruthy()
  })

  it('ordre des modèles : système, Document vierge, personnalisés', async () => {
    renderPage('/docs/new?spaceId=s1')
    await screen.findByRole('radio', { name: /Fiche fournisseur/ })
    const names = screen
      .getAllByRole('radio')
      .map((r) => r.textContent ?? '')
      .map((t) => (t.includes('Politique') ? 'sys' : t.includes('vierge') ? 'blank' : 'custom'))
    expect(names).toEqual(['sys', 'blank', 'custom'])
  })

  it('document vierge : corps vide, pas de templateId', async () => {
    postMock.mockResolvedValue({ data: { id: 'blank', spaceId: 's1', title: 'Note' } })
    renderPage('/docs/new?spaceId=s1')
    await screen.findByRole('option', { name: 'Procédures' })
    fireEvent.change(screen.getByLabelText('Titre du document'), { target: { value: 'Note' } })
    fireEvent.click(submitButton())

    await waitFor(() => expect(postMock).toHaveBeenCalled())
    const payload = postMock.mock.calls[0][1]
    expect(payload.templateId).toBeUndefined()
    expect(payload.body).toEqual({ type: 'doc', content: [{ type: 'paragraph' }] })
    expect(payload.folderId).toBeUndefined()
  })

  it('affiche les avertissements de création quand un modèle est sélectionné', async () => {
    warnings = ['Le modèle contient une transclusion restreinte.']
    renderPage('/docs/new?spaceId=s1')
    fireEvent.click(await screen.findByRole('radio', { name: /Politique/ }))

    const alert = await screen.findByTestId('creation-warnings')
    expect(alert.textContent).toContain('transclusion restreinte')
    expect(getMock).toHaveBeenCalledWith('/api/v1/templates/t-pol/creation-warnings', {
      params: { spaceId: 's1' },
    })
    expect(screen.getByRole('button', { name: 'Créer quand même' })).toBeTruthy()
  })

  it('affiche l’erreur de création et reste sur la page', async () => {
    postMock.mockRejectedValue(new Error('refus'))
    renderPage('/docs/new?spaceId=s1')
    await screen.findByRole('option', { name: 'Procédures' })
    fireEvent.change(screen.getByLabelText('Titre du document'), { target: { value: 'Note' } })
    fireEvent.click(submitButton())

    const alert = await screen.findByText(/Création refusée|refus/)
    expect(alert).toBeTruthy()
    expect(screen.queryByText('Page document')).toBeNull()
    expect(postMock).toHaveBeenCalledTimes(1)
  })

  it('rattache les tags choisis (existant + nouveau) après la création', async () => {
    postMock.mockImplementation((url: string) => {
      if (url === '/api/v1/documents')
        return Promise.resolve({ data: { id: 'new-doc', spaceId: 's1', title: 'Ma note' } })
      if (url === '/api/v1/documents/new-doc/tags')
        return Promise.resolve({ data: { id: 'x', name: 'x' } })
      return Promise.reject(new Error(`unexpected ${url}`))
    })
    renderPage('/docs/new?spaceId=s1')
    await screen.findByRole('option', { name: 'Procédures' })
    fireEvent.change(screen.getByLabelText('Titre du document'), { target: { value: 'Ma note' } })

    // Tag existant via la suggestion
    const tagInput = screen.getByLabelText(/Tags/)
    fireEvent.focus(tagInput)
    fireEvent.click(await screen.findByRole('button', { name: 'IAM' }))
    expect(screen.getByRole('button', { name: 'Retirer le tag IAM' })).toBeTruthy()

    // Nouveau tag saisi + Entrée
    fireEvent.change(tagInput, { target: { value: 'Nouveau' } })
    fireEvent.keyDown(tagInput, { key: 'Enter' })
    expect(screen.getByRole('button', { name: 'Retirer le tag Nouveau' })).toBeTruthy()

    fireEvent.click(submitButton())

    await waitFor(() => expect(postMock).toHaveBeenCalledTimes(3))
    expect(postMock.mock.calls[0][0]).toBe('/api/v1/documents')
    const tagCalls = postMock.mock.calls.slice(1)
    expect(tagCalls.map((c) => c[0])).toEqual([
      '/api/v1/documents/new-doc/tags',
      '/api/v1/documents/new-doc/tags',
    ])
    expect(tagCalls.map((c) => c[1])).toEqual([{ tagId: 'tag-iam' }, { name: 'Nouveau' }])
    expect(await screen.findByText('Page document')).toBeTruthy()
  })

  it('un tag retiré n’est pas rattaché ; sans tag, aucun appel /tags', async () => {
    postMock.mockResolvedValue({ data: { id: 'new-doc', spaceId: 's1', title: 'Ma note' } })
    renderPage('/docs/new?spaceId=s1')
    await screen.findByRole('option', { name: 'Procédures' })
    fireEvent.change(screen.getByLabelText('Titre du document'), { target: { value: 'Ma note' } })

    const tagInput = screen.getByLabelText(/Tags/)
    fireEvent.change(tagInput, { target: { value: 'Temp' } })
    fireEvent.keyDown(tagInput, { key: 'Enter' })
    fireEvent.click(screen.getByRole('button', { name: 'Retirer le tag Temp' }))
    expect(screen.queryByRole('button', { name: 'Retirer le tag Temp' })).toBeNull()

    fireEvent.click(submitButton())
    await waitFor(() => expect(postMock).toHaveBeenCalledTimes(1))
    expect(postMock.mock.calls[0][0]).toBe('/api/v1/documents')
  })

  it('ne bloque pas l’ouverture du document si le rattachement d’un tag échoue', async () => {
    postMock.mockImplementation((url: string) => {
      if (url === '/api/v1/documents')
        return Promise.resolve({ data: { id: 'new-doc', spaceId: 's1', title: 'Ma note' } })
      return Promise.reject(new Error('tag refusé'))
    })
    renderPage('/docs/new?spaceId=s1')
    await screen.findByRole('option', { name: 'Procédures' })
    fireEvent.change(screen.getByLabelText('Titre du document'), { target: { value: 'Ma note' } })
    const tagInput = screen.getByLabelText(/Tags/)
    fireEvent.change(tagInput, { target: { value: 'Fragile' } })
    fireEvent.keyDown(tagInput, { key: 'Enter' })
    fireEvent.click(submitButton())
    expect(await screen.findByText('Page document')).toBeTruthy()
  })
})
