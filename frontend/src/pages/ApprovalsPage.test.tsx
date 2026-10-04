// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import type { ReactNode } from 'react'
import {
  REJECT_JUSTIFICATION_REQUIRED,
  cannotDecideMessage,
  formatSlaCountdown,
  formatSlaRail,
  formatSlaRemaining,
} from '../lib/approvals'

vi.mock('../lib/api', () => ({ api: {} }))
vi.mock('../auth/AuthProvider', () => ({
  useAuth: () => ({ me: { displayName: 'Tarek Fezai', avatarInitials: 'TF' } }),
}))

const listMyApprovals = vi.fn()
const fetchApprovalDetail = vi.fn()
const decideApproval = vi.fn()
const fetchApprovalCompare = vi.fn()
const fetchApplicableWorkflow = vi.fn()
const listAllVersions = vi.fn()
const fetchVersionCompare = vi.fn()

vi.mock('../lib/approvals', async () => {
  const actual = await vi.importActual<typeof import('../lib/approvals')>('../lib/approvals')
  return {
    ...actual,
    listMyApprovals: (...args: unknown[]) => listMyApprovals(...args),
    fetchApprovalDetail: (...args: unknown[]) => fetchApprovalDetail(...args),
    decideApproval: (...args: unknown[]) => decideApproval(...args),
    fetchApprovalCompare: (...args: unknown[]) => fetchApprovalCompare(...args),
    fetchApplicableWorkflow: (...args: unknown[]) => fetchApplicableWorkflow(...args),
  }
})
vi.mock('../lib/documents', async () => {
  const actual = await vi.importActual<typeof import('../lib/documents')>('../lib/documents')
  return {
    ...actual,
    listAllVersions: (...args: unknown[]) => listAllVersions(...args),
    fetchVersionCompare: (...args: unknown[]) => fetchVersionCompare(...args),
  }
})

import { ApprovalsPage } from './ApprovalsPage'

const DOC = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'
const REQ = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb'
const LINK_ID = 'cccccccc-cccc-cccc-cccc-cccccccccccc'

const pendingItem = {
  approvalRequestId: REQ,
  documentId: DOC,
  documentTitle: 'Politique accès',
  temporalWorkflowId: 'doc-approval-1',
  status: 'en_cours',
  currentStepOrder: 1,
  slaDeadlineAt: new Date(Date.now() + 22 * 3_600_000 + 60_000).toISOString(),
  submittedVersionNo: 2,
  baselineVersionNo: 1,
  requestedBy: '11111111-1111-1111-1111-111111111111',
  createdAt: new Date(Date.now() - 26 * 3_600_000).toISOString(),
  requestedByDisplayName: 'Claire Dubois',
  requestedByInitials: 'CD',
  impactedLinks: [] as { id: string; title: string }[],
  hiddenImpactedCount: 0,
}

const detailCanDecide = {
  ...pendingItem,
  impactedLinks: [{ id: LINK_ID, title: 'Procédure de provisioning' }],
  hiddenImpactedCount: 0,
  canDecide: true,
  cannotDecideReason: null as null,
}

function wrap(ui: ReactNode, path = '/approvals') {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return (
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/approvals" element={ui} />
          <Route path="/approvals/:requestId" element={ui} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  )
}

async function renderDetail(item = detailCanDecide) {
  fetchApprovalDetail.mockResolvedValue(item)
  render(wrap(<ApprovalsPage />, `/approvals/${REQ}`))
  await waitFor(() => expect(screen.getByRole('heading', { name: item.documentTitle })).toBeTruthy())
}

describe('ApprovalsPage — liste', () => {
  beforeEach(() => {
    listMyApprovals.mockReset()
    fetchApprovalDetail.mockReset()
  })

  it('liste les demandes avec lien vers le détail', async () => {
    listMyApprovals.mockResolvedValue([pendingItem])
    render(wrap(<ApprovalsPage />))
    const link = await screen.findByTestId(`approval-list-item-${REQ}`)
    expect(link.getAttribute('href')).toBe(`/approvals/${REQ}`)
    expect(link.textContent).toContain('Politique accès')
  })

  it('aucune demande : message vide', async () => {
    listMyApprovals.mockResolvedValue([])
    render(wrap(<ApprovalsPage />))
    await waitFor(() => expect(screen.getByText(/Aucune demande en attente/)).toBeTruthy())
  })
})

describe('ApprovalsPage — détail canDecide', () => {
  beforeEach(() => {
    for (const m of [
      listMyApprovals,
      fetchApprovalDetail,
      decideApproval,
      fetchApprovalCompare,
      fetchApplicableWorkflow,
      listAllVersions,
      fetchVersionCompare,
    ]) {
      m.mockReset()
    }
    fetchApprovalCompare.mockResolvedValue({
      documentId: DOC,
      fromVersion: 1,
      toVersion: 2,
      added: 18,
      removed: 4,
      hunks: [],
    })
    fetchApplicableWorkflow.mockResolvedValue({
      steps: [
        { stepOrder: 1, approverRoleName: 'Responsable' },
        { stepOrder: 2, approverRoleName: 'Propriétaire' },
      ],
    })
    listAllVersions.mockResolvedValue({
      items: [{ versionNo: 2, changeSummary: 'Le nouveau flux de provisionnement' }],
      total: 1,
    })
  })

  it('charge le détail (pas mine) : badge, titre, demandeur, SLA, liens impactés', async () => {
    await renderDetail()
    expect(fetchApprovalDetail).toHaveBeenCalledWith(expect.anything(), REQ)
    expect(listMyApprovals).not.toHaveBeenCalled()
    expect(screen.getByTestId('approval-badge').textContent).toContain('EN ATTENTE DE VOTRE DÉCISION')
    expect(screen.getByText('Claire Dubois', { selector: '.appr-requester-name' })).toBeTruthy()
    expect(screen.getByTestId('approval-sla').textContent).toBe('Dans 22h · SLA 48h')
    const compare = screen.getByTestId('approval-compare-link')
    expect(compare.getAttribute('href')).toBe(`/approvals/${REQ}/diff`)
    const link = screen.getByRole('link', { name: 'Procédure de provisioning' })
    expect(link.getAttribute('href')).toBe(`/docs/${LINK_ID}`)
  })

  it('affiche les documents non accessibles (hiddenImpactedCount)', async () => {
    await renderDetail({ ...detailCanDecide, hiddenImpactedCount: 2 })
    expect(screen.getByTestId('approval-hidden-links').textContent).toBe('2 documents non accessibles')
  })

  it('compare la dernière version approuvée à la révision soumise', async () => {
    await renderDetail()
    await waitFor(() => expect(fetchApprovalCompare).toHaveBeenCalledWith(expect.anything(), DOC, 1, 2))
  })

  it('première soumission : pas de lien de comparaison', async () => {
    await renderDetail({ ...detailCanDecide, baselineVersionNo: null as unknown as number })
    expect(screen.queryByTestId('approval-compare-link')).toBeNull()
    expect(screen.getByTestId('approval-no-baseline')).toBeTruthy()
  })

  it('circuit : étape courante, Publication verrouillée', async () => {
    await renderDetail({ ...detailCanDecide, currentStepOrder: 2 })
    await waitFor(() => expect(screen.getByText('N2 · Propriétaire')).toBeTruthy())
    expect(screen.getByText('Verrouillée')).toBeTruthy()
  })

  it('après Approuver, retour à la file vide', async () => {
    fetchApprovalDetail.mockResolvedValue(detailCanDecide)
    listMyApprovals.mockResolvedValue([])
    decideApproval.mockResolvedValue({
      approvalRequestId: REQ,
      temporalWorkflowId: 'doc-approval-1',
      status: 'approuve',
    })

    render(wrap(<ApprovalsPage />, `/approvals/${REQ}`))
    const approve = await screen.findByRole('button', { name: /^Approuver/ })
    fireEvent.click(approve)

    await waitFor(() =>
      expect(decideApproval).toHaveBeenCalledWith(expect.anything(), DOC, REQ, 'approuve', 1, null),
    )
    await waitFor(() => expect(screen.getByText(/Aucune demande en attente/)).toBeTruthy())
  })

  it('refus sans justification : bloqué côté client', async () => {
    await renderDetail()
    fireEvent.click(screen.getByRole('button', { name: 'Refuser' }))
    await waitFor(() => expect(screen.getByRole('alert').textContent).toBe(REJECT_JUSTIFICATION_REQUIRED))
    expect(decideApproval).not.toHaveBeenCalled()
  })

  it('refus avec justification : envoyé avec le commentaire', async () => {
    decideApproval.mockResolvedValue({ approvalRequestId: REQ, temporalWorkflowId: 'x', status: 'rejete' })
    listMyApprovals.mockResolvedValue([])
    await renderDetail()
    fireEvent.change(screen.getByLabelText('Justification de la décision'), {
      target: { value: '  Périmètre trop large ' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Refuser' }))
    await waitFor(() =>
      expect(decideApproval).toHaveBeenCalledWith(
        expect.anything(),
        DOC,
        REQ,
        'rejete',
        1,
        'Périmètre trop large',
      ),
    )
  })

  it('409 step_advanced → message + Recharger', async () => {
    decideApproval.mockRejectedValue({
      response: { status: 409, data: { error: 'step_advanced', message: 'Étape avancée' } },
    })
    await renderDetail()
    fireEvent.click(screen.getByRole('button', { name: /^Approuver/ }))
    await waitFor(() => {
      expect(screen.getByText(/escaladée entretemps/i)).toBeTruthy()
      expect(screen.getByRole('button', { name: 'Recharger' })).toBeTruthy()
    })
  })
})

describe('ApprovalsPage — détail lecture seule', () => {
  beforeEach(() => {
    fetchApprovalDetail.mockReset()
    decideApproval.mockReset()
    fetchApprovalCompare.mockResolvedValue({
      documentId: DOC,
      fromVersion: 1,
      toVersion: 2,
      added: 0,
      removed: 0,
      hunks: [],
    })
    fetchApplicableWorkflow.mockResolvedValue({
      steps: [{ stepOrder: 1, approverRoleName: 'Responsable' }],
    })
    listAllVersions.mockResolvedValue({ items: [{ versionNo: 2, changeSummary: null }], total: 1 })
  })

  it('canDecide false : badge lecture, raison, pas de boutons', async () => {
    fetchApprovalDetail.mockResolvedValue({
      ...detailCanDecide,
      canDecide: false,
      cannotDecideReason: 'requester',
    })
    render(wrap(<ApprovalsPage />, `/approvals/${REQ}`))
    await waitFor(() => expect(screen.getByTestId('approval-badge').textContent).toContain('LECTURE'))
    expect(screen.getByTestId('approval-readonly-reason').textContent).toBe(
      'Vous avez demandé cette approbation',
    )
    expect(screen.queryByRole('button', { name: /^Approuver/ })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Refuser' })).toBeNull()
  })

  it('raison not_current_step_approver inclut le numéro d\'étape', async () => {
    fetchApprovalDetail.mockResolvedValue({
      ...detailCanDecide,
      currentStepOrder: 2,
      canDecide: false,
      cannotDecideReason: 'not_current_step_approver',
    })
    render(wrap(<ApprovalsPage />, `/approvals/${REQ}`))
    await waitFor(() =>
      expect(screen.getByTestId('approval-readonly-reason').textContent).toBe(
        "En attente de l'étape N2",
      ),
    )
  })
})

describe('cannotDecideMessage', () => {
  it('messages français', () => {
    expect(cannotDecideMessage('requester')).toBe('Vous avez demandé cette approbation')
    expect(cannotDecideMessage('contributor')).toBe('Vous êtes contributeur de cette révision')
    expect(cannotDecideMessage('not_current_step_approver', 3)).toBe("En attente de l'étape N3")
    expect(cannotDecideMessage('resolved')).toBe('Cette demande est déjà résolue')
  })
})

describe('formatSla*', () => {
  const now = Date.parse('2026-09-28T12:00:00Z')

  it('compte à rebours lisible', () => {
    expect(formatSlaCountdown('2026-09-28T14:30:00Z', now)).toBe('Reste 2 h 30 min')
    expect(formatSlaCountdown('2026-09-28T11:00:00Z', now)).toBe('Échu depuis 1 h 0 min')
  })

  it('rail : « Dans 22h · SLA 48h »', () => {
    expect(formatSlaRail('2026-09-29T10:00:00Z', '2026-09-27T10:00:00Z', now)).toBe('Dans 22h · SLA 48h')
    expect(formatSlaRail('2026-09-28T12:35:00Z', null, now)).toBe('Dans 35 min')
    expect(formatSlaRail('2026-09-28T09:00:00Z', '2026-09-27T09:00:00Z', now)).toBe('Échu depuis 3h · SLA 24h')
    expect(formatSlaRail(null, null, now)).toBe('SLA non défini')
  })

  it('mobile : « SLA 22h restantes »', () => {
    expect(formatSlaRemaining('2026-09-29T10:00:00Z', now)).toBe('SLA 22h restantes')
    expect(formatSlaRemaining('2026-09-28T09:00:00Z', now)).toBe('SLA échu depuis 3h')
  })
})
