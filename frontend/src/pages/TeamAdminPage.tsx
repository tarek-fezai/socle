// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { FormEvent, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { AdminShell } from '../components/admin/AdminShell'
import { PeoplePicker, type PickerValue } from '../components/PeoplePicker'
import { api } from '../lib/api'
import { apiErrorMessage } from '../lib/apiError'
import { disableUser, enableUser, listAdminUsers, type AdminUserRow } from '../lib/directory'
import {
  addGroupMember,
  createGroup,
  listGroups,
  removeGroupMember,
  updateGroupMemberRole,
  type Group,
} from '../lib/groups'
import { formatRelativeFr } from '../lib/relativeTime'
import { grantPlatformRole, revokePlatformRole } from '../lib/platformRoles'
import { SocleRole } from '../lib/auth'

type Tab = 'membres' | 'equipes'

function teamRoleLabel(role?: string) {
  if (role === 'proprietaire') return 'Propriétaire'
  if (role === 'approbateur') return 'Approbateur'
  if (role === 'lecteur') return 'Lecteur'
  return 'Contributeur'
}

export function TeamAdminPage() {
  const [tab, setTab] = useState<Tab>('membres')
  const [q, setQ] = useState('')
  return (
    <AdminShell
      active="team"
      innerWide
      mainClassName="admin-main--team"
      breadcrumb={[
        { label: 'Administration', to: '/admin' },
        { label: 'Membres & équipes' },
      ]}
    >
      <div data-mock-id="team-page">
        <div className="mb-1.5 flex items-start justify-between">
          <div>
            <h1
              className="mb-1.5 font-display text-[34px] font-normal text-socle-ink"
              data-mock-id="team-title"
            >
              Membres &amp; équipes
            </h1>
            <Lead />
          </div>
          <button
            type="button"
            disabled
            title="Bientôt"
            data-mock-id="team-invite-cta"
            className="shrink-0 rounded-[7px] bg-socle-accent px-4 py-2 text-[13px] font-semibold text-white opacity-50"
          >
            + Inviter un membre
          </button>
        </div>

        <div className="mb-4 mt-[22px] flex items-center justify-between">
          <div
            className="flex overflow-hidden rounded-lg border border-socle-line"
            data-mock-id="team-tabs"
            role="tablist"
          >
            <TabBtn id="membres" current={tab} onClick={setTab} mockId="team-tab-membres">
              Membres
            </TabBtn>
            <TabBtn id="equipes" current={tab} onClick={setTab} mockId="team-tab-equipes">
              Équipes
            </TabBtn>
            <button
              type="button"
              role="tab"
              disabled
              title="Bientôt"
              data-mock-id="team-tab-invitations"
              className="border-l border-socle-line text-[12.5px] text-[#6B6B72] opacity-60"
              style={{ padding: '7px 16px' }}
            >
              Invitations{' '}
              <span className="ml-1 text-[10px] font-semibold text-[#B7791F]">Bientôt</span>
            </button>
          </div>
          {tab === 'membres' && (
            <label className="flex items-center gap-2 rounded-lg border border-socle-line px-3 py-1.5">
              <span className="sr-only">Rechercher un membre</span>
              <input
                value={q}
                onChange={(e) => setQ(e.target.value)}
                placeholder="Rechercher un membre…"
                className="w-52 border-0 bg-transparent text-[12.5px] outline-none"
              />
            </label>
          )}
        </div>

        {tab === 'membres' && <MembersTab q={q} />}
        {tab === 'equipes' && <TeamsTab />}
      </div>
    </AdminShell>
  )
}

function Lead() {
  const users = useQuery({
    queryKey: ['admin-users-lead'],
    queryFn: () => listAdminUsers(api, { limit: 1 }),
  })
  const n = users.data?.activeCount ?? 0
  const t = users.data?.teamCount ?? 0
  return (
    <p className="m-0 text-[14px] text-[#9B9BA1]" data-mock-id="team-lead">
      {n} membre{n > 1 ? 's' : ''} actif{n > 1 ? 's' : ''} · {t} équipe{t > 1 ? 's' : ''}
    </p>
  )
}

function TabBtn({
  id,
  current,
  onClick,
  children,
  mockId,
}: {
  id: Tab
  current: Tab
  onClick: (t: Tab) => void
  children: string
  mockId: string
}) {
  const on = current === id
  return (
    <button
      type="button"
      role="tab"
      aria-selected={on}
      data-mock-id={mockId}
      onClick={() => onClick(id)}
      className={`text-[12.5px] ${on ? 'bg-white font-semibold text-socle-ink' : 'text-[#6B6B72]'} ${
        id !== 'membres' ? 'border-l border-socle-line' : ''
      }`}
      style={{ padding: '7px 16px' }}
    >
      {children}
    </button>
  )
}

function MembersTab({ q }: { q: string }) {
  const qc = useQueryClient()
  const [pending, setPending] = useState<AdminUserRow | null>(null)
  const [roleError, setRoleError] = useState<string | null>(null)
  const page = useQuery({
    queryKey: ['admin-users', q],
    queryFn: () => listAdminUsers(api, { q: q.trim().length >= 2 ? q : undefined, limit: 200 }),
  })

  const toggle = useMutation({
    mutationFn: (row: AdminUserRow) =>
      row.status === 'disabled' ? enableUser(api, row.id) : disableUser(api, row.id),
    onSuccess: () => {
      setPending(null)
      void qc.invalidateQueries({ queryKey: ['admin-users'] })
      void qc.invalidateQueries({ queryKey: ['admin-users-lead'] })
    },
    onError: (e) => setRoleError(apiErrorMessage(e, 'Action impossible')),
  })

  return (
    <div>
      {roleError && (
        <p role="alert" className="mb-3 text-sm text-socle-danger">
          {roleError}
        </p>
      )}
      <div
        data-mock-id="team-members-table"
        className="overflow-hidden rounded-[10px] border border-socle-line"
      >
        <div
          className="flex bg-[#FAFAFB] px-4 py-2.5 text-[11px] font-semibold uppercase tracking-[0.05em] text-[#75757C]"
          data-mock-id="team-members-head"
        >
          <div className="flex-[2.1]">Membre</div>
          <div className="flex-[1.1]">Rôle global</div>
          <div className="flex-[1.3]">Équipe</div>
          <div className="flex-[1.2]">Rôle dans l&apos;équipe</div>
          <div className="flex-1">Statut</div>
          <div className="flex-[1.1]">Dernière activité</div>
          <div className="w-7 shrink-0" />
        </div>
        {(page.data?.users ?? []).map((row, i) => {
          const team = row.teams[0]
          const isAdmin = row.platformRoles.includes(SocleRole.ADMINISTRATEUR_SYSTEME)
          return (
            <div
              key={row.id}
              className="flex items-center border-t border-[#F5F5F7] px-4 py-2.5"
              data-mock-id={i === 0 ? 'team-member-row' : undefined}
            >
              <div className="flex flex-[2.1] items-center gap-2.5">
                <div className="flex h-[26px] w-[26px] items-center justify-center rounded-full bg-[#EEEDFD] text-[10px] font-semibold text-socle-accent">
                  {row.initials}
                </div>
                <div>
                  <div className="text-[13.5px] font-medium">{row.displayName}</div>
                  <div className="font-mono text-[11px] text-[#75757C]">{row.email}</div>
                </div>
              </div>
              <div className="flex-[1.1]">
                <select
                  aria-label={`Rôle global de ${row.displayName}`}
                  className="rounded-md border border-socle-line px-2 py-1 text-[12px]"
                  value={isAdmin ? SocleRole.ADMINISTRATEUR_SYSTEME : SocleRole.CONTRIBUTEUR}
                  onChange={(e) => {
                    const next = e.target.value
                    const run =
                      next === SocleRole.ADMINISTRATEUR_SYSTEME
                        ? grantPlatformRole(api, row.id, SocleRole.ADMINISTRATEUR_SYSTEME)
                        : revokePlatformRole(api, row.id, SocleRole.ADMINISTRATEUR_SYSTEME)
                    run
                      .then(() => qc.invalidateQueries({ queryKey: ['admin-users'] }))
                      .catch((err) => setRoleError(apiErrorMessage(err, 'Changement de rôle impossible')))
                  }}
                >
                  <option value={SocleRole.CONTRIBUTEUR}>Membre</option>
                  <option value={SocleRole.ADMINISTRATEUR_SYSTEME}>Administrateur</option>
                </select>
              </div>
              <div className="flex-[1.3] text-[12.5px] text-[#43434A]">{team?.name ?? '—'}</div>
              <div className="flex-[1.2] text-[12px]">{team ? teamRoleLabel(team.role) : '—'}</div>
              <div
                className={`flex flex-1 items-center gap-1.5 text-[12px] font-medium ${
                  row.status === 'disabled' ? 'text-[#9B9BA1]' : 'text-[#1E8E5A]'
                }`}
              >
                <span
                  className={`h-[5px] w-[5px] rounded-full ${
                    row.status === 'disabled' ? 'bg-[#9B9BA1]' : 'bg-[#1E8E5A]'
                  }`}
                />
                {row.status === 'disabled' ? 'Désactivé' : 'Actif'}
              </div>
              <div className="flex-[1.1] text-[12px] text-[#9B9BA1]">
                {row.lastActivityAt ? formatRelativeFr(row.lastActivityAt) : '—'}
              </div>
              <div className="w-7 shrink-0">
                {pending?.id === row.id ? (
                  <div className="flex flex-col gap-1 text-[11px]">
                    <button type="button" onClick={() => toggle.mutate(row)}>
                      Confirmer
                    </button>
                    <button type="button" onClick={() => setPending(null)}>
                      Annuler
                    </button>
                  </div>
                ) : (
                  <button
                    type="button"
                    className="text-[12px] underline"
                    onClick={() => setPending(row)}
                  >
                    {row.status === 'disabled' ? 'Activer' : 'Désactiver'}
                  </button>
                )}
              </div>
            </div>
          )
        })}
      </div>
    </div>
  )
}

function TeamsTab() {
  const qc = useQueryClient()
  const [name, setName] = useState('')
  const [pick, setPick] = useState<PickerValue | null>(null)
  const [active, setActive] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const groups = useQuery({ queryKey: ['groups'], queryFn: () => listGroups(api) })

  const create = useMutation({
    mutationFn: () => createGroup(api, name.trim()),
    onSuccess: () => {
      setName('')
      setError(null)
      void qc.invalidateQueries({ queryKey: ['groups'] })
      void qc.invalidateQueries({ queryKey: ['admin-users-lead'] })
    },
    onError: (e) => setError(apiErrorMessage(e, 'Création impossible')),
  })
  const add = useMutation({
    mutationFn: () => addGroupMember(api, active!, pick!.id, 'contributeur'),
    onSuccess: () => {
      setPick(null)
      setError(null)
      void qc.invalidateQueries({ queryKey: ['groups'] })
    },
    onError: (e) => setError(apiErrorMessage(e, 'Ajout impossible')),
  })

  function onCreate(e: FormEvent) {
    e.preventDefault()
    if (name.trim()) create.mutate()
  }

  return (
    <div data-mock-id="team-teams-list" className="space-y-6">
      {error && (
        <p role="alert" className="text-sm text-socle-danger">
          {error}
        </p>
      )}
      <form onSubmit={onCreate} className="flex gap-2">
        <input
          value={name}
          onChange={(e) => setName(e.target.value)}
          placeholder="Nouvelle équipe"
          className="field-input flex-1"
        />
        <button type="submit" className="btn-primary" disabled={!name.trim() || create.isPending}>
          Créer
        </button>
      </form>
      {(groups.data ?? []).map((g: Group) => (
        <div key={g.id} className="rounded-[10px] border border-socle-line p-4">
          <button type="button" className="text-[15px] font-semibold" onClick={() => setActive(g.id)}>
            {g.name}
          </button>
          <p className="text-[12px] text-[#9B9BA1]">
            {g.memberCount} membre{g.memberCount > 1 ? 's' : ''}
          </p>
          {active === g.id && (
            <div className="mt-3 space-y-2">
              {g.canManage && (
                <div className="flex items-end gap-2">
                  <PeoplePicker value={pick} onChange={setPick} usersOnly />
                  <button
                    type="button"
                    className="btn-primary"
                    disabled={!pick || pick.kind !== 'user' || add.isPending}
                    onClick={() => add.mutate()}
                  >
                    Ajouter
                  </button>
                </div>
              )}
              <ul>
                {g.members.map((m) => (
                  <li key={m.userId} className="flex items-center justify-between py-1 text-[13px]">
                    <span>{m.displayName}</span>
                    <span className="flex items-center gap-2">
                      {g.canManage ? (
                        <select
                          aria-label={`Rôle de ${m.displayName}`}
                          value={m.role ?? 'contributeur'}
                          onChange={(e) =>
                            updateGroupMemberRole(api, g.id, m.userId, e.target.value)
                              .then(() => qc.invalidateQueries({ queryKey: ['groups'] }))
                              .catch((err) => setError(apiErrorMessage(err, 'Changement de rôle impossible')))
                          }
                        >
                          <option value="proprietaire">Propriétaire</option>
                          <option value="approbateur">Approbateur</option>
                          <option value="contributeur">Contributeur</option>
                          <option value="lecteur">Lecteur</option>
                        </select>
                      ) : (
                        teamRoleLabel(m.role)
                      )}
                      {g.canManage && (
                        <button
                          type="button"
                          className="text-socle-danger"
                          onClick={() =>
                            removeGroupMember(api, g.id, m.userId)
                              .then(() => qc.invalidateQueries({ queryKey: ['groups'] }))
                              .catch((err) => setError(apiErrorMessage(err, 'Retrait impossible')))
                          }
                        >
                          Retirer
                        </button>
                      )}
                    </span>
                  </li>
                ))}
              </ul>
            </div>
          )}
        </div>
      ))}
    </div>
  )
}
