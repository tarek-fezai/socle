// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { useEffect, useId, useRef, useState, type KeyboardEvent as ReactKeyboardEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { api } from '../../lib/api'
import { documentHref } from '../../lib/folders'
import { searchDocuments, type SearchHit } from '../../lib/search'

type Props = {
  open: boolean
  onClose: () => void
}

const DOC_ICON = (
  <>
    <path d="M14 3v4a1 1 0 0 0 1 1h4" />
    <path d="M17 21H7a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h7l5 5v11a2 2 0 0 1-2 2z" />
  </>
)

/** Pastille de statut : `valide` plein vert, `brouillon` contour, autre statut → ambre. */
function StatusDot({ status }: { status: string }) {
  if (status === 'brouillon') {
    return (
      <span
        aria-hidden
        className="box-content h-[6px] w-[6px] shrink-0 rounded-full border-[1.5px] border-solid border-[#DEDEE1]"
      />
    )
  }
  return (
    <span
      aria-hidden
      className="h-[6px] w-[6px] shrink-0 rounded-full"
      style={{ background: status === 'valide' ? '#1E8E5A' : '#B7791F' }}
    />
  )
}

/** Palette Cmd/Ctrl+K — Search.dc.html (760 px). Filtres et aperçu : zones réservées « Bientôt ». */
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
    function onKey(e: globalThis.KeyboardEvent) {
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
    function trap(e: globalThis.KeyboardEvent) {
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

  function onInputKey(e: ReactKeyboardEvent) {
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

  const term = q.trim()

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
        data-mock-id="search-palette"
        data-testid="command-palette"
      >
        <h2 id={titleId} className="sr-only">
          Rechercher
        </h2>

        <div className="cmdk-input-row" data-mock-id="search-input">
          <svg
            width="17"
            height="17"
            viewBox="0 0 24 24"
            fill="none"
            stroke="#9B9BA1"
            strokeWidth="2"
            strokeLinecap="round"
            aria-hidden
            className="shrink-0"
          >
            <circle cx="11" cy="11" r="7" />
            <line x1="21" y1="21" x2="16.65" y2="16.65" />
          </svg>
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
          <span className="cmdk-esc" aria-hidden>
            esc
          </span>
        </div>

        {/* Filtres (domaine, statut, tag, auteur) : pas d'API de facettes → NOT_IMPLEMENTED search-filters. */}
        <div
          className="cmdk-filters"
          data-mock-id="search-filters"
          data-visual-mask="search-filters"
          aria-disabled="true"
        >
          <span className="cmdk-filters-label">Filtres</span>
          <span className="cmdk-chip-soon">Bientôt</span>
        </div>

        <div className="cmdk-body">
          <div
            className="cmdk-results cmdk-list"
            id="cmdk-listbox"
            role="listbox"
            ref={listRef}
            data-mock-id="search-results"
          >
            {term.length < 2 && <div className="cmdk-empty">Tapez au moins 2 caractères…</div>}
            {term.length >= 2 && search.isFetching && results.length === 0 && (
              <div className="cmdk-empty">Recherche…</div>
            )}
            {term.length >= 2 && !search.isFetching && results.length === 0 && (
              <div className="cmdk-empty">Aucun résultat</div>
            )}
            {results.length > 0 && (
              <div className="cmdk-group-head" data-mock-id="search-results-head">
                Documents · {results.length}
              </div>
            )}
            {results.map((hit, i) => {
              const selected = i === active
              return (
                <button
                  key={hit.id}
                  type="button"
                  role="option"
                  className="cmdk-item cmdk-row"
                  aria-selected={selected}
                  data-mock-id={`search-result-${i}`}
                  onMouseEnter={() => setActive(i)}
                  onClick={() => select(hit)}
                >
                  <svg
                    width="15"
                    height="15"
                    viewBox="0 0 24 24"
                    fill="none"
                    stroke={selected ? '#3730E0' : '#6B6B72'}
                    strokeWidth="2"
                    aria-hidden
                    className="shrink-0"
                  >
                    {DOC_ICON}
                  </svg>
                  <span className="cmdk-row-text">
                    <span className="cmdk-item-title" data-mock-id={`search-result-${i}-title`}>
                      {hit.title}
                    </span>
                    {/* Espace + statut (SearchHit) ; mention / tag absents → masque search-result-meta. */}
                    <span className="cmdk-item-meta">
                      <span>
                        {hit.spaceName}
                        {hit.status && hit.status !== 'valide' ? ` · ${hit.status}` : ''}
                      </span>
                      <span
                        aria-hidden
                        className="cmdk-meta-nodata"
                        data-visual-mask="search-result-meta"
                      />
                    </span>
                  </span>
                  {/* Favori depuis la palette : pas dans SearchHit (NOT_IMPLEMENTED search-result-star). */}
                  <span aria-hidden className="cmdk-star" data-visual-mask="search-result-star" />
                  <StatusDot status={hit.status} />
                </button>
              )
            })}
            {term.length >= 2 && (
              <button
                type="button"
                className="cmdk-item cmdk-row cmdk-row--link"
                onClick={() => {
                  onClose()
                  navigate(`/search?q=${encodeURIComponent(term)}`)
                }}
              >
                <span className="cmdk-row-text">
                  <span className="cmdk-item-title">Voir tous les résultats pour « {term} »</span>
                  <span className="cmdk-item-meta">Page recherche</span>
                </span>
              </button>
            )}
            <button
              type="button"
              className="cmdk-item cmdk-row cmdk-row--link"
              onClick={() => {
                onClose()
                navigate('/spaces')
              }}
            >
              <span className="cmdk-row-text">
                <span className="cmdk-item-title">Tous les espaces</span>
                <span className="cmdk-item-meta">/spaces</span>
              </span>
            </button>
          </div>

          {/* Aperçu rapide : pas d'endpoint d'extrait enrichi → NOT_IMPLEMENTED search-preview. */}
          <div
            className="cmdk-preview"
            data-mock-id="search-preview"
            data-visual-mask="search-preview"
            aria-hidden
          >
            <div className="cmdk-preview-soon">Aperçu rapide — bientôt</div>
          </div>
        </div>

        <div className="cmdk-footer" data-mock-id="search-footer">
          <span className="cmdk-hint">
            <kbd>↑↓</kbd>Naviguer
          </span>
          <span className="cmdk-hint">
            <kbd>↵</kbd>Ouvrir
          </span>
          <span className="cmdk-hint cmdk-hint--end">
            Recherche dans <strong>Socle</strong>
          </span>
        </div>
      </div>
    </div>
  )
}
