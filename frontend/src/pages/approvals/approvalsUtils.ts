// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
/**
 * Logique pure des écrans d'approbation (Approval.dc.html, DiffApproval.dc.html,
 * MobileApproval.dc.html) : circuit, libellés, textes.
 */
import type { ApplicableWorkflow, ApprovalItem } from '../../lib/approvals'
import { lowerFirst } from '../document/versionHistoryUtils'

export const UNKNOWN_REQUESTER_LABEL = 'Un utilisateur'

export type CircuitStepState = 'done' | 'current' | 'todo'

export type CircuitStep = {
  order: number
  roleName: string | null
  state: CircuitStepState
}

export type Circuit = {
  steps: CircuitStep[]
  /**
   * Exception A1 : l'API ne fournit pas l'historique des étapes (qui a approuvé quoi). Les étapes
   * précédant `currentStepOrder` sont donc déduites « Approuvé » ; sans circuit applicable, seules
   * les étapes 1…currentStepOrder sont affichées (rôles inconnus).
   */
  simplified: boolean
}

export function buildCircuit(
  workflow: ApplicableWorkflow | null | undefined,
  currentStepOrder: number,
): Circuit {
  const defined = [...(workflow?.steps ?? [])].sort((a, b) => a.stepOrder - b.stepOrder)
  if (defined.length > 0 && defined.some((s) => s.stepOrder === currentStepOrder)) {
    return {
      simplified: false,
      steps: defined.map((s) => ({
        order: s.stepOrder,
        roleName: s.approverRoleName?.trim() || null,
        state: s.stepOrder < currentStepOrder ? 'done' : s.stepOrder === currentStepOrder ? 'current' : 'todo',
      })),
    }
  }
  const steps: CircuitStep[] = []
  for (let n = 1; n <= Math.max(1, currentStepOrder); n += 1) {
    steps.push({ order: n, roleName: null, state: n < currentStepOrder ? 'done' : 'current' })
  }
  return { simplified: true, steps }
}

/** « N2 · Propriétaire » (desktop) ; « N2 » sans rôle connu. */
export function circuitStepLabel(step: CircuitStep): string {
  return step.roleName ? `N${step.order} · ${step.roleName}` : `N${step.order}`
}

/** « Propriétaire (N2) » (mobile) ; « N2 » sans rôle connu. */
export function circuitStepLabelMobile(step: CircuitStep): string {
  return step.roleName ? `${step.roleName} (N${step.order})` : `N${step.order}`
}

export const CIRCUIT_STATUS_LABEL: Record<CircuitStepState, string> = {
  done: 'Approuvé',
  current: 'En attente',
  todo: 'À venir',
}

export function requesterName(item: Pick<ApprovalItem, 'requestedByDisplayName'>): string {
  return item.requestedByDisplayName?.trim() || UNKNOWN_REQUESTER_LABEL
}

export function requesterInitials(
  item: Pick<ApprovalItem, 'requestedByDisplayName' | 'requestedByInitials'>,
): string {
  const given = item.requestedByInitials?.trim()
  if (given) return given
  const name = item.requestedByDisplayName?.trim()
  if (!name) return '?'
  const parts = name.split(/\s+/).filter(Boolean)
  if (parts.length === 1) return parts[0]!.slice(0, 2).toUpperCase()
  return (parts[0]![0]! + parts[parts.length - 1]![0]!).toUpperCase()
}

export function revisionLabel(versionNo: number | null | undefined): string {
  return versionNo != null ? `v${versionNo}` : 'cette révision'
}

/** « Approuver la révision v13 » (CTA) ; « Approuver » sans numéro de version. */
export function approveLabel(versionNo: number | null | undefined): string {
  return versionNo != null ? `Approuver la révision v${versionNo}` : 'Approuver'
}

/** Fin de phrase « , incluant … » dérivée du résumé de la révision soumise (facultatif). */
export function ledeTail(summary: string | null | undefined): string {
  const s = summary?.trim()
  return s ? `, incluant ${lowerFirst(s)}` : ''
}

/** Titre de la comparaison : « Comparer v12 → v13 ». */
export function compareTitle(from: number | null | undefined, to: number | null | undefined): string {
  return from != null && to != null ? `Comparer v${from} → v${to}` : 'Comparer les versions'
}

export function approvalDiffHref(requestId: string): string {
  return `/approvals/${requestId}/diff`
}
