// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
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
  draft: Record<string, unknown> | null
  tags: Array<Record<string, unknown>>
  canManageAccess: boolean
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
    tags: scenario.tags,
    permissions: {
      canEdit: scenario.canEdit,
      canPublish: scenario.canPublish,
      canManageAccess: scenario.canManageAccess,
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

const draftPutCalls = () => putMock.mock.calls.filter(([u]) => String(u).endsWith('/draft'))
const updateCalls = () => putMock.mock.calls.filter(([u]) => String(u) === `/api/v1/documents/${DOC_ID}`)

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
    draft: null,
    tags: [{ id: 't1', name: 'IAM', color: '#3730E0' }],
    canManageAccess: false,
  }
  deleteMock.mockResolvedValue({ data: undefined })
  putMock.mockImplementation((url: string, payload: Record<string, unknown>) => {
    if (url.endsWith('/draft')) {
      return Promise.resolve({ data: { ...payload, updatedAt: '2026-09-12T13:05:00Z' } })
    }
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
    if (url === `/api/v1/documents/${DOC_ID}/draft`) {
      return scenario.draft
        ? Promise.resolve({ status: 200, data: scenario.draft })
        : Promise.resolve({ status: 404, data: undefined })
    }
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

  it('enregistre automatiquement après ~1 s dans le brouillon (jamais updateDocument)', async () => {
    render(wrap(<DocumentEditPage />))
    await screen.findByDisplayValue('Politique de gestion des accès')
    await waitFor(() => expect(screen.getByTestId('editor').getAttribute('data-editable')).toBe('true'))
    fireEvent.click(await screen.findByText('taper'))
    expect(screen.getByTestId('edit-word-count').textContent).toContain('4 mots')
    await waitFor(() => expect(putMock).toHaveBeenCalledTimes(1), { timeout: 4000 })
    const [url, payload] = putMock.mock.calls[0]!
    expect(url).toBe(`/api/v1/documents/${DOC_ID}/draft`)
    expect(payload.baseVersionNo).toBe(3)
    expect(payload.expectedVersionNo).toBeUndefined()
    expect(JSON.stringify(payload.body)).toContain('un deux trois quatre')
    await waitFor(() => expect(screen.getByTestId('edit-save-status').getAttribute('data-save-state')).toBe('saved'))
    // L'heure affichée vient de draft.updatedAt (13:05Z), pas de l'horloge locale.
    const expected = new Date('2026-09-12T13:05:00Z')
    const hh = String(expected.getHours()).padStart(2, '0')
    const mm = String(expected.getMinutes()).padStart(2, '0')
    expect(screen.getByTestId('edit-save-status').textContent).toBe(`Brouillon enregistré à ${hh}:${mm}`)
    expect(updateCalls()).toHaveLength(0)
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

describe('DocumentEditPage — brouillon et version explicite', () => {
  async function typeAndWaitDraft() {
    render(wrap(<DocumentEditPage />))
    await screen.findByDisplayValue('Politique de gestion des accès')
    await waitFor(() => expect(screen.getByTestId('editor').getAttribute('data-editable')).toBe('true'))
    fireEvent.click(await screen.findByText('taper'))
    await waitFor(() => expect(draftPutCalls()).toHaveLength(1), { timeout: 4000 })
  }

  it('restaure titre et corps du brouillon à l’ouverture', async () => {
    scenario.draft = {
      title: 'Titre en brouillon',
      body: { type: 'doc', content: [para('Texte du brouillon enregistré')] },
      baseVersionNo: 3,
      updatedAt: '2026-09-12T13:05:00Z',
    }
    render(wrap(<DocumentEditPage />))
    expect(await screen.findByDisplayValue('Titre en brouillon')).toBeTruthy()
    expect(screen.queryByTestId('edit-draft-stale-banner')).toBeNull()
    expect(screen.getByTestId('edit-word-count').textContent).toContain('4 mots')
    // Restaurer n'enregistre rien.
    await new Promise((r) => setTimeout(r, 1300))
    expect(putMock).not.toHaveBeenCalled()
  })

  it('brouillon périmé : bandeau, lien « Comparer » vers l’historique', async () => {
    scenario.draft = { title: 'Ancien', body: { type: 'doc', content: [para('x')] }, baseVersionNo: 2, updatedAt: '2026-09-10T08:00:00Z' }
    render(wrap(<DocumentEditPage />))
    const banner = await screen.findByTestId('edit-draft-stale-banner')
    expect(banner.textContent).toContain('Le document a changé depuis votre brouillon')
    expect(screen.getByTestId('edit-draft-compare').getAttribute('href')).toBe(`/docs/${DOC_ID}/history`)
  })

  it('« Abandonner le brouillon » : DELETE puis contenu publié rechargé', async () => {
    scenario.draft = { title: 'Ancien', body: { type: 'doc', content: [para('x')] }, baseVersionNo: 2, updatedAt: '2026-09-10T08:00:00Z' }
    render(wrap(<DocumentEditPage />))
    fireEvent.click(await screen.findByTestId('edit-draft-abandon'))
    await waitFor(() => expect(deleteMock).toHaveBeenCalledWith(`/api/v1/documents/${DOC_ID}/draft`))
    expect(await screen.findByDisplayValue('Politique de gestion des accès')).toBeTruthy()
    expect(screen.queryByTestId('edit-draft-stale-banner')).toBeNull()
    // L'abandon ne recrée pas de brouillon au démontage.
    expect(draftPutCalls()).toHaveLength(0)
    expect(updateCalls()).toHaveLength(0)
  })

  it('Ctrl+S : vide le brouillon puis appelle updateDocument une seule fois', async () => {
    await typeAndWaitDraft()
    fireEvent.keyDown(document.body, { key: 's', ctrlKey: true })
    await waitFor(() => expect(updateCalls()).toHaveLength(1))
    const [, payload] = updateCalls()[0]!
    expect(payload.expectedVersionNo).toBe(3)
    expect(JSON.stringify(payload.body)).toContain('un deux trois quatre')
    expect(await screen.findByTestId('edit-version-msg')).toBeTruthy()
    // Le brouillon a été écrit AVANT la version.
    const order = putMock.mock.calls.map(([u]) => String(u))
    expect(order.indexOf(`/api/v1/documents/${DOC_ID}/draft`)).toBeLessThan(order.indexOf(`/api/v1/documents/${DOC_ID}`))
  })

  it('Cmd+S fonctionne aussi et un 409 s’affiche', async () => {
    putMock.mockImplementation((url: string, payload: Record<string, unknown>) => {
      if (url.endsWith('/draft')) return Promise.resolve({ data: { ...payload, updatedAt: '2026-09-12T13:05:00Z' } })
      return Promise.reject({ response: { status: 409, data: { message: 'Version obsolète' } } })
    })
    await typeAndWaitDraft()
    fireEvent.keyDown(document.body, { key: 'S', metaKey: true })
    expect((await screen.findByTestId('edit-draft-error')).textContent).toContain('Version obsolète')
    expect(updateCalls()).toHaveLength(1)
  })

  it('Ctrl+S sans modification : aucune version', async () => {
    render(wrap(<DocumentEditPage />))
    await waitFor(() => expect(screen.getByTestId('editor').getAttribute('data-editable')).toBe('true'))
    fireEvent.keyDown(document.body, { key: 's', ctrlKey: true })
    await new Promise((r) => setTimeout(r, 50))
    expect(putMock).not.toHaveBeenCalled()
  })

  it('demande confirmation avant de quitter quand le contenu diffère du publié', async () => {
    await typeAndWaitDraft()
    const ev = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(ev)
    expect(ev.defaultPrevented).toBe(true)
  })

  it('pas de confirmation de sortie sans modification', async () => {
    render(wrap(<DocumentEditPage />))
    await waitFor(() => expect(screen.getByTestId('editor').getAttribute('data-editable')).toBe('true'))
    const ev = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(ev)
    expect(ev.defaultPrevented).toBe(false)
  })
})

async function confirmSendReview(summary = '') {
  const btn = (await screen.findByTestId('edit-send-review')) as HTMLButtonElement
  await waitFor(() => expect(btn.disabled).toBe(false))
  fireEvent.click(btn)
  if (summary) {
    fireEvent.change(screen.getByTestId('edit-send-review-input'), { target: { value: summary } })
  }
  fireEvent.click(screen.getByTestId('edit-send-review-confirm'))
}

describe('DocumentEditPage — envoi en révision', () => {
  it('POST /approvals puis confirme', async () => {
    render(wrap(<DocumentEditPage />))
    await confirmSendReview()
    await waitFor(() => expect(postMock).toHaveBeenCalledWith(`/api/v1/documents/${DOC_ID}/approvals`))
    expect(await screen.findByTestId('edit-approval-msg')).toBeTruthy()
    // Contenu inchangé : pas de nouvelle version inutile.
    expect(updateCalls()).toHaveLength(0)
  })

  it('brouillon modifié : flush du brouillon, updateDocument (version), puis POST /approvals', async () => {
    render(wrap(<DocumentEditPage />))
    await waitFor(() => expect(screen.getByTestId('editor').getAttribute('data-editable')).toBe('true'))
    fireEvent.click(await screen.findByText('taper'))
    await confirmSendReview()
    await waitFor(() => expect(postMock).toHaveBeenCalledWith(`/api/v1/documents/${DOC_ID}/approvals`))
    expect(draftPutCalls().length).toBeGreaterThanOrEqual(1)
    expect(updateCalls()).toHaveLength(1)
    const order = putMock.mock.invocationCallOrder
    const draftIdx = putMock.mock.calls.findIndex(([u]) => String(u).endsWith('/draft'))
    const updIdx = putMock.mock.calls.findIndex(([u]) => String(u) === `/api/v1/documents/${DOC_ID}`)
    expect(order[draftIdx]!).toBeLessThan(order[updIdx]!)
    const approvalCall = postMock.mock.calls.findIndex(([u]) => String(u).endsWith('/approvals'))
    expect(postMock.mock.invocationCallOrder[approvalCall]!).toBeGreaterThan(order[updIdx]!)
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
    await confirmSendReview()
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
    await confirmSendReview()
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
  it('liste les liens cassés : ancre + reason (jamais un titre cible secret)', async () => {
    scenario.hints = {
      longParagraphThresholdWords: 120,
      brokenLinks: [
        {
          targetId: 'x1',
          label: 'Procédure de provisioning v9',
          accessible: false,
          reason: 'deleted',
        },
        {
          targetId: 'x2',
          label: 'voir la procédure RH',
          accessible: false,
          reason: 'inaccessible',
        },
      ],
      longParagraphs: [],
    }
    render(wrap(<DocumentEditPage />))
    const panel = await screen.findByTestId('edit-assistant')
    await waitFor(() => expect(panel.textContent).toContain('Procédure de provisioning v9'))
    expect(panel.textContent).toContain("n'existe plus")
    expect(panel.textContent).toContain('voir la procédure RH')
    expect(panel.textContent).toContain("n'est pas accessible")
    expect(panel.textContent).not.toContain('Titre ultra secret')
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

  it('tag de gouvernance : cadenas, retrait désactivé hors propriétaire', async () => {
    scenario.tags = [
      { id: 't1', name: 'IAM', color: '#3730E0' },
      { id: 't2', name: 'Confidentiel', color: '#B54708', governed: true },
    ]
    render(wrap(<DocumentEditPage />))
    await screen.findByTestId('edit-assistant')
    const gov = (await screen.findAllByTestId('edit-tag')).find((t) => t.textContent?.includes('Confidentiel'))!
    expect(within(gov).getByTestId('tag-lock')).toBeTruthy()
    const remove = within(gov).getByRole('button', { name: /Retirer le tag Confidentiel/ }) as HTMLButtonElement
    expect(remove.disabled).toBe(true)
    expect(remove.title).toBe('Étiquette de gouvernance — réservée aux propriétaires')
    fireEvent.click(remove)
    expect(deleteMock).not.toHaveBeenCalledWith(`/api/v1/documents/${DOC_ID}/tags/t2`)
    // Tag non gouverné : aucun cadenas, retrait actif.
    const plain = (await screen.findAllByTestId('edit-tag')).find((t) => t.textContent?.includes('IAM'))!
    expect(within(plain).queryByTestId('tag-lock')).toBeNull()
    expect((within(plain).getByRole('button', { name: /Retirer le tag IAM/ }) as HTMLButtonElement).disabled).toBe(false)
  })

  it('tag de gouvernance : retrait possible pour un propriétaire, erreur 409 affichée', async () => {
    scenario.canManageAccess = true
    scenario.tags = [{ id: 't2', name: 'Confidentiel', governed: true }]
    deleteMock.mockRejectedValueOnce({
      response: {
        status: 409,
        data: {
          type: 'about:blank',
          title: 'Conflict',
          status: 409,
          detail: "Demande d'approbation en cours",
          instance: `/api/v1/documents/${DOC_ID}/tags/t2`,
          code: 'approval_in_progress',
        },
      },
    })
    render(wrap(<DocumentEditPage />))
    const remove = (await screen.findByRole('button', { name: /Retirer le tag Confidentiel/ })) as HTMLButtonElement
    await waitFor(() => expect(remove.disabled).toBe(false))
    fireEvent.click(remove)
    await waitFor(() => expect(deleteMock).toHaveBeenCalledWith(`/api/v1/documents/${DOC_ID}/tags/t2`))
    expect((await screen.findByTestId('tag-error')).textContent).toBe("Demande d'approbation en cours")
  })

  it('étiquette gouvernée 403 : affiche le detail problem+json', async () => {
    scenario.canManageAccess = true
    scenario.tags = [{ id: 't2', name: 'Confidentiel', governed: true }]
    deleteMock.mockRejectedValueOnce({
      response: {
        status: 403,
        data: {
          status: 403,
          detail: 'Étiquette gouvernée : seul un owner peut la rattacher ou la détacher',
          code: 'governed_tag_owner_only',
        },
      },
    })
    render(wrap(<DocumentEditPage />))
    const remove = (await screen.findByRole('button', { name: /Retirer le tag Confidentiel/ })) as HTMLButtonElement
    await waitFor(() => expect(remove.disabled).toBe(false))
    fireEvent.click(remove)
    expect((await screen.findByTestId('tag-error')).textContent).toContain('Étiquette gouvernée')
  })

  it('suggestion de gouvernance désactivée hors propriétaire', async () => {
    const base = getMock.getMockImplementation()!
    getMock.mockImplementation((url: string, ...rest: unknown[]) => {
      if (url === '/api/v1/tags') {
        return Promise.resolve({ data: [{ id: 't9', name: 'Secret défense', governed: true }, { id: 't8', name: 'Public' }] })
      }
      return base(url, ...rest)
    })
    render(wrap(<DocumentEditPage />))
    fireEvent.click(await screen.findByTestId('tag-add'))
    const list = await screen.findByTestId('tag-suggestions')
    const gov = await within(list).findByText('Secret défense')
    const li = gov.closest('li')!
    expect(li.getAttribute('aria-disabled')).toBe('true')
    expect(li.title).toBe('Étiquette de gouvernance — réservée aux propriétaires')
    fireEvent.mouseDown(li)
    expect(postMock.mock.calls.some(([u]) => String(u).endsWith('/tags'))).toBe(false)
  })

  it('retire un tag existant', async () => {
    render(wrap(<DocumentEditPage />))
    await screen.findByTestId('edit-assistant')
    fireEvent.click(await screen.findByRole('button', { name: /Retirer le tag IAM/ }))
    await waitFor(() => expect(deleteMock).toHaveBeenCalledWith(`/api/v1/documents/${DOC_ID}/tags/t1`))
  })
})
