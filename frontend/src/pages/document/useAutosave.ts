// SPDX-License-Identifier: AGPL-3.0-or-later
import { useCallback, useEffect, useRef, useState } from 'react'
import type { SaveStatus } from './documentEditUtils'

export const AUTOSAVE_DELAY_MS = 1000

type Options<T> = {
  /** Valeur courante du brouillon (titre + corps…). */
  value: T
  /** Valeur déjà enregistrée au chargement. */
  initial: T
  /** Faux = lecture seule : aucun enregistrement automatique. */
  enabled: boolean
  /** Enregistrement ; rejette en cas d'échec. */
  save: (value: T) => Promise<unknown>
  /** Instant affiché au chargement (« Brouillon enregistré à … »). */
  initialSavedAt?: Date | null
  delayMs?: number
  /** Message d'erreur affichable à partir de l'exception. */
  describeError?: (e: unknown) => string
  now?: () => Date
}

export type Autosave = {
  status: SaveStatus
  /** Relance immédiate (bouton « Réessayer »). */
  retry: () => Promise<boolean>
  /** Enregistre sans attendre le délai ; résout `true` si plus rien n'est en attente. */
  flush: () => Promise<boolean>
  /** Modifications locales non encore enregistrées. */
  dirty: boolean
}

/**
 * Enregistrement automatique avec debounce (~1 s) :
 * - un seul appel à la fois, un second passage est enchaîné si le brouillon a encore bougé ;
 * - un échec n'est pas relancé en boucle : « Réessayer » ou une nouvelle modification relance.
 */
export function useAutosave<T>({
  value,
  initial,
  enabled,
  save,
  initialSavedAt = null,
  delayMs = AUTOSAVE_DELAY_MS,
  describeError,
  now = () => new Date(),
}: Options<T>): Autosave {
  const keyOf = (v: T) => JSON.stringify(v)
  const valueRef = useRef(value)
  valueRef.current = value
  const saveRef = useRef(save)
  saveRef.current = save
  const describeRef = useRef(describeError)
  describeRef.current = describeError
  const nowRef = useRef(now)
  nowRef.current = now

  const savedKey = useRef(keyOf(initial))
  const inflight = useRef<Promise<boolean> | null>(null)
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const enabledRef = useRef(enabled)
  enabledRef.current = enabled
  const mounted = useRef(true)

  const [status, setStatus] = useState<SaveStatus>({ kind: 'saved', at: initialSavedAt })
  const [dirty, setDirty] = useState(false)

  const clearTimer = () => {
    if (timer.current) {
      clearTimeout(timer.current)
      timer.current = null
    }
  }

  const run = useCallback((): Promise<boolean> => {
    if (inflight.current) return inflight.current
    const p = (async () => {
      clearTimer()
      let ok = true
      // Boucle : enchaîne tant que le brouillon a bougé pendant l'enregistrement.
      while (keyOf(valueRef.current) !== savedKey.current) {
        const snapshot = valueRef.current
        const snapKey = keyOf(snapshot)
        if (mounted.current) setStatus({ kind: 'saving' })
        try {
          await saveRef.current(snapshot)
          savedKey.current = snapKey
          if (mounted.current) setStatus({ kind: 'saved', at: nowRef.current() })
        } catch (e) {
          ok = false
          if (mounted.current) {
            setStatus({ kind: 'error', message: describeRef.current?.(e) })
          }
          break
        }
      }
      if (mounted.current) setDirty(keyOf(valueRef.current) !== savedKey.current)
      return ok
    })().finally(() => {
      inflight.current = null
    })
    inflight.current = p
    return p
  }, [])

  // Planifie l'enregistrement à chaque modification.
  const key = keyOf(value)
  useEffect(() => {
    if (!enabled) return
    if (key === savedKey.current) {
      setDirty(false)
      return
    }
    setDirty(true)
    setStatus((s) => (s.kind === 'saving' ? s : { kind: 'saving' }))
    clearTimer()
    timer.current = setTimeout(() => {
      timer.current = null
      void run()
    }, delayMs)
    return clearTimer
  }, [key, enabled, delayMs, run])

  // Au démontage : dernier enregistrement « au mieux » si des modifications sont en attente.
  useEffect(() => {
    mounted.current = true
    return () => {
      mounted.current = false
      if (enabledRef.current && keyOf(valueRef.current) !== savedKey.current && !inflight.current) {
        void saveRef.current(valueRef.current).catch(() => undefined)
      }
      clearTimer()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const flush = useCallback(async () => {
    if (inflight.current) await inflight.current
    if (keyOf(valueRef.current) === savedKey.current) return true
    return run()
  }, [run])

  const retry = useCallback(() => run(), [run])

  return { status, retry, flush, dirty }
}
