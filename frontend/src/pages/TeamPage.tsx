// SPDX-License-Identifier: AGPL-3.0-or-later
import { FormEvent, useState } from 'react'
import { Link } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../lib/api'
import { apiErrorMessage } from '../lib/approvals'
import {
  addGroupMember,
  createGroup,
  deleteGroup,
  listGroups,
  removeGroupMember,
} from '../lib/groups'

export function TeamPage() {
  const qc = useQueryClient()
  const groups = useQuery({ queryKey: ['groups'], queryFn: () => listGroups(api) })
  const [name, setName] = useState('')
  const [memberByGroup, setMemberByGroup] = useState<Record<string, string>>({})
  const [error, setError] = useState<string | null>(null)

  const create = useMutation({
    mutationFn: () => createGroup(api, name.trim()),
    onSuccess: () => {
      setName('')
      setError(null)
      void qc.invalidateQueries({ queryKey: ['groups'] })
    },
    onError: (e) => setError(apiErrorMessage(e, 'Création impossible')),
  })

  const addMember = useMutation({
    mutationFn: ({ groupId, userId }: { groupId: string; userId: string }) =>
      addGroupMember(api, groupId, userId),
    onSuccess: (_d, vars) => {
      setMemberByGroup((prev) => ({ ...prev, [vars.groupId]: '' }))
      void qc.invalidateQueries({ queryKey: ['groups'] })
    },
    onError: (e) => setError(apiErrorMessage(e, 'Ajout membre impossible')),
  })

  const removeMember = useMutation({
    mutationFn: ({ groupId, userId }: { groupId: string; userId: string }) =>
      removeGroupMember(api, groupId, userId),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['groups'] }),
    onError: (e) => setError(apiErrorMessage(e, 'Retrait impossible')),
  })

  const removeGroup = useMutation({
    mutationFn: (id: string) => deleteGroup(api, id),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['groups'] }),
    onError: (e) => setError(apiErrorMessage(e, 'Suppression impossible')),
  })

  return (
    <main className="page-shell max-w-[780px]">
      <div className="breadcrumb mb-6">
        <Link to="/">Accueil</Link>
        <span className="text-[#DEDEE1]">→</span>
        <span className="font-medium text-socle-ink">Équipes</span>
      </div>

      <h1 className="serif-title">Équipes &amp; groupes</h1>
      <p className="mt-1.5 text-sm text-socle-muted">
        Groupes utilisables comme sujet d&apos;accès OpenFGA. Tout utilisateur peut créer un
        groupe ; seul le créateur gère les membres.
      </p>

      <form
        className="mt-8 flex flex-wrap gap-2"
        onSubmit={(e: FormEvent) => {
          e.preventDefault()
          if (name.trim()) create.mutate()
        }}
      >
        <input
          className="min-w-[220px] flex-1 rounded-lg border border-socle-line px-3 py-2"
          placeholder="Nom du groupe"
          value={name}
          onChange={(e) => setName(e.target.value)}
        />
        <button type="submit" className="btn-primary" disabled={create.isPending}>
          Créer
        </button>
      </form>

      {error && <p className="mt-2 text-sm text-socle-danger">{error}</p>}

      <ul className="mt-8 space-y-4">
        {groups.data?.map((g) => (
          <li key={g.id} className="rounded-xl border border-socle-line p-4">
            <div className="flex flex-wrap items-start justify-between gap-2">
              <div>
                <div className="font-semibold text-socle-ink">{g.name}</div>
                <div className="text-xs text-socle-muted">
                  {g.memberCount} membre{g.memberCount === 1 ? '' : 's'}
                </div>
              </div>
              {g.canManage && (
                <button
                  type="button"
                  className="text-xs font-semibold text-socle-danger hover:underline"
                  onClick={() => {
                    if (window.confirm(`Supprimer « ${g.name} » ?`)) removeGroup.mutate(g.id)
                  }}
                >
                  Supprimer
                </button>
              )}
            </div>

            <ul className="mt-3 space-y-1">
              {g.members.map((m) => (
                <li key={m.userId} className="flex justify-between text-sm">
                  <span>
                    {m.displayName}{' '}
                    <span className="font-mono text-xs text-socle-muted">{m.email}</span>
                  </span>
                  {g.canManage && (
                    <button
                      type="button"
                      className="text-xs text-socle-danger hover:underline"
                      onClick={() => removeMember.mutate({ groupId: g.id, userId: m.userId })}
                    >
                      Retirer
                    </button>
                  )}
                </li>
              ))}
            </ul>

            {g.canManage && (
              <form
                className="mt-3 flex gap-2"
                onSubmit={(e: FormEvent) => {
                  e.preventDefault()
                  const userId = (memberByGroup[g.id] ?? '').trim()
                  if (userId) addMember.mutate({ groupId: g.id, userId })
                }}
              >
                <input
                  className="flex-1 rounded border border-socle-line px-2 py-1.5 font-mono text-xs"
                  placeholder="UUID membre"
                  value={memberByGroup[g.id] ?? ''}
                  onChange={(e) =>
                    setMemberByGroup((prev) => ({ ...prev, [g.id]: e.target.value }))
                  }
                />
                <button type="submit" className="btn-ghost text-xs">
                  Ajouter
                </button>
              </form>
            )}
          </li>
        ))}
      </ul>
    </main>
  )
}
