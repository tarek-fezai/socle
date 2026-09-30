import { FormEvent, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { useAuth } from '../auth/AuthProvider'
import { api } from '../lib/api'
import { SocleRole } from '../lib/auth'
import { apiErrorMessage } from '../lib/approvals'
import {
  actorLabel,
  exportAuditCsv,
  exportAuditJson,
  formatAuditMetadataSummary,
  listAuditEvents,
  resourceLink,
  type AuditEvent,
  type AuditFilters,
} from '../lib/audit'

const PAGE_SIZE = 50

const ACTION_CHIPS: Array<{ label: string; action?: string }> = [
  { label: 'Toutes les actions' },
  { label: 'Publications', action: 'document.' },
  { label: 'Accès & permissions', action: 'access.' },
]

export function AuditPage() {
  const { me } = useAuth()
  const roles = me?.roles ?? []
  const isAuditeur = roles.includes(SocleRole.AUDITEUR)

  const [draft, setDraft] = useState({
    resourceType: '',
    action: '',
    actorId: '',
    since: '',
    until: '',
  })
  const [applied, setApplied] = useState<AuditFilters>({ limit: PAGE_SIZE, offset: 0 })
  const [expandedId, setExpandedId] = useState<number | null>(null)

  const query = useQuery({
    queryKey: ['audit', applied],
    queryFn: () => listAuditEvents(api, applied),
    enabled: isAuditeur,
    retry: false,
  })

  const filtersForApi = useMemo(() => applied, [applied])

  if (!isAuditeur) {
    return (
      <main className="page-shell">
        <div className="breadcrumb mb-6">
          <Link to="/">Accueil</Link>
          <span className="text-[#DEDEE1]">→</span>
          <span className="font-medium text-socle-ink">Journal d&apos;audit</span>
        </div>
        <h1 className="serif-title">Journal d&apos;audit</h1>
        <p className="mt-4 text-socle-danger">
          Accès réservé au rôle <strong>AUDITEUR</strong>. Votre compte n&apos;a pas ce droit.
        </p>
      </main>
    )
  }

  function onApply(e: FormEvent) {
    e.preventDefault()
    setApplied({
      resourceType: draft.resourceType.trim() || undefined,
      action: draft.action.trim() || undefined,
      actorId: draft.actorId.trim() || undefined,
      since: draft.since ? new Date(draft.since).toISOString() : undefined,
      until: draft.until ? new Date(draft.until).toISOString() : undefined,
      offset: 0,
      limit: PAGE_SIZE,
    })
  }

  function applyChip(action?: string) {
    setDraft((d) => ({ ...d, action: action ?? '' }))
    setApplied({
      resourceType: draft.resourceType.trim() || undefined,
      action: action || undefined,
      actorId: draft.actorId.trim() || undefined,
      since: draft.since ? new Date(draft.since).toISOString() : undefined,
      until: draft.until ? new Date(draft.until).toISOString() : undefined,
      offset: 0,
      limit: PAGE_SIZE,
    })
  }

  function download(kind: 'csv' | 'json') {
    const items = query.data?.items ?? []
    const body = kind === 'csv' ? exportAuditCsv(items) : exportAuditJson(items)
    const blob = new Blob([body], {
      type: kind === 'csv' ? 'text/csv;charset=utf-8' : 'application/json',
    })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `audit-export.${kind}`
    a.click()
    URL.revokeObjectURL(url)
  }

  const total = query.data?.total ?? 0
  const offset = applied.offset ?? 0
  const activeAction = applied.action ?? ''

  return (
    <main className="page-shell-wide">
      <div className="mb-6 flex flex-wrap items-center justify-between gap-3">
        <div className="breadcrumb">
          <Link to="/">Accueil</Link>
          <span className="text-[#DEDEE1]">→</span>
          <span className="font-medium text-socle-ink">Journal d&apos;audit</span>
        </div>
        <div className="flex flex-wrap gap-2">
          <button
            type="button"
            onClick={() => download('csv')}
            disabled={!query.data?.items.length}
            className="btn-primary disabled:opacity-50"
          >
            Exporter (CSV)
          </button>
          <button
            type="button"
            onClick={() => download('json')}
            disabled={!query.data?.items.length}
            className="btn-ghost disabled:opacity-50"
          >
            Export JSON
          </button>
        </div>
      </div>

      <h1 className="serif-title">Journal d&apos;audit</h1>
      <p className="mt-2 text-sm text-socle-muted">
        Traçabilité complète des actions effectuées sur la documentation — conservée 24 mois, non
        modifiable. ({total} événement{total === 1 ? '' : 's'})
      </p>

      <div className="mt-6 flex flex-wrap gap-2">
        {ACTION_CHIPS.map((chip) => {
          const active =
            (chip.action == null && !activeAction) ||
            (chip.action != null && activeAction === chip.action)
          return (
            <button
              key={chip.label}
              type="button"
              onClick={() => applyChip(chip.action)}
              className={
                active
                  ? 'rounded-lg bg-socle-mist px-3 py-1.5 text-[12.5px] font-medium text-socle-accent'
                  : 'rounded-lg border border-socle-line px-3 py-1.5 text-[12.5px] text-socle-slate hover:bg-[#ECECEE]'
              }
            >
              {chip.label}
            </button>
          )
        })}
      </div>

      <form onSubmit={onApply} className="mt-4 space-y-3 rounded-xl border border-socle-line bg-white p-4">
        <div className="grid gap-3 sm:grid-cols-2">
          <label className="block text-xs text-socle-muted">
            Type de ressource
            <input
              value={draft.resourceType}
              onChange={(e) => setDraft((d) => ({ ...d, resourceType: e.target.value }))}
              placeholder="document | space | …"
              className="field-input mt-1"
            />
          </label>
          <label className="block text-xs text-socle-muted">
            Action (exact, préfixe access.*, ou CSV)
            <input
              value={draft.action}
              onChange={(e) => setDraft((d) => ({ ...d, action: e.target.value }))}
              placeholder="access.* | document.updated"
              className="field-input mt-1"
            />
          </label>
          <label className="block text-xs text-socle-muted">
            Acteur (UUID)
            <input
              value={draft.actorId}
              onChange={(e) => setDraft((d) => ({ ...d, actorId: e.target.value }))}
              placeholder="11111111-…"
              className="field-input mt-1 font-mono"
            />
          </label>
          <div className="grid grid-cols-2 gap-2">
            <label className="block text-xs text-socle-muted">
              Depuis
              <input
                type="datetime-local"
                value={draft.since}
                onChange={(e) => setDraft((d) => ({ ...d, since: e.target.value }))}
                className="field-input mt-1"
              />
            </label>
            <label className="block text-xs text-socle-muted">
              Jusqu&apos;à
              <input
                type="datetime-local"
                value={draft.until}
                onChange={(e) => setDraft((d) => ({ ...d, until: e.target.value }))}
                className="field-input mt-1"
              />
            </label>
          </div>
        </div>
        <button type="submit" className="btn-ghost">
          Appliquer les filtres
        </button>
        <p className="font-mono text-[10px] text-socle-faint" data-testid="audit-api-filters">
          {JSON.stringify(filtersForApi)}
        </p>
      </form>

      {query.isLoading && <p className="mt-6 text-socle-muted">Chargement…</p>}
      {query.isError && (
        <p className="mt-6 text-socle-danger">
          {apiErrorMessage(
            query.error,
            'Impossible de charger le journal (rôle AUDITEUR requis ?).',
          )}
        </p>
      )}
      {query.isSuccess && query.data.items.length === 0 && (
        <p className="mt-6 text-socle-muted">Aucun événement pour ces filtres.</p>
      )}

      {query.isSuccess && query.data.items.length > 0 && (
        <div className="mt-6 overflow-x-auto rounded-xl border border-socle-line bg-white">
          <div className="flex border-b border-socle-line bg-socle-soft px-4 py-2.5 text-[11px] font-semibold uppercase tracking-[0.05em] text-socle-faint">
            <div className="w-[140px] shrink-0">Horodatage</div>
            <div className="w-[170px] shrink-0">Auteur</div>
            <div className="min-w-[200px] flex-1">Action</div>
            <div className="w-[110px] shrink-0">IP</div>
          </div>
          <ul>
            {query.data.items.map((e) => (
              <AuditRow
                key={e.id}
                event={e}
                expanded={expandedId === e.id}
                onToggle={() => setExpandedId((id) => (id === e.id ? null : e.id))}
              />
            ))}
          </ul>
        </div>
      )}

      {total > PAGE_SIZE && (
        <div className="mt-6 flex gap-2">
          <button
            type="button"
            disabled={offset <= 0}
            onClick={() =>
              setApplied((a) => ({ ...a, offset: Math.max(0, (a.offset ?? 0) - PAGE_SIZE) }))
            }
            className="btn-ghost disabled:opacity-40"
          >
            Précédent
          </button>
          <button
            type="button"
            disabled={offset + PAGE_SIZE >= total}
            onClick={() => setApplied((a) => ({ ...a, offset: (a.offset ?? 0) + PAGE_SIZE }))}
            className="btn-ghost disabled:opacity-40"
          >
            Suivant
          </button>
        </div>
      )}
    </main>
  )
}

function AuditRow({
  event,
  expanded,
  onToggle,
}: {
  event: AuditEvent
  expanded: boolean
  onToggle: () => void
}) {
  const summary = formatAuditMetadataSummary(event)
  const href = resourceLink(event)
  const when = new Date(event.createdAt)
  const stamp = when.toLocaleString('fr-FR', {
    day: '2-digit',
    month: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })

  return (
    <li className="border-b border-[#F5F5F7] last:border-b-0 hover:bg-socle-soft">
      <div className="flex flex-wrap items-start gap-y-2 px-4 py-3 text-[13px] sm:flex-nowrap">
        <div className="w-[140px] shrink-0 font-mono text-socle-slate">{stamp}</div>
        <div className="w-[170px] shrink-0 text-socle-ink">{actorLabel(event)}</div>
        <div className="min-w-[200px] flex-1 text-[#43434A]">
          <span className="font-medium text-socle-ink">{event.action}</span>
          {summary ? <span className="text-socle-slate"> — {summary}</span> : null}
          <div className="mt-1 flex flex-wrap gap-3 text-sm">
            {href && (
              <Link to={href} className="font-semibold text-socle-accent hover:underline">
                Ouvrir la ressource
              </Link>
            )}
            <button
              type="button"
              onClick={onToggle}
              className="text-socle-slate underline-offset-4 hover:underline"
            >
              {expanded ? 'Masquer le détail brut' : 'Voir le détail brut'}
            </button>
          </div>
        </div>
        <div className="w-[110px] shrink-0 font-mono text-socle-faint">
          {event.ipAddress ?? '—'}
        </div>
      </div>
      {expanded && (
        <pre className="mx-4 mb-3 overflow-x-auto rounded-lg bg-socle-soft p-3 font-mono text-[11px] text-socle-slate">
          {event.metadata ?? 'null'}
        </pre>
      )}
    </li>
  )
}
