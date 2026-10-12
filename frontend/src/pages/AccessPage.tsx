// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { FormEvent, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { PeoplePicker, type PickerValue } from '../components/PeoplePicker'
import { api } from '../lib/api'
import {
  accessErrorMessage,
  grantAccess,
  listAccess,
  revokeAccess,
  type AccessEntry,
} from '../lib/access'
import { formatRelativeFr } from '../lib/relativeTime'
import { softDeleteDocument } from '../lib/trash'
import {
  getDocument,
  updateDocumentVisibility,
  type DocumentVisibility,
} from '../lib/documents'
import { getSpace } from '../lib/spaces'

type ResourceKind = 'space' | 'folder' | 'document'

const VISIBILITY_OPTIONS: Array<{
  value: DocumentVisibility
  label: string
  hint: (org: string, spaceName: string) => string
}> = [
  {
    value: 'organisation',
    label: 'Tous les utilisateurs',
    hint: (org: string) =>
      `Toute personne authentifiée de ${org} peut consulter ce document.`,
  },
  {
    value: 'space',
    label: "Membres de l'espace",
    hint: (_org: string, spaceName: string) =>
      `Restreint aux membres de l'espace ${spaceName}.`,
  },
  {
    value: 'restricted',
    label: 'Personnes et équipes listées',
    hint: () => 'Accès limité aux personnes et équipes listées ci-dessous.',
  },
]

function relationLabel(relation: string, group: boolean) {
  if (relation === 'owner') return 'Propriétaire'
  if (relation === 'editor') return 'Éditeur'
  if (relation === 'viewer') return group ? 'Lecteurs' : 'Lecteur'
  return relation
}

function AccessEntryRow({
  entry,
  onRevoke,
  revoking,
}: {
  entry: AccessEntry
  onRevoke: (entry: AccessEntry) => void
  revoking: boolean
}) {
  const isGroup = entry.subjectType === 'group'
  const name = entry.displayName || (isGroup ? 'Équipe' : 'Personne')
  const initials = entry.initials || (isGroup ? '⬡' : '?')
  const meta = isGroup
    ? `${entry.memberCount ?? 0} membre${(entry.memberCount ?? 0) > 1 ? 's' : ''}${
        entry.inheritedFrom ? ' · hérité de l\'espace' : ''
      }`
    : entry.lastActivityAt
      ? `Actif ${formatRelativeFr(entry.lastActivityAt).toLowerCase()}`
      : '—'
  return (
    <li className="row group flex items-center px-4 py-3" style={{ borderBottom: '1px solid #F5F5F7' }}>
      <div className="flex min-w-0 flex-1 items-center gap-2.5">
        <div
          className="flex h-[26px] w-[26px] shrink-0 items-center justify-center text-[10px] font-semibold"
          style={{
            borderRadius: isGroup ? 8 : '50%',
            background: isGroup ? '#F1EFEA' : '#0E0E10',
            color: isGroup ? '#6B6862' : '#FFFFFF',
          }}
        >
          {initials}
        </div>
        <div className="min-w-0">
          <div className="truncate text-[13.5px] font-medium text-socle-ink">{name}</div>
          <div className="text-[11.5px] text-[#9B9BA1]">{meta}</div>
        </div>
      </div>
      <span className="shrink-0 rounded-[7px] border border-socle-line px-3 py-1 text-[12.5px] font-medium text-[#43434A]">
        {relationLabel(entry.relation, isGroup)}
      </span>
      {entry.revocable ? (
        <button
          type="button"
          onClick={() => onRevoke(entry)}
          disabled={revoking}
          className="ml-3 shrink-0 text-sm font-semibold text-socle-danger underline-offset-4 opacity-0 hover:underline group-hover:opacity-100 group-focus-within:opacity-100 disabled:opacity-50"
        >
          Révoquer
        </button>
      ) : null}
    </li>
  )
}

export function AccessPage({
  objectType: objectTypeProp,
  objectId: objectIdProp,
}: {
  objectType?: ResourceKind
  objectId?: string
} = {}) {
  const params = useParams<{ spaceId?: string; folderId?: string; documentId?: string }>()
  const objectType: ResourceKind =
    objectTypeProp ??
    (params.documentId ? 'document' : params.folderId ? 'folder' : 'space')
  const objectId = objectIdProp ?? params.documentId ?? params.folderId ?? params.spaceId ?? ''

  const queryClient = useQueryClient()
  const queryKey = ['access', objectType, objectId] as const

  const [invite, setInvite] = useState<PickerValue | null>(null)
  const [relation, setRelation] = useState('editor')
  const [inviteOpen, setInviteOpen] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [confirmTrash, setConfirmTrash] = useState(false)

  const perms = useQuery({
    queryKey,
    queryFn: () => listAccess(api, objectType, objectId),
    enabled: Boolean(objectId),
    retry: false,
  })

  const grant = useMutation({
    mutationFn: () => {
      if (!invite) throw new Error('invite')
      return grantAccess(api, objectType, objectId, {
        relation,
        subjectType: invite.kind,
        subjectId: invite.id,
      })
    },
    onSuccess: async () => {
      setError(null)
      setInvite(null)
      setInviteOpen(false)
      await queryClient.invalidateQueries({ queryKey })
    },
    onError: (e: unknown) => {
      setError(accessErrorMessage(e))
    },
  })

  const revoke = useMutation({
    mutationFn: (entry: AccessEntry) => {
      if (!entry.subjectId || (entry.subjectType !== 'user' && entry.subjectType !== 'group')) {
        return Promise.reject(new Error('Sujet non reconnu'))
      }
      return revokeAccess(api, objectType, objectId, {
        relation: entry.relation,
        subjectType: entry.subjectType,
        subjectId: entry.subjectId,
      })
    },
    onSuccess: async () => {
      setError(null)
      await queryClient.invalidateQueries({ queryKey })
    },
    onError: (e: unknown) => {
      setError(accessErrorMessage(e))
    },
  })

  function onSubmit(e: FormEvent) {
    e.preventDefault()
    if (!invite) {
      setError('Choisissez une personne ou une équipe')
      return
    }
    setError(null)
    grant.mutate()
  }

  const trash = useMutation({
    mutationFn: () => softDeleteDocument(api, objectId),
    onSuccess: () => {
      setConfirmTrash(false)
      window.location.href = '/trash'
    },
    onError: (e: unknown) => setError(accessErrorMessage(e)),
  })

  if (!objectId) {
    return (
      <main className="page-shell">
        <p className="text-socle-danger">
          Ressource non précisée —{' '}
          <Link to="/spaces" className="underline">
            choisir un espace
          </Link>
          .
        </p>
      </main>
    )
  }

  const resourceLabel =
    objectType === 'space' ? 'espace' : objectType === 'folder' ? 'dossier' : 'document'

  return (
    <main className="mx-auto w-[760px] max-w-[760px] px-0 pt-11 leading-[normal]">
      {objectType === 'document' && (
        <div
          className="mb-6 flex items-center gap-[22px] border-b border-socle-line"
          data-mock-id="access-doc-tabs"
          role="tablist"
        >
          <Link to={`/docs/${objectId}`} className="doc-tab" role="tab">
            Lire
          </Link>
          <Link to={`/docs/${objectId}/edit`} className="doc-tab" role="tab">
            Modifier
          </Link>
          <Link to={`/docs/${objectId}/history`} className="doc-tab" role="tab">
            Historique
          </Link>
          <Link to={`/docs/${objectId}`} className="doc-tab" role="tab">
            Commentaires
          </Link>
          <span className="doc-tab is-active" role="tab" aria-selected>
            Accès
          </span>
        </div>
      )}
      <div className="mb-6 flex items-start justify-between">
        <div className="breadcrumb">
          <Link to="/">Accueil</Link>
          <span className="text-[#DEDEE1]">→</span>
          {objectType === 'space' && (
            <>
              <Link to="/spaces">Espaces</Link>
              <span className="text-[#DEDEE1]">→</span>
            </>
          )}
          <span className="font-medium text-socle-ink">Accès et permissions</span>
        </div>
        {perms.data?.canManage && (
          <button
            type="button"
            data-mock-id="access-invite"
            onClick={() => setInviteOpen(true)}
            className="rounded-[7px] bg-socle-accent px-4 py-1.5 text-[13px] font-semibold text-white hover:bg-[#2C27C7]"
          >
            + Inviter
          </button>
        )}
      </div>

      <h1
        className="mb-7 font-display text-[36px] font-normal leading-[normal] text-socle-ink"
        data-mock-id="access-title"
      >
        Accès et permissions
      </h1>

      {objectType === 'document' && (
        <VisibilityBlock
          documentId={objectId}
          canManage={Boolean(perms.data?.canManage)}
          accessForbidden={perms.isError}
        />
      )}

      {perms.isError && (
        <p className="mt-6 rounded-lg border border-[#F2CFC2] bg-[#FCEEEA] px-4 py-3 text-sm text-[#7C2D12]">
          {accessErrorMessage(perms.error)}
        </p>
      )}

      <div className="mt-8">
        <div
          className="mb-3 text-[13px] font-semibold leading-[normal] text-socle-ink"
          data-mock-id="access-people-label"
        >
          Personnes et équipes
        </div>

        {perms.isSuccess && perms.data.canManage && inviteOpen && (
          <form
            onSubmit={onSubmit}
            className="mb-4 flex flex-wrap items-end gap-3 rounded-xl border border-socle-line bg-white p-4"
          >
            <PeoplePicker value={invite} onChange={setInvite} disabled={grant.isPending} />
            <label className="flex flex-col gap-1 text-sm">
              <span className="text-socle-muted">Rôle</span>
              <select
                value={relation}
                onChange={(e) => setRelation(e.target.value)}
                className="field-input"
              >
                <option value="viewer">Lecteur</option>
                <option value="editor">Éditeur</option>
                <option value="owner">Propriétaire</option>
              </select>
            </label>
            <button type="submit" disabled={grant.isPending || !invite} className="btn-primary">
              {grant.isPending ? 'Envoi…' : 'Accorder'}
            </button>
          </form>
        )}

        {error && (
          <p
            role="alert"
            className="mb-3 rounded-lg border border-[#F2CFC2] bg-[#FCEEEA] px-3 py-2 text-sm text-[#7C2D12]"
          >
            {error}
          </p>
        )}

        <ul
          className="overflow-hidden rounded-[10px] border border-socle-line"
          data-testid="access-list"
          data-mock-id="access-people-rows"
        >
          {perms.isLoading && <li className="text-socle-muted">Chargement…</li>}
          {perms.data?.entries.length === 0 && (
            <li className="rounded-xl border border-dashed border-[#DEDEE1] px-4 py-8 text-center text-sm text-socle-muted">
              Aucun accès listé sur cette ressource.
            </li>
          )}
          {perms.data?.entries.map((entry) => (
            <AccessEntryRow
              key={`${entry.subject}:${entry.relation}:${entry.source}:${entry.inheritedFrom ?? ''}`}
              entry={entry}
              onRevoke={(e) => revoke.mutate(e)}
              revoking={revoke.isPending}
            />
          ))}
        </ul>
      </div>

      <p className="mt-2 text-sm text-socle-muted">
        Personnes et équipes ayant accès à cet {resourceLabel} — distinction direct / via groupe /
        hérité.
      </p>
      {objectType === 'document' && perms.data?.canManage && (
        <div
          data-mock-id="access-delete-box"
          className="mt-8 flex items-center justify-between rounded-[10px] border px-4 py-4"
          style={{ borderColor: '#F2CFC2', background: '#FCEEEA' }}
        >
          <div>
            <div className="mb-0.5 text-[13.5px] font-semibold text-[#7C2D12]">
              Déplacer ce document vers la corbeille
            </div>
            <div className="text-[12px] text-[#9A4A2E]">
              Réservé au propriétaire — restaurable pendant 30 jours avant suppression définitive.
            </div>
          </div>
          {confirmTrash ? (
            <div className="flex gap-2">
              <button type="button" className="text-sm" onClick={() => setConfirmTrash(false)}>
                Annuler
              </button>
              <button
                type="button"
                className="rounded-[7px] border px-3.5 py-1.5 text-[13px] font-semibold text-[#B54708]"
                style={{ borderColor: '#E2A388' }}
                disabled={trash.isPending}
                onClick={() => trash.mutate()}
              >
                Confirmer
              </button>
            </div>
          ) : (
            <button
              type="button"
              className="rounded-[7px] border px-3.5 py-1.5 text-[13px] font-semibold text-[#B54708]"
              style={{ borderColor: '#E2A388' }}
              onClick={() => setConfirmTrash(true)}
            >
              Supprimer
            </button>
          )}
        </div>
      )}
    </main>
  )
}

function VisibilityBlock({
  documentId,
  canManage,
  accessForbidden,
}: {
  documentId: string
  canManage: boolean
  accessForbidden: boolean
}) {
  const qc = useQueryClient()
  const [error, setError] = useState<string | null>(null)

  const doc = useQuery({
    queryKey: ['document', documentId],
    queryFn: () => getDocument(api, documentId),
    enabled: Boolean(documentId),
    retry: false,
  })

  const space = useQuery({
    queryKey: ['space', doc.data?.spaceId],
    queryFn: () => getSpace(api, doc.data!.spaceId),
    enabled: Boolean(doc.data?.spaceId),
  })
  const branding = useQuery({
    queryKey: ['public-branding'],
    queryFn: async () => {
      const { data } = await api.get<{ instanceName?: string }>('/api/v1/public/branding')
      return data
    },
  })
  const orgName = branding.data?.instanceName?.trim() || "l'instance"
  const spaceName = space.data?.name?.trim() || "l'espace"

  const current = (doc.data?.visibility as DocumentVisibility | undefined) ?? 'space'
  const editable = canManage && !accessForbidden

  const save = useMutation({
    mutationFn: (visibility: DocumentVisibility) =>
      updateDocumentVisibility(api, documentId, visibility),
    onSuccess: async () => {
      setError(null)
      await qc.invalidateQueries({ queryKey: ['document', documentId] })
    },
    onError: (e: unknown) => {
      setError(accessErrorMessage(e))
    },
  })

  if (doc.isLoading) {
    return (
      <section className="mt-8" data-testid="visibility-block">
        <div className="section-label mb-3">Visibilité</div>
        <p className="text-sm text-socle-muted">Chargement…</p>
      </section>
    )
  }

  if (doc.isError || !doc.data) {
    return null
  }

  return (
    <section className="mt-8" data-testid="visibility-block">
      <div className="mb-3 flex items-baseline justify-between">
        <div className="text-[13px] font-semibold leading-[normal] text-socle-ink" data-mock-id="access-vis-label">
          Visibilité
        </div>
        <div className="text-[11.5px] text-[#9B9BA1]">
          Modifiable par les propriétaires de l&apos;espace
        </div>
      </div>
      {!editable && (
        <p className="mb-3 text-xs text-socle-muted">
          Lecture seule — seuls les owners de l’espace peuvent modifier la visibilité.
        </p>
      )}
      <div className="flex flex-col gap-2" role="radiogroup" aria-label="Visibilité du document">
        {VISIBILITY_OPTIONS.map((opt) => {
          const selected = current === opt.value
          return (
            <label
              key={opt.value}
              data-mock-id={`access-vis-${opt.value}`}
              className={`flex cursor-pointer items-start gap-3 rounded-[10px] px-4 py-3.5 ${
                selected
                  ? 'border-[1.5px] border-socle-accent bg-[#FAFAFE]'
                  : 'border border-socle-line bg-white'
              } ${!editable ? 'cursor-default opacity-90' : 'hover:border-[#C7C6F5]'}`}
            >
              <input
                type="radio"
                name="document-visibility"
                value={opt.value}
                checked={selected}
                disabled={!editable || save.isPending}
                onChange={() => {
                  if (editable && opt.value !== current) {
                    save.mutate(opt.value)
                  }
                }}
                className="sr-only"
                data-testid={`visibility-${opt.value}`}
              />
              <span
                aria-hidden
                className={`mt-0.5 shrink-0 rounded-full ${
                  selected ? 'border-socle-accent' : 'border-[#C7C6C6]'
                }`}
                style={{
                  width: 16,
                  height: 16,
                  boxSizing: 'content-box',
                  borderWidth: selected ? 5 : 1.5,
                  borderStyle: 'solid',
                }}
              />
              <span>
                <span className="block text-[13.5px] font-semibold leading-[normal] text-socle-ink">
                  {opt.label}
                </span>
                <span className="mt-0.5 block text-[12.5px] leading-[normal] text-[#6B6B72]">
                  {opt.hint(orgName, spaceName)}
                </span>
              </span>
            </label>
          )
        })}
      </div>
      {error && (
        <p role="alert" className="mt-3 text-sm text-socle-danger">
          {error}
        </p>
      )}
    </section>
  )
}
