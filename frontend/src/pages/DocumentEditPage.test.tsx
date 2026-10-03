// SPDX-License-Identifier: AGPL-3.0-or-later
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import type { ReactNode } from 'react'

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
  useAuth: () => ({
    authenticated: true,
    me: { id: 'me', displayName: 'Tarek Fezai', email: 't@example.com', avatarInitials: 'TF' },
  }),
}))

// L'éditeur TipTap est testé à part : ici un double qui expose `editable` et permet de « taper ».
vi.mock('./DocumentEditor', () => ({
  DocumentEditor: (props: {
    titleSlot?: ReactNode
    editable?: boolean
    hidden?: boolean
    onChange: (b: Record<string, unknown>) => void
  }) => (
    <div data-testid="editor" data-editable={String(props.editable)} data-hidden={String(Boolean(props.hidden))}>
      {props.titleSlot}
      <button
        type="button"
        onClick={() =>
          props.onChange({
            type: 'doc',
            content: [{ type: 'paragraph', content: [{ type: 'text', text: 'un deux trois quatre' }] }],
          })
        }
      >
        taper
      </button>
    </div>
  ),
}))

import { DocumentEditPage } from './DocumentEditPage'

const DOC_ID = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'
const SPACE_ID = '00000000-0000-0000-0000-000000000001'

type Scenario = {
  canPublish: boolean
  canEdit: boolean
  workflow: boolean
  lockHolder: string | null
  body: Record<string, unknown>
  hints: Record<string, unknown>
  customFields: unknown[]
}

let scenario: Scenario

const para = (text: string) => ({ type: 'paragraph', content: [{ type: 'text', text }] })

function docPayload() {
  return {
    id: DOC_ID,
    spaceId: SPACE_ID,
    title: 'Politique de gestion des accès',
    docType: 'politique',
    body: scenario.body,
    status: 'brouillon',
    currentVersionNo: 3,
    createdAt: '2026-09-01T09:00:00Z',
    updatedAt: '2026-09-12T12:22:00Z',
    stalenessThresholdDays: 181,
    reliabilityScore: 91,
    tags: [{ id: 't1', name: 'IAM', color: '#3730E0' }],
    permissions: {
      canEdit: scenario.canEdit,
      canPublish: scenario.canPublish,
      canManageAccess: false,
      canComment: true,
      canManageAttestations: false,
    },
  }
}

function lockPayload() {
  const other = scenario.lockHolder
  return {
    active: true,
    holderUserId: other ? 'u2' : 'me',
    holderDisplayName: other ?? 'Tarek Fezai',
    acquiredAt: new Date(Date.now() - 12 * 60_000).toISOString(),
    heartbeatAt: new Date().toISOString(),
    heldByCurrentUser: !other,
    ttlSeconds: 45,
    heartbeatSeconds: 15,
  }
}

function wrap(ui: ReactNode) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return (
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[`/docs/${DOC_ID}/edit`]}>
        <Routes>
          <Route path="/docs/:id/edit" element={ui} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  )
}

beforeEach(() => {
  getMock.mockReset()
  postMock.mockReset()
  putMock.mockReset()
  deleteMock.mockReset()
  scenario = {
    canPublish: true,
    canEdit: true,
    workflow: true,
    lockHolder: null,
    body: { type: 'doc', content: [para('Chapeau.')] },
    hints: { longParagraphThresholdWords: 120, brokenLinks: [], longParagraphs: [] },
    customFields: [],
  }
  deleteMock.mockResolvedValue({ data: undefined })
  putMock.mockImplementation((url: string, payload: Record<string, unknown>) => {
    if (url.includes('/custom-fields/')) return Promise.resolve({ data: { id: 'f1', value: payload.value } })
    return Promise.resolve({ data: { ...docPayload(), ...payload, currentVersionNo: 4, updatedAt: new Date().toISOString() } })
  })
  postMock.mockImplementation((url: string) => {
    if (String(url).includes('/edit-lock')) return Promise.resolve({ data: lockPayload() })
    if (String(url).includes('/approvals')) {
      return Promise.resolve({ data: { approvalRequestId: 'r1', temporalWorkflowId: 'w1', status: 'en_cours' } })
    }
    return Promise.resolve({ data: {} })
  })
  getMock.mockImplementation((url: string) => {
    if (url === `/api/v1/documents/${DOC_ID}`) return Promise.resolve({ data: docPayload() })
    if (url.endsWith('/approvals/current')) return Promise.resolve({ status: 204, data: undefined })
    if (url.endsWith('/approvals/applicable-workflow')) {
      return scenario.workflow
        ? Promise.resolve({ data: { id: 'w', name: 'Approbation simple', stepCount: 1, matchLevel: 'fallback', steps: [] } })
        : Promise.reject({ response: { status: 404 } })
    }
    if (url.endsWith('/writing-assistant')) return Promise.resolve({ data: scenario.hints })
    if (url.endsWith('/custom-fields')) return Promise.resolve({ data: scenario.customFields })
    if (url === `/api/v1/spaces/${SPACE_ID}`) {
      return Promise.resolve({ data: { id: SPACE_ID, name: 'Identité & accès', canManage: false } })
    }
    if (url.includes('/comments')) return Promise.resolve({ data: { threads: [], openThreadCount: 0 } })
    return Promise.reject({ response: { status: 404 } })
  })
})

describe('DocumentEditPage — barre haute', () => {
  it('affiche l’état d’enregistrement, le décompte de mots et mon seul avatar', async () => {
    render(wrap(<DocumentEditPage />))
    expect(await screen.findByDisplayValue('Politique de gestion des accès')).toBeTruthy()
    const status = await screen.findByTestId('edit-save-status')
    expect(status.textContent).toMatch(/^Brouillon enregistré à \d{2}:\d{2}$/)
    expect(screen.getByTestId('edit-word-count').textContent?.replace(/\s/g, ' ')).toBe('1 mot · 1 min de lecture')
    const presence = screen.getByTestId('edit-presence')
    expect(within(presence).getAllByText('TF')).toHaveLength(1)
    expect(screen.queryByTestId('edit-lock-banner')).toBeNull()
  })

  it('enregistre automatiquement après ~1 s via updateDocument et met à jour le compteur', async () => {
    render(wrap(<DocumentEditPage />))
    await screen.findByDisplayValue('Politique de gestion des accès')
    await waitFor(() => expect(screen.getByTestId('editor').getAttribute('data-editable')).toBe('true'))
    fireEvent.click(await screen.findByText('taper'))
    expect(screen.getByTestId('edit-word-count').textContent).toContain('4 mots')
    await waitFor(() => expect(putMock).toHaveBeenCalledTimes(1), { timeout: 4000 })
    const [url, payload] = putMock.mock.calls[0]!
    expect(url).toBe(`/api/v1/documents/${DOC_ID}`)
    expect(payload.expectedVersionNo).toBe(3)
    expect(JSON.stringify(payload.body)).toContain('un deux trois quatre')
    await waitFor(() => expect(screen.getByTestId('edit-save-status').getAttribute('data-save-state')).toBe('saved'))
  })

  it('affiche « Échec de l’enregistrement — Réessayer » puis réussit au nouvel essai', async () => {
    putMock.mockRejectedValueOnce({ response: { status: 500, data: { message: 'Serveur indisponible' } } })
    render(wrap(<DocumentEditPage />))
    fireEvent.click(await screen.findByText('taper'))
    await waitFor(() => expect(screen.getByTestId('edit-save-status').getAttribute('data-save-state')).toBe('error'), {
      timeout: 4000,
    })
    expect(screen.getByTestId('edit-save-status').textContent).toContain("Échec de l'enregistrement")
    fireEvent.click(screen.getByTestId('edit-save-retry'))
    await waitFor(() => expect(screen.getByTestId('edit-save-status').getAttribute('data-save-state')).toBe('saved'))
    expect(putMock).toHaveBeenCalledTimes(2)
  })

  it('« Aperçu » bascule en lecture dans la page', async () => {
    render(wrap(<DocumentEditPage />))
    await screen.findByText('taper')
    fireEvent.click(screen.getByTestId('edit-preview'))
    expect(screen.getByTestId('editor').getAttribute('data-hidden')).toBe('true')
    expect(screen.getByTestId('edit-preview-body').textContent).toContain('Chapeau.')
    fireEvent.click(screen.getByTestId('edit-preview'))
    expect(screen.queryByTestId('edit-preview-body')).toBeNull()
  })
})

describe('DocumentEditPage — envoi en révision', () => {
  it('POST /approvals puis confirme', async () => {
    render(wrap(<DocumentEditPage />))
    const btn = (await screen.findByTestId('edit-send-review')) as HTMLButtonElement
    await waitFor(() => expect(btn.disabled).toBe(false))
    fireEvent.click(btn)
    await waitFor(() => expect(postMock).toHaveBeenCalledWith(`/api/v1/documents/${DOC_ID}/approvals`))
    expect(await screen.findByTestId('edit-approval-msg')).toBeTruthy()
  })

  it('est désactivé sans droit de publication, avec la raison en info-bulle', async () => {
    scenario.canPublish = false
    render(wrap(<DocumentEditPage />))
    const btn = (await screen.findByTestId('edit-send-review')) as HTMLButtonElement
    await screen.findByDisplayValue('Politique de gestion des accès')
    expect(btn.disabled).toBe(true)
    expect(btn.title).toMatch(/pas le droit/)
  })

  it('est désactivé sans workflow applicable', async () => {
    scenario.workflow = false
    render(wrap(<DocumentEditPage />))
    const btn = (await screen.findByTestId('edit-send-review')) as HTMLButtonElement
    await waitFor(() => expect(btn.title).toMatch(/workflow/))
    expect(btn.disabled).toBe(true)
  })

  it('affiche le vrai message d’erreur de l’API (quatre yeux)', async () => {
    postMock.mockImplementation((url: string) => {
      if (String(url).includes('/edit-lock')) return Promise.resolve({ data: lockPayload() })
      return Promise.reject({
        response: { status: 409, data: { error: 'four_eyes', message: 'Le principe des quatre yeux interdit de valider votre propre document.' } },
      })
    })
    render(wrap(<DocumentEditPage />))
    const btn = (await screen.findByTestId('edit-send-review')) as HTMLButtonElement
    await waitFor(() => expect(btn.disabled).toBe(false))
    fireEvent.click(btn)
    const alert = await screen.findByTestId('edit-approval-error')
    expect(alert.textContent).toContain('quatre yeux')
  })

  it('affiche le message du 409 « zones à compléter »', async () => {
    scenario.body = { type: 'doc', content: [{ type: 'placeholder', attrs: { hint: 'Objet' } }, { type: 'placeholder', attrs: { hint: 'Périmètre' } }] }
    postMock.mockImplementation((url: string) => {
      if (String(url).includes('/edit-lock')) return Promise.resolve({ data: lockPayload() })
      return Promise.reject({
        response: { status: 409, data: { error: 'placeholders_remaining', message: '2 zones à compléter subsistent.' } },
      })
    })
    render(wrap(<DocumentEditPage />))
    expect((await screen.findByTestId('placeholder-banner')).textContent).toContain('2 zones à compléter')
    const btn = screen.getByTestId('edit-send-review') as HTMLButtonElement
    await waitFor(() => expect(btn.disabled).toBe(false))
    fireEvent.click(btn)
    await waitFor(() => expect(screen.getByTestId('edit-approval-error').textContent).toContain('2 zones à compléter subsistent.'))
  })
})

describe('DocumentEditPage — verrou exclusif', () => {
  it('lecture seule + bandeau + avatar du détenteur quand un autre utilisateur édite', async () => {
    scenario.lockHolder = 'Camille Durand'
    render(wrap(<DocumentEditPage />))
    const banner = await screen.findByTestId('edit-lock-banner')
    expect(banner.textContent).toContain('Camille Durand')
    expect(banner.textContent).toMatch(/depuis 12 min/)
    expect(screen.getByTestId('editor').getAttribute('data-editable')).toBe('false')
    expect((screen.getByTestId('edit-title') as HTMLTextAreaElement).readOnly).toBe(true)
    const presence = screen.getByTestId('edit-presence')
    expect(within(presence).getByText('CD')).toBeTruthy()
    expect(within(presence).queryByText('TF')).toBeNull()
    expect((screen.getByTestId('edit-send-review') as HTMLButtonElement).disabled).toBe(true)
    // Aucun enregistrement automatique en lecture seule.
    expect(putMock).not.toHaveBeenCalled()
  })

  it('libère le verrou à la sortie quand il est détenu', async () => {
    const { unmount } = render(wrap(<DocumentEditPage />))
    await waitFor(() => expect(screen.getByTestId('editor').getAttribute('data-editable')).toBe('true'))
    unmount()
    await waitFor(() => expect(deleteMock).toHaveBeenCalledWith(`/api/v1/documents/${DOC_ID}/edit-lock`))
  })

  it('lecture seule sans droit d’édition (pas de verrou demandé)', async () => {
    scenario.canEdit = false
    render(wrap(<DocumentEditPage />))
    expect(await screen.findByTestId('edit-readonly-banner')).toBeTruthy()
    expect(screen.getByTestId('editor').getAttribute('data-editable')).toBe('false')
    expect(postMock.mock.calls.some(([u]) => String(u).includes('/edit-lock'))).toBe(false)
  })

  it('corps en lecture seule si le document contient des blocs que l’éditeur perdrait', async () => {
    scenario.body = { type: 'doc', content: [para('ok'), { type: 'drawio', attrs: { xml: '<mxfile/>' } }] }
    render(wrap(<DocumentEditPage />))
    expect(await screen.findByTestId('edit-unsupported-banner')).toBeTruthy()
    expect(screen.getByTestId('editor').getAttribute('data-editable')).toBe('false')
    expect((screen.getByTestId('edit-title') as HTMLTextAreaElement).readOnly).toBe(false)
  })
})

describe('DocumentEditPage — assistant et métadonnées', () => {
  it('liste les liens cassés, « Document inaccessible » pour une cible illisible', async () => {
    scenario.hints = {
      longParagraphThresholdWords: 120,
      brokenLinks: [
        { targetId: 'x1', label: 'Ancienne procédure', accessible: true },
        { targetId: 'x2', label: 'Secret RH', accessible: false },
      ],
      longParagraphs: [],
    }
    render(wrap(<DocumentEditPage />))
    const panel = await screen.findByTestId('edit-assistant')
    await waitFor(() => expect(panel.textContent).toContain('Ancienne procédure'))
    expect(panel.textContent).toContain('Document inaccessible')
    expect(panel.textContent).not.toContain('Secret RH')
  })

  it('signale un paragraphe trop long selon le seuil du serveur', async () => {
    const long = Array.from({ length: 45 }, (_, i) => `mot${i}`).join(' ')
    scenario.body = { type: 'doc', content: [para('court'), para(long)] }
    scenario.hints = { longParagraphThresholdWords: 40, brokenLinks: [], longParagraphs: [] }
    render(wrap(<DocumentEditPage />))
    const panel = await screen.findByTestId('edit-assistant')
    await waitFor(() => expect(within(panel).getByText(/Aller au paragraphe/)).toBeTruthy())
    expect(panel.textContent).toContain('45')
  })

  it('affiche les champs personnalisés seulement s’il existe des définitions', async () => {
    render(wrap(<DocumentEditPage />))
    await screen.findByTestId('edit-assistant')
    expect(screen.queryByTestId('edit-custom-fields')).toBeNull()
  })

  it('affiche les champs personnalisés et enregistre une valeur', async () => {
    scenario.customFields = [
      { id: 'f1', name: 'Référence interne', slug: 'ref', fieldType: 'texte', required: false, value: null },
    ]
    render(wrap(<DocumentEditPage />))
    const input = (await screen.findByLabelText('Référence interne')) as HTMLInputElement
    await waitFor(() => expect(input.disabled).toBe(false))
    fireEvent.change(input, { target: { value: 'POL-12' } })
    fireEvent.blur(input)
    await waitFor(() =>
      expect(putMock).toHaveBeenCalledWith(`/api/v1/documents/${DOC_ID}/custom-fields/f1`, { value: 'POL-12' }),
    )
  })

  it('retire un tag existant', async () => {
    render(wrap(<DocumentEditPage />))
    await screen.findByTestId('edit-assistant')
    fireEvent.click(await screen.findByRole('button', { name: /Retirer le tag IAM/ }))
    await waitFor(() => expect(deleteMock).toHaveBeenCalledWith(`/api/v1/documents/${DOC_ID}/tags/t1`))
  })
})
