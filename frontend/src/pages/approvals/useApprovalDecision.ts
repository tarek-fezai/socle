// SPDX-License-Identifier: AGPL-3.0-or-later
import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { api } from '../../lib/api'
import { apiErrorCode, apiErrorMessage } from '../../lib/apiError'
import {
  REJECT_JUSTIFICATION_REQUIRED,
  approvalConflictCode,
  decideApproval,
  type ApprovalItem,
} from '../../lib/approvals'

export type Decision = 'approuve' | 'rejete'

type Vars = {
  item: ApprovalItem
  decision: Decision
  /** Étape affichée à l'ouverture de la demande — envoyée comme `expectedStepOrder`. */
  expectedStepOrder: number
  comment: string
}

/**
 * Décision sur une demande d'approbation, sans mise à jour optimiste : l'écran ne change qu'après
 * la réponse du serveur. Un refus sans justification est bloqué côté client ; le 400 serveur
 * (« Justification obligatoire pour un refus ») est affiché tel quel.
 */
export function useApprovalDecision({ onDecided }: { onDecided?: () => void } = {}) {
  const queryClient = useQueryClient()
  const [error, setError] = useState<string | null>(null)
  const [stepAdvanced, setStepAdvanced] = useState(false)

  const mutation = useMutation({
    mutationFn: ({ item, decision, expectedStepOrder, comment }: Vars) =>
      decideApproval(
        api,
        item.documentId,
        item.approvalRequestId,
        decision,
        expectedStepOrder,
        comment.trim() || null,
      ),
    onSuccess: () => {
      setError(null)
      setStepAdvanced(false)
      void queryClient.invalidateQueries({ queryKey: ['approvals'] })
      void queryClient.invalidateQueries({ queryKey: ['documents'] })
      onDecided?.()
    },
    onError: (err) => {
      const conflict = approvalConflictCode(err)
      const fallback =
        apiErrorCode(err) === 'reject_justification_required'
          ? REJECT_JUSTIFICATION_REQUIRED
          : 'Échec de la décision'
      setError(apiErrorMessage(err, fallback))
      setStepAdvanced(conflict === 'step_advanced')
      if (conflict !== 'step_advanced') {
        void queryClient.invalidateQueries({ queryKey: ['approvals'] })
      }
    },
  })

  /** @returns false si la décision est bloquée côté client (refus sans justification). */
  function submit(vars: Vars): boolean {
    if (vars.decision === 'rejete' && !vars.comment.trim()) {
      setError(REJECT_JUSTIFICATION_REQUIRED)
      return false
    }
    setError(null)
    mutation.mutate(vars)
    return true
  }

  function reset() {
    setError(null)
    setStepAdvanced(false)
    mutation.reset()
  }

  return { submit, reset, clearError: () => setError(null), pending: mutation.isPending, error, stepAdvanced }
}
