// SPDX-License-Identifier: AGPL-3.0-or-later
import { FormEvent, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { DiffViewer } from '../components/DiffViewer'
import { api } from '../lib/api'
import {
  apiErrorMessage,
  approvalConflictCode,
  decideApproval,
  fetchApprovalDiff,
  formatSlaCountdown,
  listMyApprovals,
  type ApprovalItem,
} from '../lib/approvals'
import { listComments } from '../lib/comments'

export function ApprovalsPage() {
  const queryClient = useQueryClient()
  const [selectedId, setSelectedId] = useState<string | null>(null)
  /** Étape affichée au moment de la sélection — envoyée comme expectedStepOrder. */
  const [expectedStepOrder, setExpectedStepOrder] = useState<number | null>(null)
  const [comment, setComment] = useState('')
  const [actionError, setActionError] = useState<string | null>(null)
  const [stepAdvanced, setStepAdvanced] = useState(false)
  const [nowTick, setNowTick] = useState(() => Date.now())

  const mine = useQuery({
    queryKey: ['approvals', 'mine'],
    queryFn: () => listMyApprovals(api),
    refetchInterval: 5_000,
  })

  useEffect(() => {
    const id = window.setInterval(() => setNowTick(Date.now()), 30_000)
    return () => window.clearInterval(id)
  }, [])

  const selected = mine.data?.find((a) => a.approvalRequestId === selectedId) ?? null

  const openComments = useQuery({
    queryKey: ['document-comments', selected?.documentId, 'ouvert', ''],
    queryFn: () => listComments(api, selected!.documentId, { status: 'ouvert' }),
    enabled: Boolean(selected?.documentId),
  })

  const diff = useQuery({
    queryKey: [
      'approval-diff',
      selected?.documentId,
      selected?.baselineVersionNo,
      selected?.submittedVersionNo,
    ],
    queryFn: () =>
      fetchApprovalDiff(
        api,
        selected!.documentId,
        selected!.baselineVersionNo!,
        selected!.submittedVersionNo!,
      ),
    enabled:
      Boolean(selected?.documentId) &&
      selected?.baselineVersionNo != null &&
      selected?.submittedVersionNo != null,
  })

  const decide = useMutation({
    mutationFn: async (decision: 'approuve' | 'rejete') => {
      if (!selected || expectedStepOrder == null) {
        throw new Error('Aucune demande sélectionnée')
      }
      return decideApproval(
        api,
        selected.documentId,
        selected.approvalRequestId,
        decision,
        expectedStepOrder,
        comment.trim() || null,
      )
    },
    onSuccess: () => {
      setActionError(null)
      setStepAdvanced(false)
      setComment('')
      setSelectedId(null)
      setExpectedStepOrder(null)
      void queryClient.invalidateQueries({ queryKey: ['approvals', 'mine'] })
      void queryClient.invalidateQueries({ queryKey: ['documents'] })
    },
    onError: (err) => {
      const code = approvalConflictCode(err)
      setActionError(apiErrorMessage(err, 'Échec de la décision'))
      setStepAdvanced(code === 'step_advanced')
      if (code !== 'step_advanced') {
        void queryClient.invalidateQueries({ queryKey: ['approvals', 'mine'] })
      }
    },
  })

  function onDecide(e: FormEvent, decision: 'approuve' | 'rejete') {
    e.preventDefault()
    decide.mutate(decision)
  }

  function reloadApprovals() {
    setActionError(null)
    setStepAdvanced(false)
    setSelectedId(null)
    setExpectedStepOrder(null)
    setComment('')
    void queryClient.invalidateQueries({ queryKey: ['approvals', 'mine'] })
  }

  return (
    <main className="page-shell-wide">
      <div className="breadcrumb mb-6">
        <Link to="/">Accueil</Link>
        <span className="text-[#DEDEE1]">→</span>
        <span className="font-medium text-socle-ink">Approbation requise</span>
      </div>

      <h1 className="serif-title">Mes approbations</h1>
      <p className="mt-2 text-sm text-socle-muted">
        Demandes en attente à votre étape — SLA visible, décision sans mise à jour optimiste.
      </p>

      {mine.isLoading && <p className="mt-8 text-socle-muted">Chargement…</p>}
      {mine.isError && (
        <p className="mt-8 text-socle-danger">Impossible de charger vos approbations.</p>
      )}
      {mine.isSuccess && mine.data.length === 0 && (
        <p className="mt-8 text-socle-muted">Aucune demande en attente pour vous.</p>
      )}

      <div className="mt-8 flex flex-col gap-8 lg:flex-row lg:items-start">
        <div className="min-w-0 flex-1">
          <ul className="space-y-1">
            {mine.data?.map((item) => (
              <li key={item.approvalRequestId}>
                <button
                  type="button"
                  onClick={() => {
                    setSelectedId(item.approvalRequestId)
                    setExpectedStepOrder(item.currentStepOrder)
                    setActionError(null)
                    setStepAdvanced(false)
                  }}
                  className={`w-full rounded-xl border px-4 py-3 text-left transition ${
                    selectedId === item.approvalRequestId
                      ? 'border-[#C7C6F5] bg-socle-mist'
                      : 'border-socle-line bg-white hover:bg-socle-soft'
                  }`}
                >
                  <div className="flex flex-wrap items-baseline justify-between gap-2">
                    <span className="font-medium text-socle-ink">{item.documentTitle}</span>
                    <SlaBadge deadline={item.slaDeadlineAt} nowMs={nowTick} />
                  </div>
                  <p className="mt-1 text-xs text-socle-muted">
                    Étape {item.currentStepOrder}
                    {item.submittedVersionNo != null ? ` · v${item.submittedVersionNo}` : ''}
                  </p>
                </button>
              </li>
            ))}
          </ul>

          {selected && (
            <ApprovalDetail
              item={selected}
              comment={comment}
              onComment={setComment}
              onDecide={onDecide}
              deciding={decide.isPending}
              actionError={actionError}
              stepAdvanced={stepAdvanced}
              onReload={reloadApprovals}
              diffLoading={diff.isLoading}
              diffError={diff.isError}
              changes={diff.data?.changes ?? null}
              hasBaseline={selected.baselineVersionNo != null}
            />
          )}
        </div>

        {selected && (
          <aside className="w-full shrink-0 space-y-5 rounded-xl border border-socle-line bg-white p-5 lg:sticky lg:top-[76px] lg:w-[280px]">
            <div>
              <div className="section-label mb-2">Échéance</div>
              <SlaBadge deadline={selected.slaDeadlineAt} nowMs={nowTick} />
              {selected.slaDeadlineAt && (
                <p className="mt-1 text-xs text-socle-faint">
                  {new Date(selected.slaDeadlineAt).toLocaleString('fr-FR')}
                </p>
              )}
            </div>
            <div>
              <div className="section-label mb-2">Étape courante</div>
              <p className="text-sm font-medium text-socle-ink">N{selected.currentStepOrder}</p>
            </div>
            <div>
              <div className="section-label mb-2">Modifications</div>
              {selected.baselineVersionNo != null && selected.submittedVersionNo != null ? (
                <p className="text-sm text-socle-slate">
                  Comparer v{selected.baselineVersionNo} → v{selected.submittedVersionNo}
                </p>
              ) : (
                <p className="text-sm text-socle-muted">Pas de version de référence</p>
              )}
            </div>
            <div>
              <div className="section-label mb-2">Soumis le</div>
              <p className="text-sm text-socle-slate">
                {new Date(selected.createdAt).toLocaleString('fr-FR')}
              </p>
            </div>
            <Link
              to={`/docs/${selected.documentId}`}
              className="inline-block text-sm font-semibold text-socle-accent hover:underline"
            >
              Ouvrir le document →
            </Link>
            {(openComments.data?.openThreadCount ?? 0) > 0 && (
              <Link
                to={`/docs/${selected.documentId}/view?comments=open`}
                className="inline-block text-sm font-semibold text-socle-accent hover:underline"
                data-testid="approval-open-comments-link"
              >
                Voir les commentaires ouverts ({openComments.data!.openThreadCount})
              </Link>
            )}
          </aside>
        )}
      </div>
    </main>
  )
}

function SlaBadge({ deadline, nowMs }: { deadline: string | null; nowMs: number }) {
  const label = formatSlaCountdown(deadline, nowMs)
  const overdue = deadline != null && new Date(deadline).getTime() < nowMs
  return (
    <span
      className={`text-xs font-semibold ${overdue ? 'text-socle-danger' : 'text-socle-warn'}`}
      title={deadline ? new Date(deadline).toLocaleString('fr-FR') : undefined}
    >
      {label}
    </span>
  )
}

function ApprovalDetail({
  item,
  comment,
  onComment,
  onDecide,
  deciding,
  actionError,
  stepAdvanced,
  onReload,
  diffLoading,
  diffError,
  changes,
  hasBaseline,
}: {
  item: ApprovalItem
  comment: string
  onComment: (v: string) => void
  onDecide: (e: FormEvent, decision: 'approuve' | 'rejete') => void
  deciding: boolean
  actionError: string | null
  stepAdvanced: boolean
  onReload: () => void
  diffLoading: boolean
  diffError: boolean
  changes: Array<{ path: string; op: string; before: unknown; after: unknown }> | null
  hasBaseline: boolean
}) {
  const versionLabel =
    item.submittedVersionNo != null ? `v${item.submittedVersionNo}` : 'cette révision'

  return (
    <section className="mt-8 rounded-xl border border-socle-line bg-white p-6">
      <div className="status-badge-warn mb-4">
        <span className="h-1.5 w-1.5 rounded-full bg-socle-warn" aria-hidden />
        EN ATTENTE DE VOTRE DÉCISION
      </div>

      <h2 className="font-display text-[28px] font-normal text-socle-ink">{item.documentTitle}</h2>
      <p className="mt-2 text-sm leading-relaxed text-socle-slate">
        Approbation de la révision <strong className="text-socle-ink">{versionLabel}</strong>
        {item.submittedVersionNo != null ? ' soumise pour validation.' : '.'}
      </p>

      <div className="mt-6">
        <div className="section-label mb-3">Circuit d&apos;approbation</div>
        <p className="text-sm text-socle-slate">
          Étape {item.currentStepOrder} · workflow{' '}
          <span className="font-mono text-xs text-socle-muted">{item.temporalWorkflowId}</span>
        </p>
      </div>

      <div className="mt-6">
        {!hasBaseline && (
          <p className="text-sm text-socle-muted">
            Pas de version de référence — document soumis en v{item.submittedVersionNo ?? '?'}.
          </p>
        )}
        {hasBaseline && (
          <DiffViewer
            title="Comparer les versions"
            changes={changes}
            loading={diffLoading}
            error={diffError}
          />
        )}
      </div>

      <form className="mt-8 space-y-3">
        <label className="block">
          <span className="section-label">Justification de la décision</span>
          <textarea
            value={comment}
            onChange={(e) => onComment(e.target.value)}
            rows={2}
            placeholder="Facultatif pour une approbation, requis pour un refus…"
            className="field-input mt-2"
          />
        </label>
        {actionError && <p className="text-sm text-socle-danger">{actionError}</p>}
        {stepAdvanced && (
          <button type="button" onClick={onReload} className="btn-ghost">
            Recharger
          </button>
        )}
        <div className="flex flex-wrap gap-2 pt-1">
          <button
            type="button"
            disabled={deciding || stepAdvanced}
            onClick={(e) => onDecide(e, 'approuve')}
            title={
              item.submittedVersionNo != null
                ? `Approuver la révision v${item.submittedVersionNo}`
                : 'Approuver'
            }
            className="btn-approve flex-1 sm:flex-none"
          >
            {deciding ? 'Envoi…' : 'Approuver'}
          </button>
          <button
            type="button"
            disabled={deciding || stepAdvanced}
            onClick={(e) => onDecide(e, 'rejete')}
            className="btn-reject"
          >
            Refuser
          </button>
        </div>
        {item.submittedVersionNo != null && !deciding && (
          <p className="text-xs text-socle-muted">
            Approuver la révision v{item.submittedVersionNo}
          </p>
        )}
      </form>
    </section>
  )
}
