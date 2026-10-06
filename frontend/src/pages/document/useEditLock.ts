// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { useEffect, useRef, useState } from 'react'
import { api } from '../../lib/api'
import {
  acquireEditLock,
  heartbeatEditLock,
  releaseEditLock,
  type EditLockStatus,
} from '../../lib/editLock'

export type EditLockState = {
  /** Dernier état connu du verrou exclusif (null tant que la première réponse n'est pas arrivée). */
  lock: EditLockStatus | null
  /** Première acquisition terminée (succès ou échec). */
  settled: boolean
}

/**
 * Verrou d'édition exclusif : acquisition à l'ouverture, battement de cœur périodique
 * (le serveur reprend la main dès que le détenteur précédent a expiré) et libération à la sortie.
 */
export function useEditLock(documentId: string, enabled: boolean): EditLockState {
  const [state, setState] = useState<EditLockState>({ lock: null, settled: false })
  const heldRef = useRef(false)

  useEffect(() => {
    if (!enabled || !documentId) return
    let cancelled = false
    let timer: ReturnType<typeof setTimeout> | null = null

    const apply = (lock: EditLockStatus) => {
      if (cancelled) return
      heldRef.current = lock.heldByCurrentUser
      setState({ lock, settled: true })
    }

    const schedule = (secs: number) => {
      timer = setTimeout(() => {
        void heartbeatEditLock(api, documentId)
          .then((l) => {
            apply(l)
            schedule(l.heartbeatSeconds || 15)
          })
          .catch(() => schedule(secs))
      }, Math.max(1, secs) * 1000)
    }

    void acquireEditLock(api, documentId)
      .then((l) => {
        apply(l)
        schedule(l.heartbeatSeconds || 15)
      })
      .catch(() => {
        if (cancelled) return
        setState((s) => ({ ...s, settled: true }))
        schedule(15)
      })

    return () => {
      cancelled = true
      if (timer) clearTimeout(timer)
      if (heldRef.current) {
        heldRef.current = false
        void releaseEditLock(api, documentId).catch(() => undefined)
      }
    }
  }, [documentId, enabled])

  return state
}
