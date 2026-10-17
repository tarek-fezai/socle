// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'

const getMock = vi.fn()
const postMock = vi.fn()
const putMock = vi.fn()
const authState = vi.hoisted(() => ({ roles: [] as string[] }))
vi.mock('../auth/AuthProvider', () => ({
  useAuth: () => ({
    me: { id: 'me-1', displayName: 'Tarek Fezai', avatarInitials: 'TF', roles: authState.roles },
  }),
}))

vi.mock('../lib/api', () => ({
  api: {
    get: (...args: unknown[]) => getMock(...args),
    post: (...args: unknown[]) => postMock(...args),
    patch: vi.fn(),
    put: (...args: unknown[]) => putMock(...args),
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

const perms = (canEdit: boolean) => ({
  canEdit,
  canPublish: false,
  canManageAccess: false,
  canComment: canEdit,
  canManageAttestations: false,
})

let pageResponse: { status: number; data: unknown } = { status: 204, data: '' }
let folderRights = { canEdit: true, canDelete: true }
let openThreads = 0

const folderDocuments = [
  {
    id: 'prov',
    title: 'Provisioning des comptes',
    status: 'valide',
    docType: 'procedure',
    updatedAt: '2026-09-12T10:00:00.000Z',
    updatedBy: 'Tarek Fezai',
    reviewSince: null,
    tags: [{ id: 't1', name: 'IAM', color: '#3730E0', governed: false }],
  },
  {
    id: 'reg',
    title: 'Registre',
    status: 'en_revue',
    docType: 'procedure',
    updatedAt: '2026-08-01T10:00:00.000Z',
    updatedBy: 'Claire Dubois',
    reviewSince: '2026-08-01T10:00:00.000Z',
    tags: [],
  },
]

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
          <Route path="/docs/new" element={<div>Nouveau depuis un modèle</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('FolderPage', () => {
  beforeEach(() => {
    getMock.mockReset()
    postMock.mockReset()
    putMock.mockReset()
    putMock.mockResolvedValue({
      data: { id: 'page-1', permissions: { canEdit: true }, currentVersionNo: 1 },
    })
    authState.roles = []
    pageResponse = { status: 204, data: '' }
    folderRights = { canEdit: true, canDelete: true }
    openThreads = 0
    getMock.mockImplementation((url: string) => {
      if (url === '/api/v1/folders/proc') {
        return Promise.resolve({
          data: {
            id: 'proc',
            spaceId: SPACE,
            parentFolderId: null,
            name: 'Procédures',
            position: 0,
            language: 'fr',
            documents: folderDocuments,
            ownerGroup: {
              id: 'g1',
              name: 'Équipe Identité',
              owner: { id: 'me-1', displayName: 'Tarek Fezai', initials: 'TF' },
            },
            ...folderRights,
          },
        })
      }
      if (url === `/api/v1/spaces/${SPACE}/tree`) return Promise.resolve({ data: tree })
      if (url === '/api/v1/folders/proc/page') return Promise.resolve(pageResponse)
      if (String(url).includes('/comments')) {
        return Promise.resolve({
          data: {
            documentId: 'page-1',
            versionNo: 1,
            threads: [],
            detached: [],
            openThreadCount: openThreads,
          },
        })
      }
      if (String(url).startsWith('/api/v1/documents/')) {
        return Promise.resolve({
          data: {
            id: String(url).split('/').pop(),
            title: '',
            status: 'valide',
            docType: null,
            updatedAt: '2026-09-12T10:00:00.000Z',
            updatedBy: { id: 'u1', displayName: 'Tarek Fezai' },
            tags: [],
            permissions: perms(true),
            body: { type: 'doc', content: [{ type: 'paragraph' }] },
            spaceId: SPACE,
          },
        })
      }
      if (String(url).includes('/favorites/')) return Promise.resolve({ data: { favorited: false } })
      return Promise.reject(new Error(`unexpected ${url}`))
    })
  })

  it('affiche sous-dossiers, documents du dossier et fil d’Ariane', async () => {
    renderPage()
    expect(await screen.findByRole('heading', { name: 'Procédures' })).toBeTruthy()

    const crumbs = screen.getByRole('navigation', { name: "Fil d'Ariane" })
    const spaceLink = await within(crumbs).findByRole('link', { name: 'Identité & accès' })
    expect(spaceLink.getAttribute('href')).toBe('/spaces/s1')
    expect(within(crumbs).getByText('Procédures').getAttribute('aria-current')).toBe('page')

    expect(screen.queryByText('Revues')).toBeNull()
    expect(screen.getAllByText('Provisioning des comptes').length).toBeGreaterThan(0)
    expect(screen.getAllByText('Registre').length).toBeGreaterThan(0)
    expect(screen.queryByText('Politique')).toBeNull()
    expect(screen.getByText(/2 documents · sous-dossier de l'espace/)).toBeTruthy()
    expect(screen.getByText(/En revue depuis le 1 août 2026/)).toBeTruthy()

    const urls = getMock.mock.calls.map((call) => String(call[0]))
    expect(urls.filter((url) => url.startsWith('/api/v1/documents/'))).toEqual([])
    expect(urls.filter((url) => url === '/api/v1/folders/proc')).toHaveLength(1)
  })

  it('« + Nouveau document » crée dans ce dossier puis ouvre le document', async () => {
    postMock.mockResolvedValue({ data: { id: 'new-doc', spaceId: SPACE, title: 'Sans titre' } })
    renderPage()
    fireEvent.click(await screen.findByRole('button', { name: '+ Nouveau document' }))

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

  it('lecteur : page en lecture seule, sans barre ni création', async () => {
    folderRights = { canEdit: false, canDelete: false }
    pageResponse = {
      status: 200,
      data: {
        id: 'page-1',
        title: 'Procédures',
        body: { type: 'doc', content: [{ type: 'paragraph', content: [{ type: 'text', text: 'Déjà écrit' }] }] },
        status: 'brouillon',
        spaceId: SPACE,
        permissions: perms(false),
      },
    }
    renderPage()
    expect(await screen.findByRole('heading', { name: 'Procédures' })).toBeTruthy()
    expect(await screen.findByText('Déjà écrit')).toBeTruthy()
    expect(screen.getByRole('tab', { name: 'Lire' }).getAttribute('aria-selected')).toBe('true')
    expect(screen.queryByRole('tab', { name: 'Modifier' })).toBeNull()
    expect(screen.queryByRole('toolbar', { name: 'Mise en forme' })).toBeNull()
    expect(screen.queryByRole('button', { name: '+ Sous-dossier' })).toBeNull()
    expect(screen.queryByRole('button', { name: '+ Nouveau document' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Renommer le dossier' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Autres actions du dossier' })).toBeNull()
    expect(screen.queryByText('Page de dossier modifiable — comme un document')).toBeNull()
  })

  it('contributeur : Lire sans barre d’outils, avec création et Modifier', async () => {
    pageResponse = {
      status: 200,
      data: {
        id: 'page-1',
        title: 'Procédures',
        body: { type: 'doc', content: [{ type: 'paragraph' }] },
        status: 'brouillon',
        spaceId: SPACE,
        permissions: perms(true),
      },
    }
    renderPage()
    expect(await screen.findByRole('heading', { name: 'Procédures' })).toBeTruthy()
    expect(screen.queryByRole('toolbar', { name: 'Mise en forme' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Renommer le dossier' })).toBeNull()
    expect(screen.getByRole('tab', { name: 'Lire' })).toBeTruthy()
    expect(screen.getByRole('tab', { name: 'Modifier' })).toBeTruthy()
    expect(screen.getByRole('tab', { name: 'Historique' })).toBeTruthy()
    expect(screen.getByRole('tab', { name: 'Commentaires' })).toBeTruthy()
    expect(screen.getByRole('tab', { name: 'Accès' }).getAttribute('href')).toBe('/folders/proc/access')
    expect(screen.getByRole('link', { name: 'Graphe' }).getAttribute('href')).toBe('/spaces/s1/graph')
    expect((screen.getByRole('button', { name: 'Index' }) as HTMLButtonElement).disabled).toBe(true)
    expect(screen.getByRole('button', { name: '+ Sous-dossier' })).toBeTruthy()
    expect(screen.getByRole('button', { name: '+ Nouveau document' })).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Autres actions du dossier' })).toBeTruthy()
  })

  it('page 204 : Modifier suit canEdit, Lire reste en lecture', async () => {
    const first = renderPage()
    expect(await screen.findByRole('tab', { name: 'Modifier' })).toBeTruthy()
    expect(screen.queryByRole('toolbar', { name: 'Mise en forme' })).toBeNull()
    expect(screen.getByRole('button', { name: 'Autres actions du dossier' })).toBeTruthy()
    first.unmount()

    folderRights = { canEdit: false, canDelete: false }
    renderPage()
    expect(await screen.findByRole('heading', { name: 'Procédures' })).toBeTruthy()
    expect(screen.queryByRole('tab', { name: 'Modifier' })).toBeNull()
    expect(screen.queryByRole('toolbar', { name: 'Mise en forme' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Autres actions du dossier' })).toBeNull()
  })

  it('Modifier enregistre la page puis revient à Lire', async () => {
    renderPage()
    fireEvent.click(await screen.findByRole('tab', { name: 'Modifier' }))
    expect(await screen.findByRole('toolbar', { name: 'Mise en forme' })).toBeTruthy()
    fireEvent.click(screen.getByTestId('edit-save-version'))
    fireEvent.click(await screen.findByTestId('edit-save-version-confirm'))
    await waitFor(() =>
      expect(putMock).toHaveBeenCalledWith(
        '/api/v1/folders/proc/page',
        expect.objectContaining({ body: expect.any(Object) }),
      ),
    )
    await waitFor(() => expect(screen.queryByRole('toolbar', { name: 'Mise en forme' })).toBeNull())
    expect(screen.getByRole('tab', { name: 'Lire' }).getAttribute('aria-selected')).toBe('true')
  })

  it('Commentaires vide sans page', async () => {
    renderPage()
    fireEvent.click(await screen.findByRole('tab', { name: 'Commentaires' }))
    expect(await screen.findByTestId('folder-comments-empty')).toBeTruthy()
    expect(screen.queryByTestId('comments-panel')).toBeNull()
  })

  it('le compteur de commentaires suit le document de page', async () => {
    openThreads = 3
    pageResponse = {
      status: 200,
      data: {
        id: 'page-1',
        title: 'Procédures',
        body: { type: 'doc', content: [{ type: 'paragraph' }] },
        status: 'brouillon',
        currentVersionNo: 1,
        spaceId: SPACE,
        permissions: perms(true),
      },
    }
    renderPage()
    expect((await screen.findByTestId('comments-badge')).textContent).toBe('3')
  })

  it('contributeur : menu sans Supprimer', async () => {
    folderRights = { canEdit: true, canDelete: false }
    renderPage()
    fireEvent.click(await screen.findByRole('button', { name: 'Autres actions du dossier' }))
    const menu = await screen.findByRole('menu')
    expect(within(menu).getByRole('menuitem', { name: 'Renommer' })).toBeTruthy()
    expect(within(menu).getByRole('menuitem', { name: 'Déplacer' })).toBeTruthy()
    expect(within(menu).getByRole('menuitem', { name: 'Depuis un modèle' })).toBeTruthy()
    expect(within(menu).queryByRole('menuitem', { name: 'Supprimer' })).toBeNull()
    fireEvent.keyDown(menu, { key: 'Escape' })
    expect(screen.queryByRole('menu')).toBeNull()
  })

  it('contributeur autorisé à supprimer : le menu confirme dans la page', async () => {
    const confirm = vi.spyOn(window, 'confirm').mockImplementation(() => {
      throw new Error('confirm')
    })
    renderPage()
    fireEvent.click(await screen.findByRole('button', { name: 'Autres actions du dossier' }))
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Supprimer' }))
    expect(screen.getByText('Déplacer ce dossier vers la corbeille ?')).toBeTruthy()
    expect(screen.getByTestId('folder-delete-confirm')).toBeTruthy()
    expect(confirm).not.toHaveBeenCalled()
    confirm.mockRestore()
  })

  it('Déplacer ouvre la boîte existante, Depuis un modèle quitte vers /docs/new', async () => {
    renderPage()
    fireEvent.click(await screen.findByRole('button', { name: 'Autres actions du dossier' }))
    fireEvent.click(screen.getByRole('menuitem', { name: 'Déplacer' }))
    const dialog = await screen.findByRole('dialog', { name: 'Déplacer' })
    expect(dialog).toBeTruthy()
    fireEvent.click(within(dialog).getByRole('button', { name: 'Fermer' }))

    fireEvent.click(screen.getByRole('button', { name: 'Autres actions du dossier' }))
    fireEvent.click(screen.getByRole('menuitem', { name: 'Depuis un modèle' }))
    expect(await screen.findByText('Nouveau depuis un modèle')).toBeTruthy()
  })

  it('Gérer les tags n’est visible que pour un administrateur', async () => {
    renderPage()
    expect(await screen.findByRole('heading', { name: 'Procédures' })).toBeTruthy()
    expect(screen.queryByRole('link', { name: 'Gérer →' })).toBeNull()

    authState.roles = ['ADMINISTRATEUR_SYSTEME']
    renderPage()
    expect(await screen.findByRole('link', { name: 'Gérer →' })).toBeTruthy()
  })
})
