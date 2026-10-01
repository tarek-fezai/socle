// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import type { ReactNode } from 'react'
import { formatSlaCountdown } from '../lib/approvals'

vi.mock('../lib/api', () => ({ api: {} }))

const listMyApprovals = vi.fn()
const decideApproval = vi.fn()
const fetchApprovalDiff = vi.fn()

vi.mock('../lib/approvals', async () => {
  const actual = await vi.importActual<typeof import('../lib/approvals')>('../lib/approvals')
  return {
    ...actual,
    listMyApprovals: (...args: unknown[]) => listMyApprovals(...args),
    decideApproval: (...args: unknown[]) => decideApproval(...args),
    fetchApprovalDiff: (...args: unknown[]) => fetchApprovalDiff(...args),
  }
})

import { ApprovalsPage } from './ApprovalsPage'

const DOC = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'
const REQ = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb'

const pendingItem = {
  approvalRequestId: REQ,
  documentId: DOC,
  documentTitle: 'Politique accès',
  temporalWorkflowId: 'doc-approval-1',
  status: 'en_cours',
  currentStepOrder: 1,
  slaDeadlineAt: new Date(Date.now() + 3_600_000).toISOString(),
  submittedVersionNo: 2,
  baselineVersionNo: 1,
  requestedBy: '11111111-1111-1111-1111-111111111111',
  createdAt: new Date().toISOString(),
}

function wrap(ui: ReactNode) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return (
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/approvals']}>
        <Routes>
          <Route path="/approvals" element={ui} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  )
}

describe('ApprovalsPage', () => {
  beforeEach(() => {
    listMyApprovals.mockReset()
    decideApproval.mockReset()
    fetchApprovalDiff.mockReset()
  })

  it('affiche la liste avec SLA visible', async () => {
    listMyApprovals.mockResolvedValue([pendingItem])
    fetchApprovalDiff.mockResolvedValue({
      documentId: DOC,
      fromVersion: 1,
      toVersion: 2,
      changes: [],
    })

    render(wrap(<ApprovalsPage />))

    await waitFor(() => {
      expect(screen.getByText('Politique accès')).toBeTruthy()
    })
    expect(screen.getByText(/Reste/)).toBeTruthy()
  })

  it('affiche le diff (ajout + modification) avant approbation', async () => {
    listMyApprovals.mockResolvedValue([pendingItem])
    fetchApprovalDiff.mockResolvedValue({
      documentId: DOC,
      fromVersion: 1,
      toVersion: 2,
      changes: [
        { path: 'content[1]', op: 'added', before: null, after: { type: 'paragraph' } },
        { path: 'content[0].text', op: 'modified', before: 'ancien', after: 'nouveau' },
      ],
    })

    render(wrap(<ApprovalsPage />))
    await waitFor(() => expect(screen.getByText('Politique accès')).toBeTruthy())
    fireEvent.click(screen.getByText('Politique accès'))

    await waitFor(() => {
      expect(screen.getByText('[added]')).toBeTruthy()
      expect(screen.getByText('[modified]')).toBeTruthy()
    })
    expect(screen.getByText(/content\[1\]/)).toBeTruthy()
    expect(screen.getByText(/content\[0\]\.text/)).toBeTruthy()
  })

  it('après Approuver, la demande disparaît après rafraîchissement backend', async () => {
    listMyApprovals
      .mockResolvedValueOnce([pendingItem])
      .mockResolvedValueOnce([])
    fetchApprovalDiff.mockResolvedValue({
      documentId: DOC,
      fromVersion: 1,
      toVersion: 2,
      changes: [],
    })
    decideApproval.mockResolvedValue({
      approvalRequestId: REQ,
      temporalWorkflowId: 'doc-approval-1',
      status: 'approuve',
    })

    render(wrap(<ApprovalsPage />))
    await waitFor(() => expect(screen.getByText('Politique accès')).toBeTruthy())
    fireEvent.click(screen.getByText('Politique accès'))

    await waitFor(() => expect(screen.getByRole('button', { name: 'Approuver' })).toBeTruthy())
    fireEvent.click(screen.getByRole('button', { name: 'Approuver' }))

    await waitFor(() => {
      expect(decideApproval).toHaveBeenCalledWith(
        expect.anything(),
        DOC,
        REQ,
        'approuve',
        1,
        null,
      )
    })
    await waitFor(() => {
      expect(screen.getByText(/Aucune demande en attente/)).toBeTruthy()
    })
    expect(screen.queryByText('Politique accès')).toBeNull()
  })

  it('race condition 409 already_resolved → message clair, pas de plantage', async () => {
    listMyApprovals.mockResolvedValue([pendingItem])
    fetchApprovalDiff.mockResolvedValue({
      documentId: DOC,
      fromVersion: 1,
      toVersion: 2,
      changes: [],
    })
    decideApproval.mockRejectedValue({
      response: { status: 409, data: { error: 'already_resolved', message: 'Demande déjà résolue' } },
    })

    render(wrap(<ApprovalsPage />))
    await waitFor(() => expect(screen.getByText('Politique accès')).toBeTruthy())
    fireEvent.click(screen.getByText('Politique accès'))
    await waitFor(() => expect(screen.getByRole('button', { name: 'Approuver' })).toBeTruthy())
    fireEvent.click(screen.getByRole('button', { name: 'Approuver' }))

    await waitFor(() => {
      expect(screen.getByText(/déjà été traitée/i)).toBeTruthy()
    })
    expect(screen.queryByRole('button', { name: 'Recharger' })).toBeNull()
    expect(screen.getByText('Mes approbations')).toBeTruthy()
  })

  it('409 step_advanced → message distinct + bouton Recharger', async () => {
    listMyApprovals
      .mockResolvedValueOnce([pendingItem])
      .mockResolvedValueOnce([])
    fetchApprovalDiff.mockResolvedValue({
      documentId: DOC,
      fromVersion: 1,
      toVersion: 2,
      changes: [],
    })
    decideApproval.mockRejectedValue({
      response: {
        status: 409,
        data: { error: 'step_advanced', message: 'Étape avancée' },
      },
    })

    render(wrap(<ApprovalsPage />))
    await waitFor(() => expect(screen.getByText('Politique accès')).toBeTruthy())
    fireEvent.click(screen.getByText('Politique accès'))
    await waitFor(() => expect(screen.getByRole('button', { name: 'Approuver' })).toBeTruthy())
    fireEvent.click(screen.getByRole('button', { name: 'Approuver' }))

    await waitFor(() => {
      expect(screen.getByText(/escaladée entretemps/i)).toBeTruthy()
      expect(screen.getByRole('button', { name: 'Recharger' })).toBeTruthy()
    })
    expect(screen.queryByText(/déjà été traitée/i)).toBeNull()

    fireEvent.click(screen.getByRole('button', { name: 'Recharger' }))
    await waitFor(() => {
      expect(screen.getByText(/Aucune demande en attente/)).toBeTruthy()
    })
  })
})

describe('formatSlaCountdown', () => {
  it('affiche un compte à rebours lisible', () => {
    const now = Date.parse('2026-09-28T12:00:00Z')
    expect(formatSlaCountdown('2026-09-28T14:30:00Z', now)).toBe('Reste 2 h 30 min')
    expect(formatSlaCountdown('2026-09-28T11:00:00Z', now)).toBe('Échu depuis 1 h 0 min')
  })
})
