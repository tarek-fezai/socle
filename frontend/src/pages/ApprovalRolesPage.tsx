// SPDX-License-Identifier: AGPL-3.0-or-later
import { FormEvent, useState } from 'react'
import { Link } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../lib/api'
import { apiErrorMessage } from '../lib/approvals'
import {
  createApprovalRoleAssignment,
  deleteApprovalRoleAssignment,
  listApprovalRoleAssignments,
  scopeLabel,
  type ApprovalRoleAssignment,
} from '../lib/approvalRoles'
import { listGlobalRoles } from '../lib/workflows'

export function ApprovalRolesPage() {
  const qc = useQueryClient()
  const roles = useQuery({
    queryKey: ['global-roles'],
    queryFn: () => listGlobalRoles(api),
  })
  const assignments = useQuery({
    queryKey: ['approval-role-assignments'],
    queryFn: () => listApprovalRoleAssignments(api),
  })

  const [error, setError] = useState<string | null>(null)
  const [roleId, setRoleId] = useState('')
  const [subjectType, setSubjectType] = useState<'user' | 'group'>('user')
  const [subjectId, setSubjectId] = useState('')
  const [scopeType, setScopeType] = useState<'all' | 'space' | 'tag' | 'doc_type'>('space')
  const [scopeRef, setScopeRef] = useState('')

  const create = useMutation({
    mutationFn: () =>
      createApprovalRoleAssignment(api, {
        roleId,
        subjectType,
        subjectId,
        scopeType,
        scopeRef: scopeType === 'all' ? null : scopeRef,
      }),
    onSuccess: () => {
      setError(null)
      setSubjectId('')
      setScopeRef('')
      void qc.invalidateQueries({ queryKey: ['approval-role-assignments'] })
    },
    onError: (err) => setError(apiErrorMessage(err, 'Attribution impossible')),
  })

  const remove = useMutation({
    mutationFn: (id: string) => deleteApprovalRoleAssignment(api, id),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['approval-role-assignments'] }),
    onError: (err) => setError(apiErrorMessage(err, 'Retrait impossible')),
  })

  function onSubmit(e: FormEvent) {
    e.preventDefault()
    if (!roleId || !subjectId) {
      setError('Rôle et sujet requis')
      return
    }
    if (scopeType !== 'all' && !scopeRef.trim()) {
      setError('Référence de portée requise')
      return
    }
    create.mutate()
  }

  return (
    <main className="page-shell max-w-[900px]">
      <div className="breadcrumb mb-6">
        <Link to="/">Accueil</Link>
        <span className="text-[#DEDEE1]">→</span>
        <Link to="/admin/workflows">Workflows</Link>
        <span className="text-[#DEDEE1]">→</span>
        <span className="font-medium text-socle-ink">Rôles d&apos;approbation</span>
      </div>

      <h1 className="serif-title">Rôles d&apos;approbation</h1>
      <p className="mt-1.5 mb-6 text-sm text-socle-muted">
        Attributions scopées (tous les espaces / un espace / un tag / un type de document). Les
        administrateurs système gèrent toutes les portées ; les propriétaires d&apos;espace
        uniquement la portée espace.
      </p>

      {error && <p className="mb-4 text-sm text-socle-danger">{error}</p>}

      <form
        onSubmit={onSubmit}
        className="mb-8 grid gap-3 rounded-xl border border-socle-line p-4 sm:grid-cols-2"
      >
        <label className="text-sm">
          <span className="mb-1 block text-socle-muted">Rôle</span>
          <select
            className="w-full rounded-lg border border-socle-line px-3 py-2"
            value={roleId}
            onChange={(e) => setRoleId(e.target.value)}
          >
            <option value="">Choisir…</option>
            {(roles.data ?? []).map((r) => (
              <option key={r.id} value={r.id}>
                {r.name}
              </option>
            ))}
          </select>
        </label>
        <label className="text-sm">
          <span className="mb-1 block text-socle-muted">Sujet</span>
          <div className="flex gap-2">
            <select
              className="rounded-lg border border-socle-line px-2 py-2"
              value={subjectType}
              onChange={(e) => setSubjectType(e.target.value as 'user' | 'group')}
            >
              <option value="user">Utilisateur</option>
              <option value="group">Groupe</option>
            </select>
            <input
              className="min-w-0 flex-1 rounded-lg border border-socle-line px-3 py-2 font-mono text-xs"
              placeholder="UUID"
              value={subjectId}
              onChange={(e) => setSubjectId(e.target.value)}
            />
          </div>
        </label>
        <label className="text-sm">
          <span className="mb-1 block text-socle-muted">Portée</span>
          <select
            className="w-full rounded-lg border border-socle-line px-3 py-2"
            value={scopeType}
            onChange={(e) => setScopeType(e.target.value as typeof scopeType)}
          >
            <option value="all">Tous les espaces</option>
            <option value="space">Un espace</option>
            <option value="tag">Un tag</option>
            <option value="doc_type">Un type de document</option>
          </select>
        </label>
        <label className="text-sm">
          <span className="mb-1 block text-socle-muted">Référence</span>
          <input
            className="w-full rounded-lg border border-socle-line px-3 py-2 font-mono text-xs"
            placeholder={scopeType === 'all' ? '—' : 'UUID espace/tag ou type'}
            disabled={scopeType === 'all'}
            value={scopeRef}
            onChange={(e) => setScopeRef(e.target.value)}
          />
        </label>
        <div className="sm:col-span-2">
          <button type="submit" className="btn-primary" disabled={create.isPending}>
            Ajouter l&apos;attribution
          </button>
        </div>
      </form>

      <div className="overflow-hidden rounded-xl border border-socle-line">
        <div className="grid grid-cols-[1.4fr_1fr_1.4fr_auto] gap-2 border-b border-socle-line bg-[#FAFAFB] px-4 py-2 text-[11px] font-semibold uppercase tracking-wide text-socle-muted">
          <div>Rôle</div>
          <div>Sujet</div>
          <div>Portée</div>
          <div />
        </div>
        {(assignments.data ?? []).length === 0 && (
          <p className="px-4 py-6 text-sm text-socle-muted">Aucune attribution.</p>
        )}
        {(assignments.data ?? []).map((a: ApprovalRoleAssignment) => (
          <div
            key={a.id}
            className="grid grid-cols-[1.4fr_1fr_1.4fr_auto] items-center gap-2 border-b border-[#F5F5F7] px-4 py-3 text-sm last:border-0"
          >
            <div className="font-medium text-socle-ink">{a.roleName}</div>
            <div className="font-mono text-xs text-socle-muted">
              {a.subjectType}:{a.subjectId.slice(0, 8)}…
            </div>
            <div>{scopeLabel(a.scopeType, a.scopeRef)}</div>
            <button
              type="button"
              className="text-sm font-semibold text-socle-danger"
              onClick={() => remove.mutate(a.id)}
            >
              Retirer
            </button>
          </div>
        ))}
      </div>
    </main>
  )
}
