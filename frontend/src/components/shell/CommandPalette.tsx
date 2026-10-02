// SPDX-License-Identifier: AGPL-3.0-or-later
import { useEffect, useId, useRef, useState, type KeyboardEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { api } from '../../lib/api'
import { documentHref } from '../../lib/folders'
import { searchDocuments, type SearchHit } from '../../lib/search'

type Props = {
  open: boolean
  onClose: () => void
}

export function CommandPalette({ open, onClose }: Props) {
  const navigate = useNavigate()
  const titleId = useId()
  const inputRef = useRef<HTMLInputElement>(null)
  const listRef = useRef<HTMLDivElement>(null)
  const [q, setQ] = useState('')
  const [active, setActive] = useState(0)

  const search = useQuery({
    queryKey: ['command-palette', q],
    queryFn: () => searchDocuments(api, { q, limit: 12 }),
    enabled: open && q.trim().length >= 2,
  })

  const results: SearchHit[] = search.data?.results ?? []

  useEffect(() => {
    if (!open) {
      setQ('')
      setActive(0)
      return
    }
    const t = window.setTimeout(() => inputRef.current?.focus(), 0)
    return () => window.clearTimeout(t)
  }, [open])

  useEffect(() => {
    setActive(0)
  }, [q, results.length])

  useEffect(() => {
    if (!open) return
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape') {
        e.preventDefault()
        onClose()
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [open, onClose])

  // Focus trap
  useEffect(() => {
    if (!open) return
    function trap(e: KeyboardEvent) {
      if (e.key !== 'Tab') return
      const root = listRef.current?.closest('.cmdk-dialog')
      if (!root) return
      const focusables = root.querySelectorAll<HTMLElement>(
        'input, button.cmdk-item, [tabindex]:not([tabindex="-1"])',
      )
      if (focusables.length === 0) return
      const first = focusables[0]
      const last = focusables[focusables.length - 1]
      if (e.shiftKey && document.activeElement === first) {
        e.preventDefault()
        last.focus()
      } else if (!e.shiftKey && document.activeElement === last) {
        e.preventDefault()
        first.focus()
      }
    }
    window.addEventListener('keydown', trap)
    return () => window.removeEventListener('keydown', trap)
  }, [open])

  if (!open) return null

  function select(hit: SearchHit) {
    onClose()
    navigate(documentHref(hit.id))
  }

  function onInputKey(e: KeyboardEvent) {
    if (e.key === 'ArrowDown') {
      e.preventDefault()
      setActive((i) => Math.min(i + 1, Math.max(results.length - 1, 0)))
    } else if (e.key === 'ArrowUp') {
      e.preventDefault()
      setActive((i) => Math.max(i - 1, 0))
    } else if (e.key === 'Enter') {
      e.preventDefault()
      const hit = results[active]
      if (hit) select(hit)
      else if (q.trim()) {
        onClose()
        navigate(`/search?q=${encodeURIComponent(q.trim())}`)
      }
    }
  }

  return (
    <div
      className="cmdk-overlay"
      role="presentation"
      onMouseDown={(e) => {
        if (e.target === e.currentTarget) onClose()
      }}
    >
      <div
        className="cmdk-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        data-mock-id="command-palette"
        data-testid="command-palette"
      >
        <h2 id={titleId} className="sr-only">
          Rechercher
        </h2>
        <input
          ref={inputRef}
          className="cmdk-input"
          placeholder="Rechercher des documents…"
          value={q}
          onChange={(e) => setQ(e.target.value)}
          onKeyDown={onInputKey}
          aria-autocomplete="list"
          aria-controls="cmdk-listbox"
          data-testid="command-palette-input"
        />
        <div className="cmdk-list" id="cmdk-listbox" role="listbox" ref={listRef}>
          {q.trim().length < 2 && (
            <div className="cmdk-empty">Tapez au moins 2 caractères…</div>
          )}
          {q.trim().length >= 2 && search.isFetching && results.length === 0 && (
            <div className="cmdk-empty">Recherche…</div>
          )}
          {q.trim().length >= 2 && !search.isFetching && results.length === 0 && (
            <div className="cmdk-empty">Aucun résultat</div>
          )}
          {results.map((hit, i) => (
            <button
              key={hit.id}
              type="button"
              role="option"
              className="cmdk-item"
              aria-selected={i === active}
              onMouseEnter={() => setActive(i)}
              onClick={() => select(hit)}
            >
              <span className="cmdk-item-title">{hit.title}</span>
              <span className="cmdk-item-meta">
                {hit.spaceName}
                {hit.status ? ` · ${hit.status}` : ''}
              </span>
            </button>
          ))}
          {q.trim().length >= 2 && (
            <button
              type="button"
              className="cmdk-item"
              onClick={() => {
                onClose()
                navigate(`/search?q=${encodeURIComponent(q.trim())}`)
              }}
            >
              <span className="cmdk-item-title">Voir tous les résultats pour « {q.trim()} »</span>
              <span className="cmdk-item-meta">Page recherche</span>
            </button>
          )}
          <button
            type="button"
            className="cmdk-item"
            onClick={() => {
              onClose()
              navigate('/spaces')
            }}
          >
            <span className="cmdk-item-title">Tous les espaces</span>
            <span className="cmdk-item-meta">/spaces</span>
          </button>
        </div>
      </div>
    </div>
  )
}
