// SPDX-License-Identifier: AGPL-3.0-or-later
import { FormEvent, useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { DocumentReliabilityStatus } from '../components/ReliabilityDisplay'
import { StaleBadge } from '../components/StaleBadge'
import { api } from '../lib/api'
import { apiErrorMessage } from '../lib/approvals'
import { emptyDocBody, getDocument, updateDocument } from '../lib/documents'
import {
  acquireEditLock,
  editLockBannerText,
  heartbeatEditLock,
  releaseEditLock,
} from '../lib/editLock'
import { fetchApplicableWorkflow, matchLevelLabel } from '../lib/workflows'
import { DocumentEditor } from './DocumentEditor'

type ApprovalCurrent = {
  approvalRequestId: string
  documentId: string
  documentTitle: string
  temporalWorkflowId: string
  status: string
  currentStepOrder: number
  slaDeadlineAt: string | null
  submittedVersionNo: number | null
  baselineVersionNo: number | null
  requestedBy: string
  createdAt: string
}

export function DocumentEditPage() {
  const { id = '' } = useParams()
  const queryClient = useQueryClient()
  const [title, setTitle] = useState('')
  const [docType, setDocType] = useState('')
  const [body, setBody] = useState<Record<string, unknown>>(emptyDocBody)
  const [savedAt, setSavedAt] = useState<string | null>(null)
  const [approvalMsg, setApprovalMsg] = useState<string | null>(null)
  const [approvalError, setApprovalError] = useState<string | null>(null)

  const doc = useQuery({
    queryKey: ['document', id],
    queryFn: () => getDocument(api, id),
    enabled: Boolean(id),
  })

  const currentApproval = useQuery({
    queryKey: ['approval', id],
    queryFn: async () => {
      const res = await api.get<ApprovalCurrent>(`/api/v1/documents/${id}/approvals/current`, {
        validateStatus: (s) => s === 200 || s === 204,
      })
      return res.status === 204 ? null : res.data
    },
    enabled: Boolean(id),
    refetchInterval: (q) => (q.state.data ? 3000 : false),
  })

  const applicable = useQuery({
    queryKey: ['applicable-workflow', id, doc.data?.spaceId, doc.data?.docType],
    queryFn: () => fetchApplicableWorkflow(api, id),
    enabled: Boolean(id) && Boolean(doc.data) && !currentApproval.data,
  })

  useEffect(() => {
    if (!doc.data) return
    setTitle(doc.data.title)
    setDocType(doc.data.docType ?? '')
    setBody(doc.data.body ?? emptyDocBody)
  }, [doc.data])

  const editLock = useQuery({
    queryKey: ['edit-lock', id],
    queryFn: () => acquireEditLock(api, id),
    enabled: Boolean(id) && Boolean(doc.data),
    refetchInterval: (q) => {
      const secs = q.state.data?.heartbeatSeconds ?? 15
      return secs * 1000
    },
  })

  useEffect(() => {
    if (!id || !doc.data) return
    const secs = editLock.data?.heartbeatSeconds ?? 15
    const timer = window.setInterval(() => {
      void heartbeatEditLock(api, id)
        .then((lock) => queryClient.setQueryData(['edit-lock', id], lock))
        .catch(() => undefined)
    }, secs * 1000)
    return () => {
      window.clearInterval(timer)
      void releaseEditLock(api, id).catch(() => undefined)
      void queryClient.removeQueries({ queryKey: ['edit-lock', id] })
    }
  }, [id, doc.data, editLock.data?.heartbeatSeconds, queryClient])

  const save = useMutation({
    mutationFn: () =>
      updateDocument(
        api,
        id,
        title,
        body,
        docType.trim() || null,
        doc.data?.currentVersionNo ?? null,
      ),
    onSuccess: (updated) => {
      setSavedAt(updated.updatedAt)
      void queryClient.invalidateQueries({ queryKey: ['documents'] })
      void queryClient.setQueryData(['document', id], updated)
      void queryClient.invalidateQueries({ queryKey: ['applicable-workflow', id] })
      void releaseEditLock(api, id).catch(() => undefined)
    },
  })

  const submitApproval = useMutation({
    mutationFn: async () => {
      const { data } = await api.post<{
        approvalRequestId: string
        temporalWorkflowId: string
        status: string
      }>(`/api/v1/documents/${id}/approvals`)
      return data
    },
    onSuccess: (data) => {
      setApprovalError(null)
      setApprovalMsg(`En cours d'approbation · workflow ${data.temporalWorkflowId}`)
      void queryClient.invalidateQueries({ queryKey: ['document', id] })
      void queryClient.invalidateQueries({ queryKey: ['documents'] })
      void queryClient.invalidateQueries({ queryKey: ['approval', id] })
      void queryClient.invalidateQueries({ queryKey: ['approvals', 'mine'] })
    },
    onError: (err) => {
      setApprovalMsg(null)
      setApprovalError(apiErrorMessage(err, 'Échec de la soumission pour approbation'))
    },
  })

  function onSubmit(e: FormEvent) {
    e.preventDefault()
    save.mutate()
  }

  if (doc.isLoading) {
    return <main className="page-shell text-socle-muted">Chargement…</main>
  }

  if (doc.isError || !doc.data) {
    return (
      <main className="page-shell">
        <Link to="/docs" className="text-sm font-semibold text-socle-accent">
          ← Documents
        </Link>
        <p className="mt-4 text-socle-danger">Document introuvable ou accès refusé.</p>
      </main>
    )
  }

  const pending = currentApproval.data
  const status = doc.data.status
  const canSubmit =
    !pending && (status === 'brouillon' || status === 'en_revue') && status !== 'archive'
  const lockBanner = editLock.data ? editLockBannerText(editLock.data) : null

  return (
    <main className="page-shell">
      <div className="mb-6 flex items-center justify-between gap-4">
        <div className="flex flex-wrap items-center gap-4">
          <Link to="/docs" className="text-sm font-semibold text-socle-accent">
            ← Documents
          </Link>
          <Link
            to={`/docs/${id}/history`}
            className="text-sm font-semibold text-socle-accent underline-offset-4 hover:underline"
          >
            Voir l&apos;historique
          </Link>
          <Link
            to={`/docs/${id}/view`}
            className="text-sm font-semibold text-socle-accent underline-offset-4 hover:underline"
          >
            Vue composite
          </Link>
          <Link
            to={`/docs/${id}/export`}
            className="text-sm font-semibold text-socle-accent underline-offset-4 hover:underline"
          >
            Exporter
          </Link>
          <Link
            to={`/documents/${id}/access`}
            className="text-sm text-socle-muted hover:text-socle-accent"
          >
            Accès
          </Link>
        </div>
        <p className="text-xs text-socle-muted">
          {save.isSuccess && savedAt
            ? `Enregistré ${new Date(savedAt).toLocaleTimeString('fr-FR')}`
            : ''}
        </p>
      </div>

      {lockBanner ? (
        <div
          className="mb-4 rounded-lg border border-[#E8D9A8] bg-[#FBF3E4] px-4 py-2.5 text-sm text-socle-warn"
          data-testid="edit-lock-banner"
        >
          {lockBanner}
          <span className="ml-1 font-normal text-socle-muted">
            — l&apos;édition reste possible ; un conflit à la sauvegarde renverra une erreur 409.
          </span>
        </div>
      ) : null}

      {save.isError ? (
        <p className="mb-4 text-sm text-socle-danger" data-testid="save-conflict-error">
          {apiErrorMessage(save.error, 'Échec de l’enregistrement')}
        </p>
      ) : null}

      <DocumentReliabilityStatus
        score={doc.data.reliabilityScore}
        computedAt={doc.data.reliabilityComputedAt}
        status={doc.data.status}
        createdAt={doc.data.createdAt}
      />
      <div className="mb-4">
        <StaleBadge stale={doc.data.stale} contentModifiedAt={doc.data.contentModifiedAt} />
      </div>

      <form onSubmit={onSubmit} className="space-y-4">
        <input
          value={title}
          onChange={(e) => setTitle(e.target.value)}
          className="w-full border-0 border-b border-socle-line bg-transparent font-display text-3xl font-normal text-socle-ink outline-none focus:border-socle-accent"
          placeholder="Titre du document"
        />

        <label className="block max-w-sm text-sm">
          <span className="text-socle-muted">Type de document (scope workflow)</span>
          <input
            value={docType}
            onChange={(e) => setDocType(e.target.value)}
            disabled={Boolean(pending)}
            className="mt-1 w-full rounded-lg border border-socle-line bg-white px-3 py-2 text-sm"
            placeholder="ex. politique, procédure"
          />
        </label>

        <DocumentEditor content={body} onChange={setBody} />

        {canSubmit && applicable.data && (
          <div className="rounded-xl border border-socle-line bg-[#FAFAFB] px-4 py-3 text-sm text-socle-ink">
            <p className="font-medium">
              Workflow applicable : {applicable.data.name}
              <span className="ml-2 font-normal text-socle-muted">
                ({applicable.data.stepCount} étape
                {applicable.data.stepCount > 1 ? 's' : ''} ·{' '}
                {matchLevelLabel(applicable.data.matchLevel)})
              </span>
            </p>
          </div>
        )}

        <div className="flex flex-wrap justify-end gap-2">
          {canSubmit && (
            <button
              type="button"
              disabled={submitApproval.isPending}
              onClick={() => submitApproval.mutate()}
              className="btn-ghost"
            >
              {submitApproval.isPending ? 'Soumission…' : 'Soumettre pour approbation'}
            </button>
          )}
          <button
            type="submit"
            disabled={save.isPending || !title.trim() || Boolean(pending)}
            className="btn-primary"
          >
            {save.isPending ? 'Enregistrement…' : 'Enregistrer'}
          </button>
        </div>
        {pending && (
          <div className="rounded-xl border border-[#C7C6F5] bg-socle-mist/60 px-4 py-3 text-sm text-socle-ink">
            <p className="font-medium">En cours d&apos;approbation</p>
            <p className="mt-1 font-mono text-xs text-socle-slate">
              {pending.temporalWorkflowId}
            </p>
            <p className="mt-2 text-xs text-socle-slate">
              Étape {pending.currentStepOrder}
              {pending.slaDeadlineAt
                ? ` · SLA ${new Date(pending.slaDeadlineAt).toLocaleString('fr-FR')}`
                : ''}
            </p>
            <Link
              to="/approvals"
              className="mt-2 inline-block text-xs font-semibold text-socle-accent underline-offset-4 hover:underline"
            >
              Voir mes approbations →
            </Link>
          </div>
        )}
        {approvalMsg && !pending && (
          <p className="text-right text-xs text-socle-slate">{approvalMsg}</p>
        )}
        {approvalError && <p className="text-right text-xs text-socle-danger">{approvalError}</p>}
      </form>
    </main>
  )
}
