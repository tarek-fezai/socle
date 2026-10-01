// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
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

vi.mock('./DocumentEditor', () => ({
  DocumentEditor: () => <div data-testid="editor" />,
}))

import { DocumentEditPage } from './DocumentEditPage'

const DOC_ID = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'

function wrap(ui: ReactNode) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return (
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[`/docs/${DOC_ID}`]}>
        <Routes>
          <Route path="/docs/:id" element={ui} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  )
}

describe('DocumentEditPage — soumission', () => {
  beforeEach(() => {
    getMock.mockReset()
    postMock.mockReset()
    putMock.mockReset()
    deleteMock.mockReset()
    deleteMock.mockResolvedValue({ data: undefined })
    postMock.mockImplementation((url: string) => {
      if (String(url).includes('/edit-lock')) {
        return Promise.resolve({
          data: {
            active: true,
            holderUserId: 'me',
            holderDisplayName: 'Me',
            acquiredAt: new Date().toISOString(),
            heartbeatAt: new Date().toISOString(),
            heldByCurrentUser: true,
            ttlSeconds: 45,
            heartbeatSeconds: 15,
          },
        })
      }
      return Promise.resolve({ data: {} })
    })
  })

  it('soumet puis affiche l’état en cours avec temporal_workflow_id', async () => {
    getMock.mockImplementation((url: string) => {
      if (url.includes('/approvals/current')) {
        return Promise.resolve({ status: 204, data: undefined })
      }
      if (url.includes('/approvals/applicable-workflow')) {
        return Promise.resolve({
          data: {
            id: 'cccccccc-cccc-cccc-cccc-cccccccccccc',
            name: 'Approbation simple',
            stepCount: 1,
            matchLevel: 'fallback',
            steps: [],
          },
        })
      }
      return Promise.resolve({
        data: {
          id: DOC_ID,
          spaceId: '00000000-0000-0000-0000-000000000001',
          title: 'Doc test',
          body: { type: 'doc', content: [] },
          status: 'brouillon',
          currentVersionNo: 1,
          createdAt: new Date().toISOString(),
          updatedAt: new Date().toISOString(),
        },
      })
    })
    postMock.mockResolvedValue({
      data: {
        approvalRequestId: 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',
        temporalWorkflowId: 'doc-approval-xyz',
        status: 'en_cours',
      },
    })

    render(wrap(<DocumentEditPage />))
    await waitFor(() => expect(screen.getByText('Soumettre pour approbation')).toBeTruthy())

    // Après succès, currentApproval renvoie la demande en cours
    getMock.mockImplementation((url: string) => {
      if (url.includes('/approvals/current')) {
        return Promise.resolve({
          status: 200,
          data: {
            approvalRequestId: 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',
            documentId: DOC_ID,
            documentTitle: 'Doc test',
            temporalWorkflowId: 'doc-approval-xyz',
            status: 'en_cours',
            currentStepOrder: 1,
            slaDeadlineAt: new Date(Date.now() + 86_400_000).toISOString(),
            submittedVersionNo: 1,
            baselineVersionNo: null,
            requestedBy: '11111111-1111-1111-1111-111111111111',
            createdAt: new Date().toISOString(),
          },
        })
      }
      if (url.includes('/approvals/applicable-workflow')) {
        return Promise.resolve({
          data: {
            id: 'cccccccc-cccc-cccc-cccc-cccccccccccc',
            name: 'Approbation simple',
            stepCount: 1,
            matchLevel: 'fallback',
            steps: [],
          },
        })
      }
      return Promise.resolve({
        data: {
          id: DOC_ID,
          spaceId: '00000000-0000-0000-0000-000000000001',
          title: 'Doc test',
          body: { type: 'doc', content: [] },
          status: 'en_revue',
          currentVersionNo: 2,
          createdAt: new Date().toISOString(),
          updatedAt: new Date().toISOString(),
        },
      })
    })

    fireEvent.click(screen.getByText('Soumettre pour approbation'))

    await waitFor(() => {
      expect(postMock).toHaveBeenCalledWith(`/api/v1/documents/${DOC_ID}/approvals`)
    })
    await waitFor(() => {
      expect(screen.getByText(/En cours d'approbation/)).toBeTruthy()
      expect(screen.getByText('doc-approval-xyz')).toBeTruthy()
    })
  })

  it('affiche le workflow applicable avant soumission', async () => {
    getMock.mockImplementation((url: string) => {
      if (url.includes('/approvals/current')) {
        return Promise.resolve({ status: 204, data: undefined })
      }
      if (url.includes('/approvals/applicable-workflow')) {
        return Promise.resolve({
          data: {
            id: 'eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee',
            name: 'Politique 3 étapes',
            stepCount: 3,
            matchLevel: 'space_type',
            steps: [],
          },
        })
      }
      return Promise.resolve({
        data: {
          id: DOC_ID,
          spaceId: '00000000-0000-0000-0000-000000000001',
          title: 'Doc',
          docType: 'politique',
          body: { type: 'doc' },
          status: 'brouillon',
          currentVersionNo: 1,
          createdAt: new Date().toISOString(),
          updatedAt: new Date().toISOString(),
        },
      })
    })

    render(wrap(<DocumentEditPage />))
    await waitFor(() => {
      expect(screen.getByText(/Workflow applicable : Politique 3 étapes/)).toBeTruthy()
      expect(screen.getByText(/espace \+ type/)).toBeTruthy()
    })
  })

  it('n’affiche pas Soumettre si statut valide', async () => {
    getMock.mockImplementation((url: string) => {
      if (url.includes('/approvals/current')) {
        return Promise.resolve({ status: 204, data: undefined })
      }
      if (url.includes('/approvals/applicable-workflow')) {
        return Promise.resolve({ data: { id: 'x', name: 'A', stepCount: 1, matchLevel: 'fallback', steps: [] } })
      }
      return Promise.resolve({
        data: {
          id: DOC_ID,
          spaceId: '00000000-0000-0000-0000-000000000001',
          title: 'Validé',
          body: { type: 'doc' },
          status: 'valide',
          currentVersionNo: 3,
          createdAt: new Date().toISOString(),
          updatedAt: new Date().toISOString(),
        },
      })
    })

    render(wrap(<DocumentEditPage />))
    await waitFor(() => expect(screen.getByDisplayValue('Validé')).toBeTruthy())
    expect(screen.queryByText('Soumettre pour approbation')).toBeNull()
  })

  it('affiche le bandeau « N zones à compléter » et le message du 409 à la soumission', async () => {
    getMock.mockImplementation((url: string) => {
      if (url.includes('/approvals/current')) {
        return Promise.resolve({ status: 204, data: undefined })
      }
      if (url.includes('/approvals/applicable-workflow')) {
        return Promise.resolve({ data: { id: 'x', name: 'A', stepCount: 1, matchLevel: 'fallback', steps: [] } })
      }
      return Promise.resolve({
        data: {
          id: DOC_ID,
          spaceId: '00000000-0000-0000-0000-000000000001',
          title: 'Depuis modèle',
          body: {
            type: 'doc',
            content: [
              { type: 'placeholder', attrs: { hint: 'Objet' } },
              { type: 'placeholder', attrs: { hint: 'Périmètre' } },
            ],
          },
          status: 'brouillon',
          currentVersionNo: 1,
          createdAt: new Date().toISOString(),
          updatedAt: new Date().toISOString(),
        },
      })
    })
    postMock.mockRejectedValue({
      response: {
        status: 409,
        data: { error: 'placeholders_remaining', message: '2 zones à compléter subsistent.' },
      },
    })

    render(wrap(<DocumentEditPage />))
    expect((await screen.findByTestId('placeholder-banner')).textContent).toContain(
      '2 zones à compléter',
    )
    fireEvent.click(await screen.findByText('Soumettre pour approbation'))
    await waitFor(() => {
      expect(screen.getByText('2 zones à compléter subsistent.')).toBeTruthy()
    })
  })

  it('« Enregistrer comme modèle » avertit si le document n’est pas visible de l’organisation', async () => {
    getMock.mockImplementation((url: string) => {
      if (url.includes('/approvals/current')) {
        return Promise.resolve({ status: 204, data: undefined })
      }
      if (url.includes('/approvals/applicable-workflow')) {
        return Promise.resolve({ data: { id: 'x', name: 'A', stepCount: 1, matchLevel: 'fallback', steps: [] } })
      }
      return Promise.resolve({
        data: {
          id: DOC_ID,
          spaceId: '00000000-0000-0000-0000-000000000001',
          title: 'Doc restreint',
          body: { type: 'doc' },
          status: 'brouillon',
          visibility: 'restricted',
          currentVersionNo: 1,
          createdAt: new Date().toISOString(),
          updatedAt: new Date().toISOString(),
        },
      })
    })
    postMock.mockResolvedValue({ data: { id: 'tpl-1' } })

    render(wrap(<DocumentEditPage />))
    fireEvent.click(await screen.findByText('Enregistrer comme modèle'))

    expect(await screen.findByTestId('visibility-warning')).toBeTruthy()
    const submit = screen.getByRole('button', { name: 'Enregistrer le modèle' }) as HTMLButtonElement
    expect(submit.disabled).toBe(true)

    fireEvent.click(screen.getByLabelText(/Je comprends/))
    expect(submit.disabled).toBe(false)
    fireEvent.click(submit)
    await waitFor(() =>
      expect(postMock).toHaveBeenCalledWith(`/api/v1/documents/${DOC_ID}/save-as-template`, {
        scope: 'space',
        spaceId: '00000000-0000-0000-0000-000000000001',
        name: 'Doc restreint',
      }),
    )
    expect(await screen.findByText('Modèle enregistré.')).toBeTruthy()
  })

  it('affiche le bandeau « N zones à compléter » et le message du 409 à la soumission', async () => {
    getMock.mockImplementation((url: string) => {
      if (url.includes('/approvals/current')) {
        return Promise.resolve({ status: 204, data: undefined })
      }
      if (url.includes('/approvals/applicable-workflow')) {
        return Promise.resolve({ data: { id: 'x', name: 'A', stepCount: 1, matchLevel: 'fallback', steps: [] } })
      }
      return Promise.resolve({
        data: {
          id: DOC_ID,
          spaceId: '00000000-0000-0000-0000-000000000001',
          title: 'Depuis modèle',
          body: {
            type: 'doc',
            content: [
              { type: 'placeholder', attrs: { hint: 'Objet' } },
              { type: 'placeholder', attrs: { hint: 'Périmètre' } },
            ],
          },
          status: 'brouillon',
          currentVersionNo: 1,
          createdAt: new Date().toISOString(),
          updatedAt: new Date().toISOString(),
        },
      })
    })
    postMock.mockRejectedValue({
      response: {
        status: 409,
        data: { error: 'placeholders_remaining', message: '2 zones à compléter subsistent.' },
      },
    })

    render(wrap(<DocumentEditPage />))
    expect((await screen.findByTestId('placeholder-banner')).textContent).toContain(
      '2 zones à compléter',
    )
    fireEvent.click(await screen.findByText('Soumettre pour approbation'))
    await waitFor(() => {
      expect(screen.getByText('2 zones à compléter subsistent.')).toBeTruthy()
    })
  })

  it('« Enregistrer comme modèle » avertit si le document n’est pas visible de l’organisation', async () => {
    getMock.mockImplementation((url: string) => {
      if (url.includes('/approvals/current')) {
        return Promise.resolve({ status: 204, data: undefined })
      }
      if (url.includes('/approvals/applicable-workflow')) {
        return Promise.resolve({ data: { id: 'x', name: 'A', stepCount: 1, matchLevel: 'fallback', steps: [] } })
      }
      return Promise.resolve({
        data: {
          id: DOC_ID,
          spaceId: '00000000-0000-0000-0000-000000000001',
          title: 'Doc restreint',
          body: { type: 'doc' },
          status: 'brouillon',
          visibility: 'restricted',
          currentVersionNo: 1,
          createdAt: new Date().toISOString(),
          updatedAt: new Date().toISOString(),
        },
      })
    })
    postMock.mockResolvedValue({ data: { id: 'tpl-1' } })

    render(wrap(<DocumentEditPage />))
    fireEvent.click(await screen.findByText('Enregistrer comme modèle'))

    expect(await screen.findByTestId('visibility-warning')).toBeTruthy()
    const submit = screen.getByRole('button', { name: 'Enregistrer le modèle' }) as HTMLButtonElement
    expect(submit.disabled).toBe(true)

    fireEvent.click(screen.getByLabelText(/Je comprends/))
    expect(submit.disabled).toBe(false)
    fireEvent.click(submit)
    await waitFor(() =>
      expect(postMock).toHaveBeenCalledWith(`/api/v1/documents/${DOC_ID}/save-as-template`, {
        scope: 'space',
        spaceId: '00000000-0000-0000-0000-000000000001',
        name: 'Doc restreint',
      }),
    )
    expect(await screen.findByText('Modèle enregistré.')).toBeTruthy()
  })

  it('affiche 403 proprement si soumission refusée', async () => {
    getMock.mockImplementation((url: string) => {
      if (url.includes('/approvals/current')) {
        return Promise.resolve({ status: 204, data: undefined })
      }
      if (url.includes('/approvals/applicable-workflow')) {
        return Promise.resolve({
          data: {
            id: 'cccccccc-cccc-cccc-cccc-cccccccccccc',
            name: 'Approbation simple',
            stepCount: 1,
            matchLevel: 'fallback',
            steps: [],
          },
        })
      }
      return Promise.resolve({
        data: {
          id: DOC_ID,
          spaceId: '00000000-0000-0000-0000-000000000001',
          title: 'Doc',
          body: { type: 'doc' },
          status: 'brouillon',
          currentVersionNo: 1,
          createdAt: new Date().toISOString(),
          updatedAt: new Date().toISOString(),
        },
      })
    })
    postMock.mockRejectedValue({ response: { status: 403 } })

    render(wrap(<DocumentEditPage />))
    await waitFor(() => expect(screen.getByText('Soumettre pour approbation')).toBeTruthy())
    fireEvent.click(screen.getByText('Soumettre pour approbation'))

    await waitFor(() => {
      expect(screen.getByText(/Accès refusé/)).toBeTruthy()
    })
  })
})
