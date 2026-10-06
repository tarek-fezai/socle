// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { useCallback, useEffect, useMemo, useState } from 'react'
import { Link, useInRouterContext } from 'react-router-dom'
import {
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  Legend,
  Line,
  LineChart,
  Pie,
  PieChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import { api } from '../../lib/api'
import { attachmentObjectUrl } from '../../lib/attachments'
import { isSafeHttpUrl } from '../../lib/comments'
import { AttachmentImage } from '../attachments/AttachmentViews'
import {
  buttonTarget,
  chartAccessibilityLabel,
  formatDateFr,
  type ChartSeries,
  type ChartType,
  parseChartSeries,
  parseStringList,
} from './richBlockUtils'

export const DOCUMENT_INACCESSIBLE_LABEL = 'Document inaccessible'
export const VIDEO_UNAVAILABLE_LABEL = 'Vidéo indisponible'
export const POLL_CLOSED_LABEL = 'Sondage fermé'

type PollOptionResult = { option: string; count: number }

type PollApiView = {
  question: string
  options: string[]
  closed: boolean
  myVote: string | null
  results: PollOptionResult[]
  totalVotes: number
}

/* ------------------------------------------------------------------ */
/* Date                                                                 */
/* ------------------------------------------------------------------ */

/** Puce de date (lecture) : « 15 octobre 2026 ». */
export function DateChip({ value }: { value: unknown }) {
  const iso = typeof value === 'string' ? value : ''
  return (
    <time className="doc-date" dateTime={iso || undefined} data-testid="doc-date">
      {formatDateFr(value)}
    </time>
  )
}

/* ------------------------------------------------------------------ */
/* Bouton                                                               */
/* ------------------------------------------------------------------ */

const accessCache = new Map<string, Promise<boolean>>()

/** Vide le cache d'accès aux documents (tests). */
export function clearDocumentAccessCache(): void {
  accessCache.clear()
}

/** Le document est-il lisible par l'utilisateur courant ? (GET ; toute erreur → inaccessible) */
function documentAccessible(id: string): Promise<boolean> {
  let p = accessCache.get(id)
  if (!p) {
    p = api.get(`/api/v1/documents/${encodeURIComponent(id)}`).then(
      () => true,
      () => false,
    )
    accessCache.set(id, p)
  }
  return p
}

function useDocumentAccess(id: string | null): 'loading' | 'ok' | 'denied' {
  const [state, setState] = useState<{ id: string | null; ok?: boolean }>({ id: null })
  useEffect(() => {
    if (!id) return
    let cancelled = false
    void documentAccessible(id).then((ok) => !cancelled && setState({ id, ok }))
    return () => {
      cancelled = true
    }
  }, [id])
  if (!id || state.id !== id) return 'loading'
  return state.ok ? 'ok' : 'denied'
}

function DocumentButton({ label, documentId, to }: { label: string; documentId: string; to: string }) {
  const access = useDocumentAccess(documentId)
  const inRouter = useInRouterContext()
  if (access === 'denied') {
    return (
      <button type="button" className="doc-cta is-disabled" disabled data-testid="doc-button-denied">
        {DOCUMENT_INACCESSIBLE_LABEL}
      </button>
    )
  }
  if (access === 'loading') {
    return (
      <button type="button" className="doc-cta is-disabled" disabled aria-busy="true" data-testid="doc-button-loading">
        {label}
      </button>
    )
  }
  return inRouter ? (
    <Link to={to} className="doc-cta" data-testid="doc-button-internal">
      {label}
    </Link>
  ) : (
    <a href={to} className="doc-cta" data-testid="doc-button-internal">
      {label}
    </a>
  )
}

/** Bloc bouton (CTA) : lien externe, document interne (si accessible) ou bouton inerte. */
export function RichButton({ label, href, documentId }: { label?: unknown; href?: unknown; documentId?: unknown }) {
  const text = typeof label === 'string' && label.trim() ? label : 'Action'
  const target = buttonTarget({ href, documentId })
  let inner
  if (target.kind === 'document') {
    inner = <DocumentButton label={text} documentId={target.documentId} to={target.to} />
  } else if (target.kind === 'external') {
    inner = (
      <a className="doc-cta" href={target.href} target="_blank" rel="noopener noreferrer" data-testid="doc-button-external">
        {text}
      </a>
    )
  } else {
    inner = (
      <span className="doc-cta is-disabled" aria-disabled="true" data-testid="doc-button-inert">
        {text}
      </span>
    )
  }
  return (
    <div className="doc-cta-wrap" data-testid="doc-button">
      {inner}
    </div>
  )
}

/* ------------------------------------------------------------------ */
/* Sondage                                                              */
/* ------------------------------------------------------------------ */

/** Sondage : vote via API, barres de résultats agrégées (jamais de liste nominative). */
export function PollView({
  pollId,
  question,
  options,
}: {
  pollId: string
  question: string
  options: string[]
}) {
  const [apiState, setApiState] = useState<PollApiView | null>(null)
  const [loadState, setLoadState] = useState<'loading' | 'ready' | 'error'>('loading')
  const [voting, setVoting] = useState(false)

  const load = useCallback(() => {
    if (!pollId) {
      setLoadState('error')
      return
    }
    setLoadState('loading')
    void api.get(`/api/v1/polls/${encodeURIComponent(pollId)}`).then(
      (r) => {
        setApiState(r.data as PollApiView)
        setLoadState('ready')
      },
      () => setLoadState('error'),
    )
  }, [pollId])

  useEffect(() => {
    load()
  }, [load])

  const q = apiState?.question ?? question
  const opts = apiState?.options ?? options
  const closed = apiState?.closed ?? false
  const total = apiState?.totalVotes ?? 0
  const myVote = apiState?.myVote ?? null
  const results = apiState?.results ?? []

  const countFor = (opt: string) => results.find((r) => r.option === opt)?.count ?? 0

  const vote = (option: string) => {
    if (closed || voting || !pollId) return
    setVoting(true)
    void api
      .put(`/api/v1/polls/${encodeURIComponent(pollId)}/vote`, { option })
      .then((r) => {
        setApiState(r.data as PollApiView)
        setLoadState('ready')
      })
      .finally(() => setVoting(false))
  }

  const showStats = loadState === 'ready' || loadState === 'error'

  return (
    <div className="doc-poll" data-testid="doc-poll">
      <p className="doc-poll-question">{q}</p>
      {closed ? (
        <p className="doc-poll-closed" data-testid="doc-poll-closed">
          {POLL_CLOSED_LABEL}
        </p>
      ) : null}
      <ul className="doc-poll-options">
        {opts.map((opt) => {
          const count = showStats ? countFor(opt) : 0
          const pct = total > 0 ? Math.round((count / total) * 100) : 0
          const selected = myVote === opt
          return (
            <li key={opt}>
              <button
                type="button"
                className={`doc-poll-option${selected ? ' is-voted' : ''}`}
                disabled={closed || voting || loadState === 'loading'}
                data-testid={`doc-poll-option-${opt}`}
                onClick={() => vote(opt)}
              >
                <span className="doc-poll-option-label">{opt}</span>
                {showStats && total > 0 ? (
                  <>
                    <span className="doc-poll-bar-wrap" aria-hidden>
                      <span className="doc-poll-bar" style={{ width: `${pct}%` }} />
                    </span>
                    <span className="doc-poll-stat" data-testid="doc-poll-stat">
                      {count} ({pct}&nbsp;%)
                    </span>
                  </>
                ) : null}
              </button>
            </li>
          )
        })}
      </ul>
    </div>
  )
}

/* ------------------------------------------------------------------ */
/* Graphique                                                            */
/* ------------------------------------------------------------------ */

const CHART_COLORS = ['#3730E0', '#0D8A7C', '#B7791F', '#B54708', '#6B6862']

function ChartInner({
  chartType,
  labels,
  series,
}: {
  chartType: ChartType
  labels: string[]
  series: ChartSeries[]
}) {
  const data = useMemo(() => {
    return labels.map((label, i) => {
      const row: Record<string, string | number> = { label }
      for (const s of series) row[s.name] = s.values[i] ?? 0
      return row
    })
  }, [labels, series])

  if (chartType === 'pie' && series[0]) {
    const s0 = series[0]
    const pieData = labels.map((label, i) => ({ name: label, value: s0.values[i] ?? 0 }))
    return (
      <PieChart>
        <Tooltip />
        <Pie data={pieData} dataKey="value" nameKey="name" cx="50%" cy="50%" outerRadius="80%">
          {pieData.map((_, i) => (
            <Cell key={i} fill={CHART_COLORS[i % CHART_COLORS.length]} />
          ))}
        </Pie>
        <Legend />
      </PieChart>
    )
  }

  if (chartType === 'line') {
    return (
      <LineChart data={data}>
        <CartesianGrid strokeDasharray="3 3" />
        <XAxis dataKey="label" />
        <YAxis />
        <Tooltip />
        <Legend />
        {series.map((s, i) => (
          <Line key={s.name} type="monotone" dataKey={s.name} stroke={CHART_COLORS[i % CHART_COLORS.length]} />
        ))}
      </LineChart>
    )
  }

  return (
    <BarChart data={data}>
      <CartesianGrid strokeDasharray="3 3" />
      <XAxis dataKey="label" />
      <YAxis />
      <Tooltip />
      <Legend />
      {series.map((s, i) => (
        <Bar key={s.name} dataKey={s.name} fill={CHART_COLORS[i % CHART_COLORS.length]} />
      ))}
    </BarChart>
  )
}

/** Graphique Recharts (lecture / édition). */
export function ChartView({
  chartType,
  labels,
  series,
  title,
}: {
  chartType: ChartType
  labels: string[]
  series: ChartSeries[]
  title?: string | null
}) {
  const lbls = parseStringList(labels)
  const ser = parseChartSeries(series)
  const aria = chartAccessibilityLabel(title, lbls, ser)
  return (
    <figure className="doc-chart-wrap" data-testid="doc-chart">
      {title?.trim() ? <figcaption className="doc-chart-title">{title.trim()}</figcaption> : null}
      <div className="doc-chart-canvas" role="img" aria-label={aria} data-testid="doc-chart-aria">
        <ResponsiveContainer width="100%" height={280}>
          <ChartInner chartType={chartType} labels={lbls.length ? lbls : ['A']} series={ser.length ? ser : [{ name: 'Série', values: [0] }]} />
        </ResponsiveContainer>
      </div>
    </figure>
  )
}

/* ------------------------------------------------------------------ */
/* Aperçu de lien                                                       */
/* ------------------------------------------------------------------ */

/** Carte d'aperçu : URL + domaine par défaut ; titre / vignette optionnels. */
export function LinkPreviewCard({
  url,
  title,
  domain,
  thumbnailId,
}: {
  url: string
  title?: string | null
  domain?: string | null
  thumbnailId?: string | null
}) {
  const safe = isSafeHttpUrl(url) ? url : null
  const host = domain?.trim() || (safe ? new URL(safe).hostname.replace(/^www\./i, '') : '')
  const heading = title?.trim() || safe || url
  const inner = (
    <>
      {thumbnailId ? (
        <span className="doc-link-preview-thumb">
          <AttachmentImage id={thumbnailId} alt="" />
        </span>
      ) : null}
      <span className="doc-link-preview-body">
        <span className="doc-link-preview-title">{heading}</span>
        {host ? (
          <span className="doc-link-preview-domain" data-testid="doc-link-preview-domain">
            {host}
          </span>
        ) : null}
      </span>
    </>
  )
  if (!safe) {
    return (
      <div className="doc-link-preview is-inert" data-testid="doc-link-preview">
        {inner}
      </div>
    )
  }
  return (
    <a className="doc-link-preview" href={safe} target="_blank" rel="noopener noreferrer" data-testid="doc-link-preview">
      {inner}
    </a>
  )
}

/* ------------------------------------------------------------------ */
/* Vidéo                                                                */
/* ------------------------------------------------------------------ */

/** Vidéo d'une pièce jointe : chargée via l'API (jeton) puis lue par URL d'objet. */
export function RichVideo({ id, filename }: { id: string; filename?: string }) {
  const [state, setState] = useState<{ id: string; url?: string; failed?: boolean }>({ id })

  useEffect(() => {
    let cancelled = false
    attachmentObjectUrl(id).then(
      (url) => !cancelled && setState({ id, url }),
      () => !cancelled && setState({ id, failed: true }),
    )
    return () => {
      cancelled = true
    }
  }, [id])

  const current = state.id === id ? state : { id }
  if (current.failed) {
    return (
      <span className="doc-attachment-image is-error" role="note" data-testid="attachment-video-error">
        {VIDEO_UNAVAILABLE_LABEL}
        {filename ? ` — ${filename}` : ''}
      </span>
    )
  }
  if (!current.url) {
    return (
      <span
        className="doc-attachment-image is-loading"
        role="img"
        aria-busy="true"
        aria-label={filename || 'Vidéo en cours de chargement'}
        data-testid="attachment-video-loading"
      />
    )
  }
  return (
    <video
      className="doc-video"
      controls
      preload="metadata"
      src={current.url}
      aria-label={filename || 'Vidéo'}
      data-attachment-id={id}
      data-testid="attachment-video"
    />
  )
}
