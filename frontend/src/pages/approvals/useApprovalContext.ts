// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { useMemo } from 'react'
import { useQuery } from '@tanstack/react-query'
import { api } from '../../lib/api'
import { fetchApplicableWorkflow, fetchApprovalCompare, type ApprovalItem } from '../../lib/approvals'
import { listAllVersions } from '../../lib/documents'
import { buildCircuit, type Circuit } from './approvalsUtils'

export type ApprovalContext = {
  /** Résumé de changement de la révision soumise (facultatif). */
  summary: string | null
  circuit: Circuit
  /** Lignes ajoutées / supprimées entre la dernière version approuvée et la révision soumise. */
  added: number | null
  removed: number | null
  compareLoading: boolean
}

/**
 * Données complémentaires d'une demande : résumé de la révision (liste des versions), circuit
 * applicable et compteurs de lignes. Les requêtes partagent leurs clés avec l'écran Historique /
 * Comparer (`document-versions-all`, `document-compare`).
 */
export function useApprovalContext(item: ApprovalItem | null): ApprovalContext {
  const documentId = item?.documentId ?? ''
  const from = item?.baselineVersionNo ?? null
  const to = item?.submittedVersionNo ?? null

  const versions = useQuery({
    queryKey: ['document-versions-all', documentId],
    queryFn: () => listAllVersions(api, documentId),
    enabled: Boolean(item) && to != null,
  })
  const workflow = useQuery({
    queryKey: ['approval-applicable-workflow', documentId],
    queryFn: () => fetchApplicableWorkflow(api, documentId),
    enabled: Boolean(item),
    staleTime: 60_000,
    retry: false,
  })
  const compare = useQuery({
    queryKey: ['document-compare', documentId, from, to],
    queryFn: () => fetchApprovalCompare(api, documentId, from!, to!),
    enabled: Boolean(item) && from != null && to != null && from !== to,
  })

  const summary =
    to != null ? (versions.data?.items?.find((v) => v.versionNo === to)?.changeSummary?.trim() || null) : null
  const circuit = useMemo(
    () => buildCircuit(workflow.data, item?.currentStepOrder ?? 1),
    [workflow.data, item?.currentStepOrder],
  )

  return {
    summary,
    circuit,
    added: compare.data?.added ?? null,
    removed: compare.data?.removed ?? null,
    compareLoading: compare.isLoading,
  }
}
