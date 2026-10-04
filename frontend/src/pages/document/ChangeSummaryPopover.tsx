// SPDX-License-Identifier: AGPL-3.0-or-later
import { useEffect, useId, useRef, useState, type ReactNode } from 'react'

export const CHANGE_SUMMARY_MAX = 200

type Props = {
  /** Libellé du bouton déclencheur. */
  label: string
  disabled?: boolean
  title?: string
  /** Classe CSS du bouton (edit-cta / edit-ghost). */
  buttonClassName: string
  testId: string
  /** Appelé avec le résumé (chaîne vide si facultatif non saisi). */
  onConfirm: (changeSummary: string) => void
  /** Exception visuelle déclarée : popover compact hors maquette stricte. */
  children?: ReactNode
}

/**
 * Petit popover « Résumé (facultatif) » rattaché à Enregistrer / Envoyer en révision.
 * Exception visuelle déclarée (hors maquette pixel-perfect). Ctrl/Cmd+Entrée valide.
 */
export function ChangeSummaryPopover({
  label,
  disabled,
  title,
  buttonClassName,
  testId,
  onConfirm,
}: Props) {
  const [open, setOpen] = useState(false)
  const [summary, setSummary] = useState('')
  const panelId = useId()
  const inputRef = useRef<HTMLTextAreaElement>(null)
  const rootRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!open) return
    inputRef.current?.focus()
    const onDoc = (e: MouseEvent) => {
      if (!rootRef.current?.contains(e.target as Node)) setOpen(false)
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false)
    }
    document.addEventListener('mousedown', onDoc)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onDoc)
      document.removeEventListener('keydown', onKey)
    }
  }, [open])

  const confirm = () => {
    const value = summary.trim().slice(0, CHANGE_SUMMARY_MAX)
    setOpen(false)
    setSummary('')
    onConfirm(value)
  }

  return (
    <div className="edit-summary-wrap" ref={rootRef} data-testid={`${testId}-wrap`}>
      <button
        type="button"
        className={buttonClassName}
        disabled={disabled}
        title={title}
        aria-expanded={open}
        aria-controls={panelId}
        onClick={() => {
          if (disabled) return
          setOpen((v) => !v)
        }}
        data-mock-id={testId}
        data-testid={testId}
      >
        {label}
      </button>
      {open && (
        <div
          id={panelId}
          className="edit-summary-popover"
          role="dialog"
          aria-label="Résumé de modification (facultatif)"
          data-testid={`${testId}-popover`}
        >
          <label className="edit-summary-label" htmlFor={`${panelId}-input`}>
            Résumé (facultatif)
          </label>
          <textarea
            id={`${panelId}-input`}
            ref={inputRef}
            className="edit-summary-input"
            rows={2}
            maxLength={CHANGE_SUMMARY_MAX}
            value={summary}
            placeholder="Laisser vide pour un résumé automatique"
            data-testid={`${testId}-input`}
            onChange={(e) => setSummary(e.target.value.slice(0, CHANGE_SUMMARY_MAX))}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
                e.preventDefault()
                confirm()
              }
            }}
          />
          <div className="edit-summary-footer">
            <span className="edit-summary-count" aria-live="polite">
              {summary.length}/{CHANGE_SUMMARY_MAX}
            </span>
            <button
              type="button"
              className="edit-cta"
              data-testid={`${testId}-confirm`}
              onClick={confirm}
            >
              Valider
            </button>
          </div>
        </div>
      )}
    </div>
  )
}
