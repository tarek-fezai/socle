// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { FormEvent, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../lib/api'
import { apiErrorMessage } from '../lib/approvals'
import {
  createWorkflowDefinition,
  deleteWorkflowDefinition,
  listGlobalRoles,
  listWorkflowDefinitions,
  updateWorkflowDefinition,
  type WorkflowDefinition,
  type WorkflowUpsert,
} from '../lib/workflows'

type DraftStep = {
  stepOrder: number
  slaHours: string
  approverRoleId: string
  escalatesToStepOrder: string
}

const emptyStep = (order: number): DraftStep => ({
  stepOrder: order,
  slaHours: '24',
  approverRoleId: '',
  escalatesToStepOrder: '',
})

export function WorkflowsPage() {
  const qc = useQueryClient()
  const list = useQuery({
    queryKey: ['approval-workflows'],
    queryFn: () => listWorkflowDefinitions(api),
  })
  const roles = useQuery({
    queryKey: ['global-roles'],
    queryFn: () => listGlobalRoles(api),
  })

  const [editing, setEditing] = useState<WorkflowDefinition | null>(null)
  const [creating, setCreating] = useState(false)
  const [formError, setFormError] = useState<string | null>(null)

  const remove = useMutation({
    mutationFn: (id: string) => deleteWorkflowDefinition(api, id),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['approval-workflows'] }),
    onError: (err) => setFormError(apiErrorMessage(err, 'Suppression impossible')),
  })

  return (
    <main className="page-shell max-w-[900px]">
      <div className="breadcrumb mb-6">
        <Link to="/">Accueil</Link>
        <span className="text-[#DEDEE1]">→</span>
        <span className="font-medium text-socle-ink">Workflows d&apos;approbation</span>
      </div>

      <div className="mb-6 flex flex-wrap items-start justify-between gap-4">
        <div>
          <h1 className="serif-title">Workflows d&apos;approbation</h1>
          <p className="mt-1.5 text-sm text-socle-muted">
            Chaînes à N étapes (SLA, rôle, escalade) — sélection automatique par espace / type
            à la soumission.{' '}
            <Link to="/admin/approval-roles" className="font-semibold text-socle-accent">
              Gérer les rôles d&apos;approbation scopés
            </Link>
          </p>
        </div>
        <button
          type="button"
          className="btn-primary"
          onClick={() => {
            setEditing(null)
            setCreating(true)
            setFormError(null)
          }}
        >
          Nouveau workflow
        </button>
      </div>

      {formError && !creating && !editing && (
        <p className="mb-4 text-sm text-socle-danger">{formError}</p>
      )}

      {(creating || editing) && (
        <WorkflowForm
          initial={editing}
          roleOptions={roles.data ?? []}
          onCancel={() => {
            setCreating(false)
            setEditing(null)
            setFormError(null)
          }}
          onSaved={() => {
            setCreating(false)
            setEditing(null)
            setFormError(null)
            void qc.invalidateQueries({ queryKey: ['approval-workflows'] })
          }}
          onError={(msg) => setFormError(msg)}
        />
      )}

      {list.isLoading && <p className="text-sm text-socle-muted">Chargement…</p>}
      {list.isError && (
        <p className="text-sm text-socle-danger">
          {apiErrorMessage(list.error, 'Impossible de charger les workflows')}
        </p>
      )}

      {list.data && list.data.length === 0 && !creating && (
        <p className="text-sm text-socle-muted">Aucune définition — le seed « Approbation simple »
          sera créé automatiquement à la première soumission.</p>
      )}

      {list.data && list.data.length > 0 && (
        <div className="overflow-hidden rounded-xl border border-socle-line">
          <div className="grid grid-cols-[2fr_2fr_1.4fr_0.7fr_0.7fr] gap-2 border-b border-socle-line bg-[#FAFAFB] px-4 py-2.5 text-[11.5px] font-semibold uppercase tracking-wide text-socle-muted">
            <div>Workflow</div>
            <div>Étapes</div>
            <div>Portée</div>
            <div>Statut</div>
            <div className="text-right">Actions</div>
          </div>
          {list.data.map((wf) => (
            <div
              key={wf.id}
              className="grid grid-cols-[2fr_2fr_1.4fr_0.7fr_0.7fr] items-center gap-2 border-b border-[#F5F5F7] px-4 py-3 last:border-0"
            >
              <div>
                <div className="text-sm font-semibold text-socle-ink">{wf.name}</div>
                <div className="text-xs text-socle-muted">
                  {wf.steps.length} étape{wf.steps.length > 1 ? 's' : ''}
                  {wf.inProgressCount > 0 ? ` · ${wf.inProgressCount} en cours` : ''}
                </div>
              </div>
              <div className="flex flex-wrap items-center gap-1">
                {wf.steps.map((s, i) => (
                  <span key={s.id ?? s.stepOrder} className="flex items-center gap-1">
                    {i > 0 && <span className="text-socle-muted">→</span>}
                    <span className="rounded bg-socle-mist px-2 py-0.5 text-[10.5px] font-semibold text-socle-ink">
                      {s.approverRoleName ?? `Étape ${s.stepOrder}`}
                    </span>
                  </span>
                ))}
              </div>
              <div className="text-xs text-socle-slate">
                {wf.scopeSpaceId ? `Espace ${wf.scopeSpaceId.slice(0, 8)}…` : 'Tous espaces'}
                <br />
                {wf.scopeDocType ? `Type : ${wf.scopeDocType}` : 'Tous types'}
              </div>
              <div>
                <span
                  className={
                    wf.status === 'active'
                      ? 'text-xs font-semibold text-[#1E8E5A]'
                      : 'text-xs font-semibold text-socle-muted'
                  }
                >
                  {wf.status === 'active' ? 'Actif' : 'Brouillon'}
                </span>
              </div>
              <div className="flex justify-end gap-2">
                <button
                  type="button"
                  className="text-xs font-semibold text-socle-accent hover:underline"
                  disabled={wf.inProgressCount > 0}
                  title={
                    wf.inProgressCount > 0
                      ? 'Instances en cours — édition bloquée'
                      : 'Modifier'
                  }
                  onClick={() => {
                    setCreating(false)
                    setEditing(wf)
                    setFormError(null)
                  }}
                >
                  Modifier
                </button>
                <button
                  type="button"
                  className="text-xs font-semibold text-socle-danger hover:underline"
                  disabled={wf.inProgressCount > 0 || wf.name === 'Approbation simple'}
                  onClick={() => {
                    if (window.confirm(`Supprimer « ${wf.name} » ?`)) {
                      remove.mutate(wf.id)
                    }
                  }}
                >
                  Suppr.
                </button>
              </div>
            </div>
          ))}
        </div>
      )}
    </main>
  )
}

function WorkflowForm({
  initial,
  roleOptions,
  onCancel,
  onSaved,
  onError,
}: {
  initial: WorkflowDefinition | null
  roleOptions: Array<{ id: string; name: string }>
  onCancel: () => void
  onSaved: () => void
  onError: (msg: string) => void
}) {
  const [name, setName] = useState(initial?.name ?? '')
  const [status, setStatus] = useState<'active' | 'draft'>(initial?.status ?? 'active')
  const [scopeSpaceId, setScopeSpaceId] = useState(initial?.scopeSpaceId ?? '')
  const [scopeDocType, setScopeDocType] = useState(initial?.scopeDocType ?? '')
  const [steps, setSteps] = useState<DraftStep[]>(
    initial?.steps?.length
      ? initial.steps.map((s) => ({
          stepOrder: s.stepOrder,
          slaHours: s.slaHours == null ? '' : String(s.slaHours),
          approverRoleId: s.approverRoleId ?? '',
          escalatesToStepOrder:
            s.escalatesToStepOrder == null ? '' : String(s.escalatesToStepOrder),
        }))
      : [emptyStep(1)],
  )

  useEffect(() => {
    if (roleOptions.length && steps.some((s) => !s.approverRoleId)) {
      setSteps((prev) =>
        prev.map((s) => (s.approverRoleId ? s : { ...s, approverRoleId: roleOptions[0].id })),
      )
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- only seed default role once options arrive
  }, [roleOptions])

  const save = useMutation({
    mutationFn: (body: WorkflowUpsert) =>
      initial
        ? updateWorkflowDefinition(api, initial.id, body)
        : createWorkflowDefinition(api, body),
    onSuccess: () => onSaved(),
    onError: (err) => onError(apiErrorMessage(err, 'Enregistrement impossible')),
  })

  function onSubmit(e: FormEvent) {
    e.preventDefault()
    const body: WorkflowUpsert = {
      name: name.trim(),
      scopeSpaceId: scopeSpaceId.trim() || null,
      scopeDocType: scopeDocType.trim() || null,
      status,
      steps: steps.map((s, i) => ({
        stepOrder: i + 1,
        slaHours: s.slaHours.trim() === '' ? null : Number(s.slaHours),
        approverRoleId: s.approverRoleId || null,
        escalatesToStepOrder:
          s.escalatesToStepOrder.trim() === '' ? null : Number(s.escalatesToStepOrder),
      })),
    }
    save.mutate(body)
  }

  return (
    <form
      onSubmit={onSubmit}
      className="mb-8 space-y-4 rounded-xl border border-socle-line bg-[#FAFAFB] p-5"
    >
      <h2 className="text-base font-semibold text-socle-ink">
        {initial ? `Modifier — ${initial.name}` : 'Nouveau workflow'}
      </h2>

      <div className="grid gap-3 sm:grid-cols-2">
        <label className="block text-sm">
          <span className="text-socle-muted">Nom</span>
          <input
            className="mt-1 w-full rounded-lg border border-socle-line bg-white px-3 py-2"
            value={name}
            onChange={(e) => setName(e.target.value)}
            required
          />
        </label>
        <label className="block text-sm">
          <span className="text-socle-muted">Statut</span>
          <select
            className="mt-1 w-full rounded-lg border border-socle-line bg-white px-3 py-2"
            value={status}
            onChange={(e) => setStatus(e.target.value as 'active' | 'draft')}
          >
            <option value="active">Actif</option>
            <option value="draft">Brouillon</option>
          </select>
        </label>
        <label className="block text-sm">
          <span className="text-socle-muted">Espace (UUID, vide = tous)</span>
          <input
            className="mt-1 w-full rounded-lg border border-socle-line bg-white px-3 py-2 font-mono text-xs"
            value={scopeSpaceId}
            placeholder="UUID espace (vide = tous)"
            onChange={(e) => setScopeSpaceId(e.target.value)}
          />
        </label>
        <label className="block text-sm">
          <span className="text-socle-muted">Type de document (vide = tous)</span>
          <input
            className="mt-1 w-full rounded-lg border border-socle-line bg-white px-3 py-2"
            value={scopeDocType}
            placeholder="ex. politique"
            onChange={(e) => setScopeDocType(e.target.value)}
          />
        </label>
      </div>

      <div className="space-y-3">
        <div className="flex items-center justify-between">
          <h3 className="text-sm font-semibold text-socle-ink">Étapes</h3>
          <button
            type="button"
            className="text-xs font-semibold text-socle-accent hover:underline"
            onClick={() => setSteps((prev) => [...prev, emptyStep(prev.length + 1)])}
          >
            + Étape
          </button>
        </div>
        {steps.map((step, idx) => (
          <div
            key={idx}
            className="grid gap-2 rounded-lg border border-socle-line bg-white p-3 sm:grid-cols-[auto_1fr_1fr_1fr_auto]"
          >
            <div className="flex items-center text-xs font-semibold text-socle-muted">
              #{idx + 1}
            </div>
            <label className="text-xs">
              SLA (h)
              <input
                className="mt-1 w-full rounded border border-socle-line px-2 py-1.5"
                value={step.slaHours}
                placeholder="24"
                onChange={(e) =>
                  setSteps((prev) =>
                    prev.map((s, i) => (i === idx ? { ...s, slaHours: e.target.value } : s)),
                  )
                }
              />
            </label>
            <label className="text-xs">
              Rôle approbateur
              <select
                className="mt-1 w-full rounded border border-socle-line px-2 py-1.5"
                value={step.approverRoleId}
                onChange={(e) =>
                  setSteps((prev) =>
                    prev.map((s, i) =>
                      i === idx ? { ...s, approverRoleId: e.target.value } : s,
                    ),
                  )
                }
              >
                <option value="">—</option>
                {roleOptions.map((r) => (
                  <option key={r.id} value={r.id}>
                    {r.name}
                  </option>
                ))}
              </select>
            </label>
            <label className="text-xs">
              Escalade → étape
              <input
                className="mt-1 w-full rounded border border-socle-line px-2 py-1.5"
                value={step.escalatesToStepOrder}
                placeholder={idx < steps.length - 1 ? String(idx + 2) : '—'}
                onChange={(e) =>
                  setSteps((prev) =>
                    prev.map((s, i) =>
                      i === idx ? { ...s, escalatesToStepOrder: e.target.value } : s,
                    ),
                  )
                }
              />
            </label>
            <button
              type="button"
              className="self-end text-xs text-socle-danger hover:underline disabled:opacity-40"
              disabled={steps.length <= 1}
              onClick={() => setSteps((prev) => prev.filter((_, i) => i !== idx))}
            >
              Retirer
            </button>
          </div>
        ))}
      </div>

      <div className="flex justify-end gap-2">
        <button type="button" className="btn-ghost" onClick={onCancel}>
          Annuler
        </button>
        <button type="submit" className="btn-primary" disabled={save.isPending || !name.trim()}>
          {save.isPending ? 'Enregistrement…' : 'Enregistrer'}
        </button>
      </div>
    </form>
  )
}
