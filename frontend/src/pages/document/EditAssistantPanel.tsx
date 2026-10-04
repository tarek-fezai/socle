// SPDX-License-Identifier: AGPL-3.0-or-later
import { useEffect, useId, useState, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { useAuth } from '../../auth/AuthProvider'
import { api } from '../../lib/api'
import { SocleRole } from '../../lib/auth'
import {
  fieldInputValue,
  fieldOptions,
  parseFieldInput,
  type CustomFieldView,
} from '../../lib/customFields'
import type { TagRef } from '../../lib/documents'
import { filterTagSuggestions, searchTags } from '../../lib/tags'
import {
  brokenLinkMessage,
  longParagraphMessage,
  type BrokenLink,
  type LongParagraph,
} from '../../lib/writingAssistant'
import { tagCloseColor } from './documentEditUtils'
import { tagColors } from './documentPageUtils'

/* ------------------------------------------------------------------ */
/* Cartes de l'assistant                                                */
/* ------------------------------------------------------------------ */

const MAX_LONG_CARDS = 4

export type AssistantHintsProps = {
  spaceId: string
  threshold: number
  longParagraphs: LongParagraph[]
  brokenLinks: BrokenLink[]
  onGoToParagraph: (index: number) => void
}

function Card({
  tone,
  title,
  children,
  mockId,
  action,
}: {
  tone: string
  title: string
  children: string
  mockId?: string
  action?: ReactNode
}) {
  return (
    <>
      <div className="edit-card-head">
        <span className="edit-card-dot" style={{ background: tone }} />
        <span className="edit-card-title" style={{ color: tone }}>
          {title}
        </span>
        {action}
      </div>
      <p className="edit-card-text" data-mock-id={mockId}>
        {children}
      </p>
    </>
  )
}

export function AssistantHints({
  spaceId,
  threshold,
  longParagraphs,
  brokenLinks,
  onGoToParagraph,
}: AssistantHintsProps) {
  const shown = longParagraphs.slice(0, MAX_LONG_CARDS)
  const hidden = longParagraphs.length - shown.length
  const empty = longParagraphs.length === 0 && brokenLinks.length === 0
  return (
    <>
      {shown.map((p, i) => (
        <div
          key={p.index}
          className="edit-card"
          data-testid="assistant-long-paragraph"
          data-mock-id={i === 0 ? 'edit-card-long' : undefined}
        >
          <Card
            tone="#B7791F"
            title="Paragraphe long"
            mockId={i === 0 ? 'edit-card-long-text' : undefined}
            action={
              <button
                type="button"
                className="edit-card-link"
                data-testid="assistant-goto-paragraph"
                onClick={() => onGoToParagraph(p.index)}
              >
                Aller au paragraphe
              </button>
            }
          >
            {longParagraphMessage(p, threshold)}
          </Card>
        </div>
      ))}
      {hidden > 0 && (
        <p className="edit-assistant-more" data-testid="assistant-more">
          + {hidden} autre{hidden > 1 ? 's' : ''} paragraphe{hidden > 1 ? 's' : ''} long{hidden > 1 ? 's' : ''}
        </p>
      )}
      {brokenLinks.map((l, i) => (
        <Link
          key={l.targetId}
          to={`/spaces/${spaceId}/content-health`}
          className="edit-card edit-card--link"
          data-testid="assistant-broken-link"
          data-mock-id={i === 0 ? 'edit-card-link' : undefined}
        >
          <Card
            tone="#B54708"
            title="Lien cassé détecté"
            mockId={i === 0 ? 'edit-card-link-text' : undefined}
          >
            {brokenLinkMessage(l)}
          </Card>
        </Link>
      ))}
      {empty && (
        <p className="edit-assistant-empty" data-testid="assistant-empty">
          Aucune suggestion pour le moment.
        </p>
      )}
    </>
  )
}

/* ------------------------------------------------------------------ */
/* Tags                                                                 */
/* ------------------------------------------------------------------ */

const GOVERNED_TAG_TITLE = 'Étiquette de gouvernance — réservée aux propriétaires'

function LockIcon() {
  return (
    <svg
      className="edit-tag-lock"
      width="10"
      height="10"
      viewBox="0 0 16 16"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.6"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      data-testid="tag-lock"
    >
      <rect x="3" y="7" width="10" height="7" rx="1.5" />
      <path d="M5.5 7V5a2.5 2.5 0 0 1 5 0v2" />
    </svg>
  )
}

function TagAdder({
  attached,
  canManageGoverned,
  onAdd,
  onClose,
}: {
  attached: TagRef[]
  canManageGoverned: boolean
  onAdd: (tag: { tagId: string } | { name: string }) => void
  onClose: () => void
}) {
  const [q, setQ] = useState('')
  const [debounced, setDebounced] = useState('')
  const [active, setActive] = useState(0)
  const listId = useId()
  useEffect(() => {
    const t = setTimeout(() => setDebounced(q), 150)
    return () => clearTimeout(t)
  }, [q])
  const suggestions = useQuery({
    queryKey: ['tags-search', debounced],
    queryFn: () => searchTags(api, debounced, 8),
    staleTime: 30_000,
  })
  const options = filterTagSuggestions(suggestions.data ?? [], attached)
  const typed = q.trim()
  const exact = options.find((o) => o.name.toLowerCase() === typed.toLowerCase())
  const showCreate = typed.length > 0 && !exact && !attached.some((t) => t.name.toLowerCase() === typed.toLowerCase())

  const blocked = (t: TagRef | undefined) => Boolean(t?.governed) && !canManageGoverned

  const choose = (i: number) => {
    const opt = options[i]
    if (blocked(opt)) return
    if (opt) onAdd({ tagId: opt.id })
    else if (typed) onAdd({ name: typed })
    onClose()
  }

  return (
    <span className="edit-tag-adder">
      <input
        autoFocus
        type="text"
        className="edit-tag-input"
        placeholder="Ajouter un tag…"
        aria-label="Ajouter un tag"
        role="combobox"
        aria-expanded
        aria-controls={listId}
        aria-autocomplete="list"
        data-testid="tag-input"
        value={q}
        maxLength={60}
        onChange={(e) => {
          setQ(e.target.value)
          setActive(0)
        }}
        onKeyDown={(e) => {
          if (e.key === 'Escape') {
            e.preventDefault()
            onClose()
          } else if (e.key === 'ArrowDown') {
            e.preventDefault()
            setActive((a) => Math.min(a + 1, options.length - 1))
          } else if (e.key === 'ArrowUp') {
            e.preventDefault()
            setActive((a) => Math.max(a - 1, 0))
          } else if (e.key === 'Enter') {
            e.preventDefault()
            if (!typed && options.length === 0) return onClose()
            // Étiquette de gouvernance sans droit de propriétaire : rien à ajouter.
            if (blocked(exact) || (!exact && !showCreate && blocked(options[active]))) return
            // Texte saisi identique à une suggestion → cette étiquette ; sinon celle surlignée / création.
            if (exact) onAdd({ tagId: exact.id })
            else if (options[active] && !showCreate) onAdd({ tagId: options[active]!.id })
            else if (typed) onAdd({ name: typed })
            else if (options[active]) onAdd({ tagId: options[active]!.id })
            onClose()
          }
        }}
        onBlur={() => window.setTimeout(onClose, 120)}
      />
      {(options.length > 0 || showCreate) && (
        <ul className="edit-tag-list" id={listId} role="listbox" data-testid="tag-suggestions">
          {options.map((o, i) => (
            <li
              key={o.id}
              role="option"
              aria-selected={i === active}
              aria-disabled={blocked(o) || undefined}
              title={blocked(o) ? GOVERNED_TAG_TITLE : undefined}
              className={[i === active ? 'is-active' : '', blocked(o) ? 'is-disabled' : ''].filter(Boolean).join(' ') || undefined}
              onMouseDown={(e) => {
                e.preventDefault()
                choose(i)
              }}
            >
              {o.governed && <LockIcon />}
              {o.name}
            </li>
          ))}
          {showCreate && (
            <li
              role="option"
              aria-selected={false}
              data-testid="tag-create"
              onMouseDown={(e) => {
                e.preventDefault()
                onAdd({ name: typed })
                onClose()
              }}
            >
              Créer « {typed} »
            </li>
          )}
        </ul>
      )}
    </span>
  )
}

function TagsBlock({
  tags,
  canEdit,
  canManageGoverned,
  error,
  onAdd,
  onRemove,
}: {
  tags: TagRef[]
  canEdit: boolean
  /** Propriétaire : peut rattacher / retirer les étiquettes de gouvernance. */
  canManageGoverned: boolean
  error: string | null
  onAdd: (tag: { tagId: string } | { name: string }) => void
  onRemove: (tag: TagRef) => void
}) {
  const [adding, setAdding] = useState(false)
  return (
    <>
      <div className="edit-meta-sublabel" data-mock-id="edit-meta-tags-label">
        Tags
      </div>
      <div className="edit-tags" data-testid="edit-tags">
        {tags.map((t, i) => {
          const c = tagColors(t, i)
          return (
            <span
              key={t.id}
              className="edit-tag"
              style={{ color: c.fg, background: c.bg }}
              data-mock-id={i === 0 ? 'edit-meta-tag' : undefined}
              data-testid="edit-tag"
            >
              {t.governed && (
                <span title={GOVERNED_TAG_TITLE} style={{ display: 'inline-flex' }}>
                  <LockIcon />
                </span>
              )}
              {t.name}
              {canEdit && (
                <button
                  type="button"
                  className="edit-tag-x"
                  style={{ color: tagCloseColor(c.fg) }}
                  aria-label={`Retirer le tag ${t.name}`}
                  disabled={t.governed && !canManageGoverned ? true : undefined}
                  title={t.governed && !canManageGoverned ? GOVERNED_TAG_TITLE : undefined}
                  onClick={() => onRemove(t)}
                >
                  ×
                </button>
              )}
            </span>
          )
        })}
        {canEdit &&
          (adding ? (
            <TagAdder
              attached={tags}
              canManageGoverned={canManageGoverned}
              onAdd={onAdd}
              onClose={() => setAdding(false)}
            />
          ) : (
            <button
              type="button"
              className="edit-add"
              data-mock-id="edit-meta-add-tag"
              data-testid="tag-add"
              onClick={() => setAdding(true)}
            >
              + Tag
            </button>
          ))}
      </div>
      {error && (
        <p className="edit-meta-error" role="alert" data-testid="tag-error">
          {error}
        </p>
      )}
    </>
  )
}

/* ------------------------------------------------------------------ */
/* Champs personnalisés                                                 */
/* ------------------------------------------------------------------ */

function FieldInput({
  field,
  disabled,
  onSave,
  error,
  saving,
  mockId,
  highlightMissing,
}: {
  field: CustomFieldView
  disabled: boolean
  onSave: (raw: unknown) => void
  error: string | null
  saving: boolean
  mockId?: string
  highlightMissing?: boolean
}) {
  const id = useId()
  const server = fieldInputValue(field)
  const [value, setValue] = useState(server)
  useEffect(() => setValue(server), [server])

  const commit = (raw: string) => {
    if (raw === server) return
    const parsed = parseFieldInput(field.fieldType, raw)
    if (parsed === undefined) return
    onSave(parsed)
  }

  let control: ReactNode
  switch (field.fieldType) {
    case 'case_a_cocher':
      control = (
        <input
          id={id}
          type="checkbox"
          className="edit-field-check"
          checked={field.value === true}
          disabled={disabled}
          onChange={(e) => onSave(e.target.checked)}
        />
      )
      break
    case 'liste': {
      const opts = fieldOptions(field.options)
      control = (
        <select
          id={id}
          className="edit-field-input"
          value={value}
          disabled={disabled}
          data-mock-id={mockId}
          onChange={(e) => {
            setValue(e.target.value)
            commit(e.target.value)
          }}
        >
          <option value="">—</option>
          {opts.map((o) => (
            <option key={o.value} value={o.value}>
              {o.label}
            </option>
          ))}
        </select>
      )
      break
    }
    case 'personne':
    case 'multi_selection':
      control = (
        <input
          id={id}
          type="text"
          className="edit-field-input"
          readOnly
          disabled
          title="Modification non disponible depuis cet écran"
          value={Array.isArray(field.value) ? (field.value as unknown[]).join(', ') : value}
        />
      )
      break
    default:
      control = (
        <input
          id={id}
          type={field.fieldType === 'nombre' ? 'number' : field.fieldType === 'date' ? 'date' : field.fieldType === 'lien' ? 'url' : 'text'}
          className="edit-field-input"
          value={value}
          disabled={disabled}
          data-mock-id={mockId}
          onChange={(e) => setValue(e.target.value)}
          onBlur={(e) => commit(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') (e.target as HTMLInputElement).blur()
          }}
        />
      )
  }

  return (
    <div
      className={`edit-field${highlightMissing ? ' edit-field--missing' : ''}`}
      data-testid="custom-field"
    >
      <label htmlFor={id} className="edit-field-label">
        {field.name}
        {field.required ? ' *' : ''}
      </label>
      {control}
      {saving && <p className="edit-meta-hint">Enregistrement…</p>}
      {error && (
        <p className="edit-meta-error" role="alert">
          {error}
        </p>
      )}
    </div>
  )
}

/* ------------------------------------------------------------------ */
/* Panneau                                                              */
/* ------------------------------------------------------------------ */

export type EditAssistantPanelProps = AssistantHintsProps & {
  owner: string
  reliability: string
  reviewCadence: string
  tags: TagRef[]
  /** Droit d'édition ET verrou détenu : tags / champs modifiables. */
  canEditMeta: boolean
  /** Propriétaire du document (canManageAccess) : gère les étiquettes de gouvernance. */
  canManageGoverned?: boolean
  tagError: string | null
  onAddTag: (tag: { tagId: string } | { name: string }) => void
  onRemoveTag: (tag: TagRef) => void
  customFields: CustomFieldView[]
  fieldErrors: Record<string, string>
  savingFields: Record<string, boolean>
  onSaveField: (field: CustomFieldView, value: unknown) => void
  /** IDs de champs obligatoires manquants (`required_field_missing`). */
  missingRequiredFieldIds?: Set<string>
}

export function EditAssistantPanel(p: EditAssistantPanelProps) {
  const { me } = useAuth()
  const isSystemAdmin = Boolean(me?.roles?.includes(SocleRole.ADMINISTRATEUR_SYSTEME))

  return (
    <aside className="edit-assistant" aria-label="Assistant de rédaction" data-mock-id="edit-assistant" data-testid="edit-assistant">
      <div className="edit-panel-label" data-mock-id="edit-assistant-label">
        Assistant de rédaction
      </div>
      <AssistantHints
        spaceId={p.spaceId}
        threshold={p.threshold}
        longParagraphs={p.longParagraphs}
        brokenLinks={p.brokenLinks}
        onGoToParagraph={p.onGoToParagraph}
      />

      <div className="edit-meta-block" id="metadata" data-mock-id="edit-meta" data-testid="edit-meta">
        <div className="edit-panel-label edit-panel-label--meta" data-mock-id="edit-meta-label">
          Métadonnées
        </div>
        <div className="edit-meta-row" data-mock-id="edit-meta-owner">
          <span className="edit-meta-key">Propriétaire</span>
          <span className="edit-meta-val" data-testid="meta-owner">
            {p.owner}
          </span>
        </div>
        <div className="edit-meta-row" data-mock-id="edit-meta-reliability">
          <span className="edit-meta-key">Fiabilité</span>
          <span className="edit-meta-val" data-testid="meta-reliability">
            {p.reliability}
          </span>
        </div>
        <div className="edit-meta-row edit-meta-row--last" data-mock-id="edit-meta-review">
          <span className="edit-meta-key">Revue prévue</span>
          <span className="edit-meta-val" data-testid="meta-review">
            {p.reviewCadence}
          </span>
        </div>
        <TagsBlock
          tags={p.tags}
          canEdit={p.canEditMeta}
          canManageGoverned={Boolean(p.canManageGoverned)}
          error={p.tagError}
          onAdd={p.onAddTag}
          onRemove={p.onRemoveTag}
        />
        {p.customFields.length > 0 && (
          <div data-testid="edit-custom-fields">
            <div className="edit-meta-customhead">
              <span className="edit-meta-sublabel edit-meta-sublabel--inline" data-mock-id="edit-meta-custom-label">
                Champs personnalisés
              </span>
              {isSystemAdmin ? (
                <Link
                  to="/admin/custom-fields"
                  className="edit-meta-manage"
                  data-mock-id="edit-meta-custom-manage"
                >
                  Gérer →
                </Link>
              ) : null}
            </div>
            <div className="edit-fields">
              {p.customFields.map((f, i) => (
                <FieldInput
                  key={f.id}
                  field={f}
                  disabled={!p.canEditMeta}
                  saving={Boolean(p.savingFields[f.id])}
                  error={p.fieldErrors[f.id] ?? null}
                  mockId={`edit-meta-custom-field-${i + 1}`}
                  highlightMissing={Boolean(p.missingRequiredFieldIds?.has(f.id))}
                  onSave={(v) => p.onSaveField(f, v)}
                />
              ))}
              {isSystemAdmin ? (
                <Link
                  to="/admin/custom-fields?create=1"
                  className="edit-add edit-add--block"
                  data-mock-id="edit-meta-add-field"
                >
                  + Champ
                </Link>
              ) : null}
            </div>
          </div>
        )}
      </div>
    </aside>
  )
}
