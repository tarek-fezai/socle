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
import { formatInvitationSentFr, formatRelativeFr } from '../lib/relativeTime'
import { grantPlatformRole, revokePlatformRole } from '../lib/platformRoles'
import { SocleRole } from '../lib/auth'
import { getLicence } from '../lib/licenceAdmin'
import {
  createInvitation,
  invitationsKey,
  listInvitations,
  resendInvitation,
  revokeInvitation,
  type InvitationView,
} from '../lib/invitations'
import { useBrandingInstanceName } from '../lib/publicBranding'

type Tab = 'membres' | 'equipes' | 'invitations'

function teamRoleLabel(role?: string) {
  if (role === 'proprietaire') return 'Propriétaire'
  if (role === 'approbateur') return 'Approbateur'
  if (role === 'lecteur') return 'Lecteur'
  return 'Contributeur'
}

export function TeamAdminPage() {
  const [tab, setTab] = useState<Tab>('membres')
  const [q, setQ] = useState('')
  const [inviteOpen, setInviteOpen] = useState(false)
  const invites = useQuery({
    queryKey: invitationsKey(),
    queryFn: () => listInvitations(api),
  })
  const pendingCount = invites.data?.length ?? 0
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
            <div
              data-mock-id="team-title"
              className="font-display"
              style={{
                fontFamily: '"Instrument Serif", Georgia, serif',
                fontSize: 34,
                fontWeight: 400,
                lineHeight: 'normal',
                color: '#0E0E10',
                margin: '0 0 6px',
                display: 'inline-block',
                whiteSpace: 'nowrap',
              }}
            >
              Membres & équipes
            </div>
            <Lead />
          </div>
          <button
            type="button"
            data-mock-id="team-invite-cta"
            onClick={() => setInviteOpen(true)}
            className="shrink-0 rounded-[7px] bg-socle-accent px-4 py-2 text-[13px] font-semibold text-white"
            style={{ padding: '8px 16px', borderRadius: 7, fontSize: 13, fontWeight: 600 }}
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
            <a
              href="#invitations"
              role="tab"
              aria-selected={tab === 'invitations'}
              data-mock-id="team-tab-invitations"
              onClick={(e) => {
                e.preventDefault()
                setTab('invitations')
              }}
              style={{
                padding: '7px 16px',
                fontSize: 12.5,
                fontWeight: tab === 'invitations' ? 600 : 400,
                color: '#6B6B72',
                background: tab === 'invitations' ? '#FFFFFF' : 'transparent',
                boxSizing: 'content-box',
                lineHeight: 'normal',
                textDecoration: 'none',
                display: 'inline-block',
              }}
            >
              Invitations{' '}{pendingCount > 0 ? (
                <span
                  style={{
                    background: '#FBF3E4',
                    color: '#B7791F',
                    fontSize: 10,
                    fontWeight: 600,
                    borderRadius: 8,
                    padding: '1px 6px',
                    marginLeft: 4,
                  }}
                >
                  {pendingCount}
                </span>
              ) : null}
            </a>
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
        {tab === 'invitations' && <InvitationsTab />}
      </div>
      {inviteOpen && <InviteMemberModal onClose={() => setInviteOpen(false)} />}
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
    <a
      href={`#${id}`}
      role="tab"
      aria-selected={on}
      data-mock-id={mockId}
      onClick={(e) => {
        e.preventDefault()
        onClick(id)
      }}
      style={{
        padding: '7px 16px',
        fontSize: 12.5,
        fontWeight: 400,
        color: '#6B6B72',
        background: on ? '#FFFFFF' : 'transparent',
        borderRight: '1px solid #ECECEE',
        boxSizing: 'content-box',
        lineHeight: 'normal',
        textDecoration: 'none',
        display: 'inline-block',
      }}
    >
      {children}
    </a>
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
          className="flex bg-[#FAFAFB] text-[11px] font-semibold uppercase tracking-[0.05em] text-[#75757C]"
          data-mock-id="team-members-head"
          style={{ padding: '10px 16px', borderBottom: '1px solid #ECECEE', boxSizing: 'content-box' }}
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

function emailInitials(email: string) {
  const local = email.split('@')[0] ?? email
  const parts = local.split(/[.\-_]+/).filter(Boolean)
  if (parts.length >= 2) return (parts[0][0] + parts[1][0]).toUpperCase()
  return local.slice(0, 2).toUpperCase()
}

function InvitationsTab() {
  const qc = useQueryClient()
  const [error, setError] = useState<string | null>(null)
  const page = useQuery({ queryKey: invitationsKey(), queryFn: () => listInvitations(api) })
  const n = page.data?.length ?? 0

  const resend = useMutation({
    mutationFn: (id: string) => resendInvitation(api, id),
    onSuccess: () => {
      setError(null)
      void qc.invalidateQueries({ queryKey: invitationsKey() })
    },
    onError: (e) => setError(apiErrorMessage(e, 'Renvoi impossible')),
  })
  const revoke = useMutation({
    mutationFn: (id: string) => revokeInvitation(api, id),
    onSuccess: () => {
      setError(null)
      void qc.invalidateQueries({ queryKey: invitationsKey() })
      void qc.invalidateQueries({ queryKey: ['admin-users-lead'] })
    },
    onError: (e) => setError(apiErrorMessage(e, 'Annulation impossible')),
  })

  return (
    <div>
      {error && (
        <p role="alert" className="mb-3 text-sm text-socle-danger">
          {error}
        </p>
      )}
      <div
        data-mock-id="invitations-table"
        className="overflow-hidden rounded-[10px] border border-socle-line"
      >
        <div
          className="flex bg-[#FAFAFB] text-[11px] font-semibold uppercase tracking-[0.05em] text-[#75757C]"
          data-mock-id="invitations-head"
          style={{ padding: '10px 16px', borderBottom: '1px solid #ECECEE', boxSizing: 'content-box' }}
        >
          <div className="flex-[2.2]">Invité</div>
          <div className="flex-[1.4]">Équipe</div>
          <div className="flex-[1.2]">Rôle</div>
          <div className="flex-[1.2]">Envoyée par</div>
          <div className="flex-[1.1]">Envoyée</div>
          <div className="w-[190px] shrink-0" />
        </div>
        {(page.data ?? []).map((row: InvitationView, i) => (
          <div
            key={row.id}
            className="flex items-center"
            data-mock-id={i === 0 ? 'invitations-row' : undefined}
            style={{
              height: 51,
              padding: '11px 16px',
              borderBottom: '1px solid #F5F5F7',
              boxSizing: 'border-box',
            }}
          >
            <div className="flex flex-[2.2] items-center gap-2.5">
              <div
                className="flex h-[26px] w-[26px] shrink-0 items-center justify-center rounded-full text-[10px] text-[#75757C]"
                style={{ border: '1.5px dashed #DEDEE1' }}
              >
                {emailInitials(row.email)}
              </div>
              <div className="font-mono text-[13px] text-[#0E0E10]">{row.email}</div>
            </div>
            <div className="flex-[1.4] text-[12.5px] text-[#43434A]">{row.teamName ?? '—'}</div>
            <div className="flex-[1.2]">
              {row.teamRole ? (
                <span
                  className="text-[12px] font-medium text-[#43434A]"
                  style={{ border: '1px solid #ECECEE', borderRadius: 6, padding: '3px 9px' }}
                >
                  {teamRoleLabel(row.teamRole)}
                </span>
              ) : (
                '—'
              )}
            </div>
            <div className="flex-[1.2] text-[12.5px] text-[#43434A]">{row.invitedByName ?? '—'}</div>
            <div className="flex-[1.1] text-[12px] text-[#9B9BA1]">
              {formatInvitationSentFr(row.lastSentAt)}
            </div>
            <div className="flex w-[190px] shrink-0 justify-end gap-1.5">
              <span
                className="resend"
                role="button"
                tabIndex={0}
                style={{
                  fontSize: 12,
                  fontWeight: 600,
                  color: '#3730E0',
                  padding: '6px 10px',
                  borderRadius: 6,
                }}
                onClick={() => {
                  if (!resend.isPending) resend.mutate(row.id)
                }}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' && !resend.isPending) resend.mutate(row.id)
                }}
              >
                Renvoyer
              </span>
              <span
                className="cancel"
                role="button"
                tabIndex={0}
                style={{
                  fontSize: 12,
                  fontWeight: 600,
                  color: '#B54708',
                  padding: '6px 10px',
                  borderRadius: 6,
                }}
                onClick={() => {
                  if (!revoke.isPending) revoke.mutate(row.id)
                }}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' && !revoke.isPending) revoke.mutate(row.id)
                }}
              >
                Annuler
              </span>
            </div>
          </div>
        ))}
      </div>
      <p className="mt-3.5 text-[12.5px] text-[#9B9BA1]">
        {n} invitation{n > 1 ? 's' : ''} sur {n} · les invitations expirent après 7 jours sans
        réponse
      </p>
    </div>
  )
}

function InviteMemberModal({ onClose }: { onClose: () => void }) {
  const qc = useQueryClient()
  const org = useBrandingInstanceName() || 'Socle'
  const [email, setEmail] = useState('')
  const [teamId, setTeamId] = useState<string | null>(null)
  const [teamRole, setTeamRole] = useState('contributeur')
  const [globalRole, setGlobalRole] = useState<'CONTRIBUTEUR' | 'ADMINISTRATEUR_SYSTEME'>(
    'CONTRIBUTEUR',
  )
  const [confirmAdmin, setConfirmAdmin] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const groups = useQuery({ queryKey: ['groups'], queryFn: () => listGroups(api) })
  const licence = useQuery({ queryKey: ['admin', 'licence'], queryFn: () => getLicence(api) })

  const licenceWarn =
    licence.data &&
    (licence.data.evaluationMode ||
      (licence.data.effectiveMaxUsers > 0 &&
        licence.data.activeUsers >= licence.data.effectiveMaxUsers) ||
      licence.data.effectiveMaxUsers === 0)

  const send = useMutation({
    mutationFn: () =>
      createInvitation(api, {
        email: email.trim(),
        teamId: teamId || undefined,
        teamRole: teamId ? teamRole : undefined,
        globalRole,
        confirmAdmin: globalRole === 'ADMINISTRATEUR_SYSTEME' ? confirmAdmin : undefined,
      }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: invitationsKey() })
      void qc.invalidateQueries({ queryKey: ['admin-users-lead'] })
      onClose()
    },
    onError: (e) => setError(apiErrorMessage(e, 'Envoi impossible')),
  })

  return (
    <div
      data-mock-id="invite-modal-backdrop"
      style={{
        position: 'fixed',
        inset: 0,
        background: 'rgba(14,14,16,0.45)',
        zIndex: 40,
      }}
    >
      <div
        data-mock-id="invite-modal"
        role="dialog"
        aria-labelledby="invite-modal-title"
        style={{
          position: 'absolute',
          top: 120,
          left: '50%',
          transform: 'translateX(-50%)',
          width: 520,
          background: '#FFFFFF',
          borderRadius: 16,
          boxShadow: '0 24px 60px rgba(14,14,16,0.28)',
          padding: '30px 30px 26px',
          boxSizing: 'border-box',
          height: 'fit-content',
          WebkitFontSmoothing: 'auto',
          MozOsxFontSmoothing: 'auto',
          textRendering: 'auto',
          fontSynthesis: 'weight style',
        }}
      >
        <div className="mb-1.5 flex items-start justify-between">
          <div
            id="invite-modal-title"
            data-mock-id="invite-title"
            className="serif"
            style={{
              fontFamily: "'Instrument Serif', serif",
              fontWeight: 400,
              fontSize: 24,
              margin: 0,
              color: '#0E0E10',
              lineHeight: 'normal',
              WebkitFontSmoothing: 'auto',
              MozOsxFontSmoothing: 'auto',
              textRendering: 'auto',
            }}
          >
            Inviter un membre
          </div>
          <button
            type="button"
            aria-label="Fermer"
            data-mock-id="invite-close"
            onClick={onClose}
            style={{
              width: 28,
              height: 28,
              borderRadius: 7,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
            }}
          >
            <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="#6B6B72" strokeWidth="2" strokeLinecap="round">
              <line x1="18" y1="6" x2="6" y2="18" />
              <line x1="6" y1="6" x2="18" y2="18" />
            </svg>
          </button>
        </div>
        <p data-mock-id="invite-lead" style={{ fontSize: 13, color: '#9B9BA1', margin: '0 0 20px' }}>
          Rejoint l&apos;organisation {org} avec accès aux espaces de son équipe.
        </p>

        {licenceWarn && (
          <p role="status" className="mb-4 text-[12.5px] text-[#B7791F]">
            {licence.data?.evaluationMode
              ? 'Licence en évaluation : la création de compte pourra être refusée à l’acceptation.'
              : 'Sièges épuisés : l’invitation partira, mais l’acceptation sera refusée tant qu’un siège n’est pas libre.'}
          </p>
        )}

        <div
          data-mock-id="invite-email-label"
          style={{
            fontSize: 12,
            fontWeight: 600,
            color: '#9B9BA1',
            textTransform: 'uppercase',
            letterSpacing: '0.05em',
            marginBottom: 8,
          }}
        >
          Adresse e-mail
        </div>
        <div
          data-mock-id="invite-email-field"
          style={{ border: '1px solid #ECECEE', borderRadius: 9, padding: '10px 12px', marginBottom: 18 }}
        >
          <input
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            type="email"
            placeholder="prenom.nom@example.com"
            className="w-full border-0 bg-transparent text-[13.5px] outline-none placeholder:text-[#C2C2C6]"
          />
        </div>

        <div
          data-mock-id="invite-team-label"
          style={{
            fontSize: 12,
            fontWeight: 600,
            color: '#9B9BA1',
            textTransform: 'uppercase',
            letterSpacing: '0.05em',
            marginBottom: 8,
          }}
        >
          Équipe
        </div>
        <div data-mock-id="invite-teams" className="mb-[18px] flex flex-wrap gap-2">
          {(groups.data ?? []).map((g) => {
            const on = teamId === g.id
            return (
              <button
                key={g.id}
                type="button"
                onClick={() => setTeamId(on ? null : g.id)}
                style={{
                  fontSize: 12.5,
                  fontWeight: on ? 600 : 400,
                  color: on ? '#3730E0' : '#43434A',
                  background: on ? '#F0EFFC' : 'transparent',
                  border: on ? '1.5px solid #3730E0' : '1px solid #ECECEE',
                  borderRadius: 7,
                  padding: '6px 12px',
                }}
              >
                {g.name}
              </button>
            )
          })}
        </div>

        <div
          data-mock-id="invite-team-role-label"
          style={{
            fontSize: 12,
            fontWeight: 600,
            color: '#9B9BA1',
            textTransform: 'uppercase',
            letterSpacing: '0.05em',
            marginBottom: 8,
          }}
        >
          Rôle dans l&apos;équipe
        </div>
        <div data-mock-id="invite-team-roles" className="mb-[18px] flex flex-wrap gap-2">
          {(['proprietaire', 'approbateur', 'contributeur', 'lecteur'] as const).map((r) => {
            const on = teamRole === r
            return (
              <button
                key={r}
                type="button"
                onClick={() => setTeamRole(r)}
                style={{
                  fontSize: 12.5,
                  fontWeight: on ? 600 : 400,
                  color: on ? '#3730E0' : '#43434A',
                  background: on ? '#F0EFFC' : 'transparent',
                  border: on ? '1.5px solid #3730E0' : '1px solid #ECECEE',
                  borderRadius: 7,
                  padding: '6px 12px',
                }}
              >
                {teamRoleLabel(r)}
              </button>
            )
          })}
        </div>

        <div
          data-mock-id="invite-global-label"
          style={{
            fontSize: 12,
            fontWeight: 600,
            color: '#9B9BA1',
            textTransform: 'uppercase',
            letterSpacing: '0.05em',
            marginBottom: 8,
          }}
        >
          Rôle global
        </div>
        <div data-mock-id="invite-global-roles" className="mb-[22px] flex flex-col gap-2">
          <button
            type="button"
            onClick={() => {
              setGlobalRole('CONTRIBUTEUR')
              setConfirmAdmin(false)
            }}
            style={{
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'space-between',
              border: globalRole === 'CONTRIBUTEUR' ? '1.5px solid #3730E0' : '1px solid #ECECEE',
              background: globalRole === 'CONTRIBUTEUR' ? '#FAFAFE' : 'transparent',
              borderRadius: 9,
              padding: '10px 13px',
              textAlign: 'left',
            }}
          >
            <div>
              <div className="text-[13.5px] font-semibold text-[#0E0E10]">Membre</div>
              <div className="text-[11.5px] text-[#9B9BA1]">
                Accès selon les rôles attribués dans ses espaces
              </div>
            </div>
            {globalRole === 'CONTRIBUTEUR' && (
              <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="#3730E0" strokeWidth="2.5" strokeLinecap="round">
                <polyline points="20 6 9 17 4 12" />
              </svg>
            )}
          </button>
          <button
            type="button"
            onClick={() => setGlobalRole('ADMINISTRATEUR_SYSTEME')}
            style={{
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'space-between',
              border:
                globalRole === 'ADMINISTRATEUR_SYSTEME' ? '1.5px solid #3730E0' : '1px solid #ECECEE',
              background: globalRole === 'ADMINISTRATEUR_SYSTEME' ? '#FAFAFE' : 'transparent',
              borderRadius: 9,
              padding: '10px 13px',
              textAlign: 'left',
            }}
          >
            <div>
              <div className="text-[13.5px] font-semibold text-[#0E0E10]">Administrateur</div>
              <div className="text-[11.5px] text-[#9B9BA1]">
                Accès complet à l&apos;administration de l&apos;organisation
              </div>
            </div>
            {globalRole === 'ADMINISTRATEUR_SYSTEME' && (
              <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="#3730E0" strokeWidth="2.5" strokeLinecap="round">
                <polyline points="20 6 9 17 4 12" />
              </svg>
            )}
          </button>
        </div>

        {globalRole === 'ADMINISTRATEUR_SYSTEME' && (
          <label className="mb-4 flex items-start gap-2 text-[12.5px] text-[#43434A]">
            <input
              type="checkbox"
              checked={confirmAdmin}
              onChange={(e) => setConfirmAdmin(e.target.checked)}
            />
            Je confirme l&apos;invitation en administrateur système.
          </label>
        )}

        {error && (
          <p role="alert" className="mb-3 text-sm text-socle-danger">
            {error}
          </p>
        )}

        <div className="flex items-center justify-between">
          <span data-mock-id="invite-ready" style={{ fontSize: 12, color: '#9B9BA1' }}>
            1 invitation prête à envoyer
          </span>
          <div className="flex gap-2">
            <a
              href="#invite-cancel"
              data-mock-id="invite-cancel"
              className="ghost"
              onClick={(e) => {
                e.preventDefault()
                onClose()
              }}
              style={{
                padding: '9px 15px',
                borderRadius: 7,
                border: '1px solid #ECECEE',
                color: '#43434A',
                fontSize: 13,
                fontWeight: 500,
                textDecoration: 'none',
              }}
            >
              Annuler
            </a>
            <a
              href="#invite-submit"
              data-mock-id="invite-submit"
              className="cta"
              aria-disabled={
                !email.trim() ||
                send.isPending ||
                (globalRole === 'ADMINISTRATEUR_SYSTEME' && !confirmAdmin)
              }
              onClick={(e) => {
                e.preventDefault()
                if (
                  !email.trim() ||
                  send.isPending ||
                  (globalRole === 'ADMINISTRATEUR_SYSTEME' && !confirmAdmin)
                ) {
                  return
                }
                send.mutate()
              }}
              style={{
                padding: '9px 18px',
                borderRadius: 7,
                background: '#3730E0',
                color: '#FFFFFF',
                fontSize: 13,
                fontWeight: 600,
                textDecoration: 'none',
              }}
            >
              Envoyer l&apos;invitation
            </a>
          </div>
        </div>
      </div>
    </div>
  )
}
