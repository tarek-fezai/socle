// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'

const getMock = vi.fn()
const postMock = vi.fn()
const patchMock = vi.fn()
const deleteMock = vi.fn()
vi.mock('../lib/api', () => ({
  api: {
    get: (...args: unknown[]) => getMock(...args),
    post: (...args: unknown[]) => postMock(...args),
    patch: (...args: unknown[]) => patchMock(...args),
    delete: (...args: unknown[]) => deleteMock(...args),
  },
}))

vi.mock('./DocumentEditor', () => ({
  DocumentEditor: ({ templateTools }: { templateTools?: boolean }) => (
    <div data-testid="editor" data-template-tools={String(Boolean(templateTools))} />
  ),
}))

import { TemplatesAdminPage } from './TemplatesAdminPage'

// La liste ne contient pas `body` (TemplateSummary) ; le détail le contient.
const DETAIL_BODY = { type: 'doc', content: [{ type: 'placeholder', attrs: { hint: 'x' } }] }
const base = {
  defaultTagIds: [],
  version: 1,
  canManage: true,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
}
const TEMPLATES = [
  { ...base, id: 'g1', name: 'Politique', description: 'Règles', docType: 'politique', scope: 'global', spaceId: null, seedKey: 'politique', canManage: false },
  { ...base, id: 'g2', name: 'Fiche fournisseur', description: null, docType: null, scope: 'global', spaceId: null },
  { ...base, id: 'sp1', name: 'Revue d’accès', description: null, docType: null, scope: 'space', spaceId: 's1' },
]

function renderPage() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <TemplatesAdminPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('TemplatesAdminPage', () => {
  beforeEach(() => {
    getMock.mockReset()
    postMock.mockReset()
    patchMock.mockReset()
    deleteMock.mockReset()
    getMock.mockImplementation((url: string, config?: { params?: { spaceId?: string } }) => {
      if (url === '/api/v1/spaces')
        return Promise.resolve({
          data: [{ id: 's1', name: 'Identité & accès', color: null, canManage: true, isOwner: true, isResponsible: false }],
        })
      if (url === '/api/v1/templates')
        return Promise.resolve({
          data: config?.params?.spaceId ? TEMPLATES : TEMPLATES.filter((t) => !t.spaceId),
        })
      const detail = url.match(/^\/api\/v1\/templates\/(.+)$/)
      if (detail) {
        const t = TEMPLATES.find((x) => x.id === detail[1])
        return t ? Promise.resolve({ data: { ...t, body: DETAIL_BODY } }) : Promise.reject(new Error('404'))
      }
      return Promise.reject(new Error(`unexpected ${url}`))
    })
  })

  it('liste les modèles globaux ; pas de modifier/supprimer sans canManage', async () => {
    renderPage()
    expect(await screen.findByRole('heading', { name: 'Modèles' })).toBeTruthy()
    const section = await screen.findByRole('region', { name: 'Modèles globaux' })
    expect(within(section).getByText('Politique')).toBeTruthy()
    expect(within(section).queryByRole('button', { name: 'Modifier Politique' })).toBeNull()
    expect(within(section).queryByRole('button', { name: 'Supprimer Politique' })).toBeNull()
    expect(within(section).getByRole('button', { name: 'Dupliquer Politique' })).toBeTruthy()
    expect(within(section).getByRole('button', { name: 'Modifier Fiche fournisseur' })).toBeTruthy()
    expect(screen.queryByText('Revue d’accès')).toBeNull()
  })

  it('le sélecteur d’espace affiche aussi les modèles de l’espace', async () => {
    renderPage()
    await screen.findByRole('region', { name: 'Modèles globaux' })
    fireEvent.change(screen.getByRole('combobox', { name: 'Espace' }), { target: { value: 's1' } })
    const spaceSection = await screen.findByRole('region', { name: /Modèles de l’espace/ })
    expect(within(spaceSection).getByText('Revue d’accès')).toBeTruthy()
    expect(getMock).toHaveBeenCalledWith('/api/v1/templates', { params: { spaceId: 's1' } })
  })

  it('crée un modèle avec l’éditeur en mode templateTools', async () => {
    postMock.mockResolvedValue({ data: { ...base, id: 'new', name: 'Nouveau', spaceId: null } })
    renderPage()
    await screen.findByRole('region', { name: 'Modèles globaux' })
    fireEvent.click(screen.getByRole('button', { name: '+ Nouveau modèle' }))

    expect(screen.getByTestId('editor').getAttribute('data-template-tools')).toBe('true')
    fireEvent.change(screen.getByLabelText('Nom du modèle'), { target: { value: 'Compte-rendu' } })
    fireEvent.change(screen.getByLabelText('Type de document'), { target: { value: 'cr' } })
    fireEvent.change(screen.getByLabelText('Tags par défaut'), { target: { value: 'a, b' } })
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer le modèle' }))

    await waitFor(() => expect(postMock).toHaveBeenCalled())
    expect(postMock.mock.calls[0][0]).toBe('/api/v1/templates')
    expect(postMock.mock.calls[0][1]).toMatchObject({
      name: 'Compte-rendu',
      docType: 'cr',
      defaultTagIds: ['a', 'b'],
    })
    expect(postMock.mock.calls[0][1].spaceId).toBeUndefined()
  })

  it('charge le détail (corps) avant d’éditer et envoie un PATCH', async () => {
    patchMock.mockResolvedValue({ data: { ...TEMPLATES[1], body: DETAIL_BODY } })
    renderPage()
    fireEvent.click(await screen.findByRole('button', { name: 'Modifier Fiche fournisseur' }))

    expect(await screen.findByTestId('editor')).toBeTruthy()
    expect(getMock).toHaveBeenCalledWith('/api/v1/templates/g2')
    fireEvent.change(screen.getByLabelText('Nom du modèle'), { target: { value: 'Fiche v2' } })
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer le modèle' }))

    await waitFor(() => expect(patchMock).toHaveBeenCalled())
    expect(patchMock.mock.calls[0][0]).toBe('/api/v1/templates/g2')
    expect(patchMock.mock.calls[0][1]).toMatchObject({ name: 'Fiche v2', body: DETAIL_BODY })
  })

  it('supprime un modèle après confirmation', async () => {
    deleteMock.mockResolvedValue({})
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    renderPage()
    fireEvent.click(await screen.findByRole('button', { name: 'Supprimer Fiche fournisseur' }))
    await waitFor(() => expect(deleteMock).toHaveBeenCalledWith('/api/v1/templates/g2'))
  })
})
