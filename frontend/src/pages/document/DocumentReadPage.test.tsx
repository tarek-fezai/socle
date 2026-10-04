// SPDX-License-Identifier: AGPL-3.0-or-later
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'

const getMock = vi.fn()
const postMock = vi.fn()
const putMock = vi.fn()
const downloadExportMock = vi.fn()

vi.mock('../../lib/api', () => ({
  api: {
    get: (...args: unknown[]) => getMock(...args),
    post: (...args: unknown[]) => postMock(...args),
    put: (...args: unknown[]) => putMock(...args),
    delete: vi.fn(),
  },
}))
vi.mock('../../auth/AuthProvider', () => ({
  useAuth: () => ({
    authenticated: false,
    me: { id: ME_ID, displayName: 'Tarek Fezai', email: 't@example.com', avatarInitials: 'TF' },
  }),
}))
vi.mock('../../lib/export', () => ({
  downloadExport: (...args: unknown[]) => downloadExportMock(...args),
}))

import { DocumentReadPage } from './DocumentReadPage'
import { DocumentViewRedirect } from './DocumentViewRedirect'

const ME_ID = '11111111-1111-1111-1111-111111111111'
const DOC = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'
const SPACE = 'dddddddd-dddd-dddd-dddd-dddddddddddd'
const CAMPAIGN = 'cccccccc-cccc-cccc-cccc-cccccccccccc'
const RELATED = 'eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee'

type Scenario = {
  editor: boolean
  owner: boolean
  status: string
  attestation: Record<string, unknown> | null
  related: { outgoing: unknown[]; incoming: unknown[] }
  spaceCanManage: boolean
}

let scenario: Scenario

function perms() {
  const canEdit = scenario.editor
  return {
    canEdit,
    canPublish: canEdit && scenario.status === 'brouillon',
    canManageAccess: canEdit || scenario.spaceCanManage || scenario.owner,
    canComment: true,
    canManageAttestations: scenario.owner,
  }
}

function baseDoc() {
  return {
    id: DOC,
    spaceId: SPACE,
    folderId: null,
    title: 'Politique de gestion des accès',
    docType: 'politique',
    body: {
      type: 'doc',
      content: [
        { type: 'paragraph', content: [{ type: 'text', text: 'Chapeau de la politique.' }] },
        { type: 'heading', attrs: { level: 2 }, content: [{ type: 'text', text: 'Principe du moindre privilège' }] },
        { type: 'paragraph', content: [{ type: 'text', text: 'Tout accès est nécessaire.' }] },
        { type: 'drawio', attrs: { xml: '<mxfile/>' } },
        { type: 'heading', attrs: { level: 2 }, content: [{ type: 'text', text: 'Rôles et périmètres' }] },
      ],
    },
    status: scenario.status,
    currentVersionNo: 12,
    createdAt: '2026-07-14T09:00:00Z',
    updatedAt: '2026-09-12T12:22:00Z',
    contentModifiedAt: '2026-09-12T12:22:00Z',
    stalenessThresholdDays: 181,
    reliabilityScore: scenario.status === 'valide' ? 91 : null,
    stale: false,
    createdBy: null,
    updatedBy: { id: ME_ID, displayName: 'Tarek Fezai', initials: 'TF' },
    owner: scenario.owner
      ? { id: ME_ID, displayName: 'Tarek Fezai', initials: 'TF' }
      : null,
    tags: [
      { id: 't1', name: 'IAM', color: '#3730E0' },
      { id: 't2', name: 'RGPD', color: '#B7791F' },
    ],
    permissions: perms(),
  }
}

function route(url: string) {
  if (url === `/api/v1/documents/${DOC}/resolved`) return { data: baseDoc() }
  if (url === `/api/v1/spaces/${SPACE}`) {
    return {
      data: {
        id: SPACE,
        name: 'Identité & accès',
        canManage: scenario.spaceCanManage,
        isOwner: false,
        isResponsible: false,
        membership: 'member',
      },
    }
  }
  if (url === `/api/v1/spaces/${SPACE}/tree`) {
    return { data: { spaceId: SPACE, spaceName: 'Identité & accès', folders: [], documents: [] } }
  }
  if (url.startsWith(`/api/v1/documents/${DOC}/comments`)) {
    return { data: { documentId: DOC, versionNo: 12, threads: [], detached: [], openThreadCount: 3 } }
  }
  if (url === `/api/v1/documents/${DOC}/feedback`) {
    return {
      data: scenario.editor
        ? { myVote: null, totals: { yes: 4, no: 1 } }
        : { myVote: null },
    }
  }
  if (url === `/api/v1/documents/${DOC}/attestations/active`) {
    return scenario.attestation
      ? { status: 200, data: scenario.attestation }
      : { status: 204, data: '' }
  }
  if (url === `/api/v1/documents/${DOC}/links`) return { data: scenario.related }
  if (url === `/api/v1/documents/${DOC}/approvals/current`) {
    return scenario.status === 'en_revue'
      ? { status: 200, data: { approvalRequestId: 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb' } }
      : { status: 204, data: undefined }
  }
  if (url.startsWith('/api/v1/favorites/')) return { data: { favorited: false } }
  throw new Error(`unmocked GET ${url}`)
}

function campaign(overrides: Record<string, unknown> = {}) {
  return {
    campaignId: CAMPAIGN,
    documentId: DOC,
    versionNo: 12,
    currentVersionNo: 12,
    audienceType: 'space_members',
    dueDate: '2026-10-03',
    overdue: false,
    acknowledged: false,
    ackCount: 742,
    audienceSize: 856,
    createdAt: '2026-09-01T00:00:00Z',
    ...overrides,
  }
}

function LocationProbe() {
  const loc = useLocation()
  return <div data-testid="loc">{`${loc.pathname}${loc.search}`}</div>
}

function renderPage(path = `/docs/${DOC}`) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/docs/:id" element={<DocumentReadPage />} />
          <Route path="/docs/:id/edit" element={<div>Éditeur</div>} />
          <Route path="/docs/:id/view" element={<DocumentViewRedirect />} />
        </Routes>
        <LocationProbe />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  scenario = {
    editor: true,
    owner: false,
    status: 'valide',
    attestation: null,
    related: { outgoing: [], incoming: [] },
    spaceCanManage: false,
  }
  getMock.mockReset()
  postMock.mockReset()
  putMock.mockReset()
  downloadExportMock.mockReset()
  getMock.mockImplementation(async (url: string) => route(url))
  postMock.mockResolvedValue({ data: {} })
  sessionStorage.clear()
})

describe('DocumentReadPage — contenu', () => {
  it('affiche titre, statut, fiabilité, révision, sommaire et blocs non supportés', async () => {
    renderPage()
    expect(await screen.findByRole('heading', { level: 1, name: 'Politique de gestion des accès' })).toBeTruthy()
    expect(screen.getByTestId('doc-status').textContent).toBe('Validé')
    expect(screen.getByTestId('doc-reliability-label').textContent).toBe('Fiabilité élevée')
    expect(screen.getByTestId('doc-revised').textContent).toMatch(/^Révisé le 12 septembre 2026$/)
    expect(screen.getByTestId('unsupported-block')).toBeTruthy()
    const toc = screen.getByTestId('doc-toc')
    expect(within(toc).getByText('Principe du moindre privilège')).toBeTruthy()
    expect(within(toc).getByText('Rôles et périmètres')).toBeTruthy()
    // Pas de sélecteur de langue
    expect(screen.queryByText(/Langue|English|Français/i)).toBeNull()
    expect(getMock).toHaveBeenCalledWith(`/api/v1/documents/${DOC}/resolved`)
    await waitFor(() => expect(postMock).toHaveBeenCalledWith(`/api/v1/documents/${DOC}/view`))
  })

  it('rail : auteur système, dernière modification, tags, fiabilité ; pas de champs personnalisés', async () => {
    renderPage()
    await screen.findByTestId('doc-toc')
    const rail = screen.getByRole('complementary', { name: /Informations/ })
    expect(within(rail).getByText('Système (migration)')).toBeTruthy()
    expect(within(rail).getByText(/Créé le 14 juillet 2026/)).toBeTruthy()
    expect(within(rail).getByText('Tarek Fezai')).toBeTruthy()
    expect(within(rail).getByText(/· v12$/)).toBeTruthy()
    expect(within(rail).getByText('IAM')).toBeTruthy()
    expect(within(rail).getByText('RGPD')).toBeTruthy()
    expect(await within(rail).findByText('Équipe Identité & accès')).toBeTruthy()
    expect(within(rail).getByText(/91% · revue à jour/)).toBeTruthy()
    expect(within(rail).queryByText('Champs personnalisés')).toBeNull()
  })

  it('liste les documents liés (et les ajoute au sommaire)', async () => {
    scenario.related = {
      outgoing: [{ id: RELATED, title: 'Procédure de provisioning des comptes' }],
      incoming: [],
    }
    renderPage()
    const section = await screen.findByTestId('related-documents')
    const link = within(section).getByRole('link', { name: /Procédure de provisioning des comptes/ })
    expect(link.getAttribute('href')).toBe(`/docs/${RELATED}`)
    expect(within(screen.getByTestId('doc-toc')).getByText('Documents liés')).toBeTruthy()
  })

  it('document introuvable → message, pas de JSON', async () => {
    getMock.mockImplementation(async (url: string) => {
      if (url.endsWith('/resolved')) throw new Error('404')
      return route(url)
    })
    renderPage()
    expect(await screen.findByText('Document introuvable ou accès refusé.')).toBeTruthy()
  })
})

describe('DocumentReadPage — permissions', () => {
  it('éditeur + brouillon : Modifier, Publier et Accès visibles', async () => {
    scenario.status = 'brouillon'
    renderPage()
    await screen.findByTestId('doc-toc')
    await waitFor(() => expect(screen.getByTestId('tab-edit')).toBeTruthy())
    expect(screen.getByTestId('tab-edit').getAttribute('href')).toBe(`/docs/${DOC}/edit`)
    expect(screen.getByTestId('doc-publish')).toBeTruthy()
    expect(screen.getByTestId('tab-access').getAttribute('href')).toBe(`/documents/${DOC}/access`)
    expect(screen.getByRole('link', { name: 'Gérer →' }).getAttribute('href')).toBe(`/docs/${DOC}/edit#metadata`)
  })

  it('éditeur mais statut validé : pas de Publier', async () => {
    scenario.status = 'valide'
    renderPage()
    await waitFor(() => expect(screen.getByTestId('tab-edit')).toBeTruthy())
    expect(screen.queryByTestId('doc-publish')).toBeNull()
  })

  it('lecteur : ni Modifier, ni Publier, ni Gérer, ni Accès', async () => {
    scenario.editor = false
    scenario.status = 'brouillon'
    renderPage()
    await screen.findByTestId('doc-toc')
    await waitFor(() => expect(screen.getByTestId('feedback-yes')).toBeTruthy())
    expect(screen.queryByTestId('tab-edit')).toBeNull()
    expect(screen.queryByTestId('doc-publish')).toBeNull()
    expect(screen.queryByTestId('tab-access')).toBeNull()
    expect(screen.queryByRole('link', { name: 'Gérer →' })).toBeNull()
    expect(screen.queryByTestId('feedback-totals')).toBeNull()
    // Historique et commentaires restent disponibles
    expect(screen.getAllByRole('tab', { name: /Historique/ }).length).toBeGreaterThan(0)
    expect(screen.getByTestId('comments-badge').textContent).toBe('3')
  })

  it('éditeur : Modifier et Publier visibles (brouillon)', async () => {
    scenario.editor = true
    scenario.status = 'brouillon'
    renderPage()
    await waitFor(() => expect(screen.getByTestId('tab-edit')).toBeTruthy())
    expect(screen.getByTestId('doc-publish')).toBeTruthy()
  })

  it('owner : canManageAttestations exposé ; Accès visible', async () => {
    scenario.editor = false
    scenario.owner = true
    scenario.status = 'valide'
    renderPage()
    await waitFor(() => expect(screen.getByTestId('tab-access')).toBeTruthy())
    expect(screen.queryByTestId('tab-edit')).toBeNull()
    expect(baseDoc().permissions.canManageAttestations).toBe(true)
  })

  it('gestionnaire d’espace non éditeur : onglet Accès visible', async () => {
    scenario.editor = false
    scenario.spaceCanManage = true
    renderPage()
    await waitFor(() => expect(screen.getByTestId('tab-access')).toBeTruthy())
    expect(screen.queryByTestId('tab-edit')).toBeNull()
  })

  it('Publier envoie POST approvals', async () => {
    scenario.status = 'brouillon'
    renderPage()
    fireEvent.click(await screen.findByTestId('doc-publish'))
    await waitFor(() =>
      expect(postMock).toHaveBeenCalledWith(`/api/v1/documents/${DOC}/approvals`),
    )
    expect(await screen.findByTestId('doc-publish-msg')).toBeTruthy()
  })

  it('en_revue : bannière « Voir la demande » vers /approvals/:id', async () => {
    scenario.status = 'en_revue'
    renderPage()
    const link = await screen.findByTestId('doc-view-approval')
    expect(link.getAttribute('href')).toBe('/approvals/bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb')
    expect(screen.getByTestId('doc-approval-banner').textContent).toMatch(/en revue/i)
  })
})

describe('DocumentReadPage — attestation, feedback, export', () => {
  it('affiche la bannière avec X/Y et le périmètre ; le bouton enregistre l’accusé', async () => {
    scenario.attestation = campaign()
    postMock.mockImplementation(async (url: string) => {
      if (url.endsWith('/acknowledge')) {
        scenario.attestation = campaign({ acknowledged: true, ackCount: 743 })
        return { data: campaign({ acknowledged: true, ackCount: 743 }) }
      }
      return { data: {} }
    })
    renderPage()
    const banner = await screen.findByTestId('attestation-banner')
    await waitFor(() => expect(banner.textContent).toContain('Identité & accès'))
    expect(within(banner).getByText('Document obligatoire — accusé de lecture requis')).toBeTruthy()
    expect(banner.textContent).toContain('du périmètre « Identité & accès »')
    expect(banner.textContent).toContain('cette politique avant le 3 octobre 2026')
    expect(within(banner).getByTestId('attestation-progress').textContent).toBe('742/856')

    fireEvent.click(within(banner).getByRole('button', { name: "J'ai lu et compris" }))
    await waitFor(() =>
      expect(postMock).toHaveBeenCalledWith(
        `/api/v1/documents/${DOC}/attestations/${CAMPAIGN}/acknowledge`,
      ),
    )
    await waitFor(() => expect(screen.queryByTestId('attestation-banner')).toBeNull())
  })

  it('pas de bannière sans campagne (204) ni après accusé', async () => {
    renderPage()
    await screen.findByTestId('doc-toc')
    expect(screen.queryByTestId('attestation-banner')).toBeNull()
  })

  it('pas de bannière si déjà acquittée', async () => {
    scenario.attestation = campaign({ acknowledged: true })
    renderPage()
    await screen.findByTestId('doc-toc')
    await waitFor(() =>
      expect(getMock).toHaveBeenCalledWith(
        `/api/v1/documents/${DOC}/attestations/active`,
        expect.anything(),
      ),
    )
    expect(screen.queryByTestId('attestation-banner')).toBeNull()
  })

  it('feedback Oui → PUT helpful=true', async () => {
    putMock.mockResolvedValue({ data: { myVote: true } })
    renderPage()
    fireEvent.click(await screen.findByTestId('feedback-yes'))
    await waitFor(() =>
      expect(putMock).toHaveBeenCalledWith(`/api/v1/documents/${DOC}/feedback`, { helpful: true }),
    )
    await waitFor(() => expect(screen.getByTestId('feedback-yes').getAttribute('aria-pressed')).toBe('true'))
  })

  it('export : Imprimer, PDF, options avancées ; Index désactivé « bientôt »', async () => {
    const print = vi.spyOn(window, 'print').mockImplementation(() => undefined)
    downloadExportMock.mockResolvedValue(undefined)
    renderPage()
    await screen.findByTestId('doc-toc')

    const index = screen.getByRole('button', { name: 'Index' }) as HTMLButtonElement
    expect(index.disabled).toBe(true)
    expect(index.getAttribute('title')).toBe('bientôt')
    const graphLinks = screen.getAllByRole('link', { name: 'Graphe' })
    expect(graphLinks.length).toBeGreaterThan(0)
    for (const l of graphLinks) expect(l.getAttribute('href')).toBe(`/spaces/${SPACE}/graph`)

    fireEvent.click(screen.getByTestId('doc-export-btn'))
    fireEvent.click(screen.getByRole('menuitem', { name: /Imprimer/ }))
    expect(print).toHaveBeenCalled()

    fireEvent.click(screen.getByTestId('doc-export-btn'))
    expect(screen.getByRole('menuitem', { name: /Options d'export avancées/ }).getAttribute('href')).toBe(
      `/docs/${DOC}/export`,
    )
    fireEvent.click(screen.getByRole('menuitem', { name: /Télécharger en PDF/ }))
    await waitFor(() =>
      expect(downloadExportMock).toHaveBeenCalledWith(
        expect.anything(),
        'document',
        DOC,
        'Politique de gestion des accès.pdf',
      ),
    )
    print.mockRestore()
  })
})

describe('Routage', () => {
  it('/docs/:id/view redirige vers la lecture en conservant ?comments=open', async () => {
    renderPage(`/docs/${DOC}/view?comments=open`)
    await waitFor(() => expect(screen.getByTestId('loc').textContent).toBe(`/docs/${DOC}?comments=open`))
    expect(await screen.findByTestId('comments-panel')).toBeTruthy()
  })
})
