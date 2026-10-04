// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import type { ReactNode } from 'react'
import {
  REJECT_JUSTIFICATION_REQUIRED,
  formatSlaCountdown,
  formatSlaRail,
  formatSlaRemaining,
} from '../lib/approvals'

vi.mock('../lib/api', () => ({ api: {} }))
vi.mock('../auth/AuthProvider', () => ({
  useAuth: () => ({ me: { displayName: 'Tarek Fezai', avatarInitials: 'TF' } }),
}))

const listMyApprovals = vi.fn()
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
  impactedLinks: [{ id: LINK_ID, title: 'Procédure de provisioning' }],
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

async function renderPending(item = pendingItem) {
  listMyApprovals.mockResolvedValue([item])
  render(wrap(<ApprovalsPage />))
  await waitFor(() => expect(screen.getByRole('heading', { name: item.documentTitle })).toBeTruthy())
}

describe('ApprovalsPage', () => {
  beforeEach(() => {
    for (const m of [
      listMyApprovals,
      decideApproval,
      fetchApprovalCompare,
      fetchApplicableWorkflow,
      listAllVersions,
      fetchVersionCompare,
    ]) {
      m.mockReset()
    }
    fetchApprovalCompare.mockResolvedValue({ documentId: DOC, fromVersion: 1, toVersion: 2, added: 18, removed: 4, hunks: [] })
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

  it('affiche le détail : badge, titre, demandeur, SLA, date, lien de comparaison, liens impactés', async () => {
    await renderPending()
    expect(screen.getByTestId('approval-badge').textContent).toContain('EN ATTENTE DE VOTRE DÉCISION')
    expect(screen.getByText('Claire Dubois', { selector: '.appr-requester-name' })).toBeTruthy()
    expect(screen.getByText('CD')).toBeTruthy()
    expect(screen.getByTestId('approval-sla').textContent).toBe('Dans 22h · SLA 48h')
    await waitFor(() =>
      expect(screen.getByText(/incluant le nouveau flux de provisionnement/)).toBeTruthy(),
    )
    const compare = screen.getByTestId('approval-compare-link')
    expect(compare.getAttribute('href')).toBe(`/approvals/${REQ}/diff`)
    await waitFor(() => expect(compare.textContent).toContain('Comparer v1 → v2'))
    await waitFor(() => expect(compare.textContent).toContain('+18 −4'))
    const link = screen.getByRole('link', { name: 'Procédure de provisioning' })
    expect(link.getAttribute('href')).toBe(`/docs/${LINK_ID}`)
  })

  it('compare la dernière version approuvée à la révision soumise (mode lignes), pas le diff JSON', async () => {
    await renderPending()
    await waitFor(() => expect(fetchApprovalCompare).toHaveBeenCalledWith(expect.anything(), DOC, 1, 2))
  })

  it('première soumission : pas de lien de comparaison', async () => {
    await renderPending({ ...pendingItem, baselineVersionNo: null as unknown as number })
    expect(screen.queryByTestId('approval-compare-link')).toBeNull()
    expect(screen.getByTestId('approval-no-baseline')).toBeTruthy()
    expect(fetchApprovalCompare).not.toHaveBeenCalled()
  })

  it('circuit : étape courante, Publication verrouillée', async () => {
    await renderPending({ ...pendingItem, currentStepOrder: 2 })
    await waitFor(() => expect(screen.getByText('N2 · Propriétaire')).toBeTruthy())
    expect(screen.getByText('N1 · Responsable')).toBeTruthy()
    expect(screen.getByText('Approuvé')).toBeTruthy()
    expect(screen.getByText('En attente')).toBeTruthy()
    expect(screen.getByText('Verrouillée')).toBeTruthy()
  })

  it('circuit simplifié (A1) quand le circuit applicable est indisponible', async () => {
    fetchApplicableWorkflow.mockRejectedValue({ response: { status: 403 } })
    await renderPending({ ...pendingItem, currentStepOrder: 2 })
    await waitFor(() => expect(screen.getByText('N1')).toBeTruthy())
    expect(screen.getByText('N2')).toBeTruthy()
    expect(screen.getByText('Verrouillée')).toBeTruthy()
  })

  it('après Approuver, la demande disparaît après rafraîchissement backend', async () => {
    listMyApprovals.mockResolvedValueOnce([pendingItem]).mockResolvedValue([])
    decideApproval.mockResolvedValue({ approvalRequestId: REQ, temporalWorkflowId: 'doc-approval-1', status: 'approuve' })

    render(wrap(<ApprovalsPage />))
    const approve = await screen.findByRole('button', { name: /^Approuver/ })
    fireEvent.click(approve)

    await waitFor(() =>
      expect(decideApproval).toHaveBeenCalledWith(expect.anything(), DOC, REQ, 'approuve', 1, null),
    )
    await waitFor(() => expect(screen.getByText(/Aucune demande en attente/)).toBeTruthy())
    expect(screen.queryByText('Politique accès')).toBeNull()
  })

  it('refus sans justification : bloqué côté client, aucun appel serveur', async () => {
    await renderPending()
    fireEvent.click(screen.getByRole('button', { name: 'Refuser' }))
    await waitFor(() => expect(screen.getByRole('alert').textContent).toBe(REJECT_JUSTIFICATION_REQUIRED))
    expect(decideApproval).not.toHaveBeenCalled()

    // Saisir une justification efface le message.
    fireEvent.change(screen.getByLabelText('Justification de la décision'), { target: { value: 'Périmètre trop large' } })
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('refus avec justification : envoyé avec le commentaire', async () => {
    decideApproval.mockResolvedValue({ approvalRequestId: REQ, temporalWorkflowId: 'x', status: 'rejete' })
    await renderPending()
    fireEvent.change(screen.getByLabelText('Justification de la décision'), { target: { value: '  Périmètre trop large ' } })
    fireEvent.click(screen.getByRole('button', { name: 'Refuser' }))
    await waitFor(() =>
      expect(decideApproval).toHaveBeenCalledWith(expect.anything(), DOC, REQ, 'rejete', 1, 'Périmètre trop large'),
    )
  })

  it('400 serveur sur un refus : message « Justification obligatoire pour un refus » affiché', async () => {
    decideApproval.mockRejectedValue({ response: { status: 400, data: { message: '' } } })
    await renderPending()
    fireEvent.change(screen.getByLabelText('Justification de la décision'), { target: { value: 'x' } })
    fireEvent.click(screen.getByRole('button', { name: 'Refuser' }))
    await waitFor(() => expect(screen.getByRole('alert').textContent).toBe(REJECT_JUSTIFICATION_REQUIRED))
  })

  it('400 serveur avec message : le message du serveur est affiché', async () => {
    decideApproval.mockRejectedValue({
      response: { status: 400, data: { message: 'Justification obligatoire pour un refus' } },
    })
    await renderPending()
    fireEvent.change(screen.getByLabelText('Justification de la décision'), { target: { value: 'x' } })
    fireEvent.click(screen.getByRole('button', { name: 'Refuser' }))
    await waitFor(() => expect(screen.getByRole('alert').textContent).toBe(REJECT_JUSTIFICATION_REQUIRED))
  })

  it('plusieurs demandes : sélecteur affiché, la seconde est sélectionnable', async () => {
    const second = { ...pendingItem, approvalRequestId: 'dddddddd-dddd-dddd-dddd-dddddddddddd', documentTitle: 'Charte SSI' }
    listMyApprovals.mockResolvedValue([pendingItem, second])
    render(wrap(<ApprovalsPage />))
    await screen.findByTestId('approval-switcher')
    fireEvent.click(screen.getByRole('button', { name: /Charte SSI/ }))
    await waitFor(() => expect(screen.getByRole('heading', { name: 'Charte SSI' })).toBeTruthy())
  })

  it('race condition 409 already_resolved → message clair, pas de plantage', async () => {
    decideApproval.mockRejectedValue({
      response: { status: 409, data: { error: 'already_resolved', message: 'Demande déjà résolue' } },
    })
    await renderPending()
    fireEvent.click(screen.getByRole('button', { name: /^Approuver/ }))
    await waitFor(() => expect(screen.getByText(/déjà été traitée/i)).toBeTruthy())
    expect(screen.queryByRole('button', { name: 'Recharger' })).toBeNull()
  })

  it('409 step_advanced → message distinct + bouton Recharger', async () => {
    listMyApprovals.mockResolvedValueOnce([pendingItem]).mockResolvedValue([])
    decideApproval.mockRejectedValue({
      response: { status: 409, data: { error: 'step_advanced', message: 'Étape avancée' } },
    })
    render(wrap(<ApprovalsPage />))
    fireEvent.click(await screen.findByRole('button', { name: /^Approuver/ }))
    await waitFor(() => {
      expect(screen.getByText(/escaladée entretemps/i)).toBeTruthy()
      expect(screen.getByRole('button', { name: 'Recharger' })).toBeTruthy()
    })
    expect(screen.queryByText(/déjà été traitée/i)).toBeNull()

    fireEvent.click(screen.getByRole('button', { name: 'Recharger' }))
    await waitFor(() => expect(screen.getByText(/Aucune demande en attente/)).toBeTruthy())
  })

  it('aucune demande : message vide', async () => {
    listMyApprovals.mockResolvedValue([])
    render(wrap(<ApprovalsPage />))
    await waitFor(() => expect(screen.getByText(/Aucune demande en attente/)).toBeTruthy())
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
