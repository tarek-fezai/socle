// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { FormEvent, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../lib/api'
import {
  accessErrorMessage,
  grantAccess,
  listAccess,
  revokeAccess,
  sourceLabel,
  type AccessEntry,
  type AccessSource,
} from '../lib/access'
import { listGroups } from '../lib/groups'
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

const SOURCE_STYLES: Record<AccessSource, string> = {
  direct: 'bg-[#F1F8F3] text-socle-success ring-[#D3EBD9]',
  group: 'bg-socle-mist text-socle-accent ring-[#DCDAFB]',
  inherited: 'bg-[#FBF3E4] text-socle-warn ring-[#F2E4C4]',
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
  const source = (entry.source as AccessSource) ?? 'direct'
  return (
    <li className="row flex items-start justify-between gap-3 rounded-xl border border-socle-line bg-white px-4 py-3 hover:bg-socle-soft">
      <div className="min-w-0 space-y-1.5">
        <div className="flex flex-wrap items-center gap-2">
          <span
            className={`inline-flex rounded-md px-2 py-0.5 text-xs font-semibold ring-1 ring-inset ${SOURCE_STYLES[source]}`}
          >
            {sourceLabel(source)}
          </span>
          <span className="rounded-md border border-socle-line px-2 py-0.5 text-xs font-medium text-[#43434A]">
            {entry.relation}
          </span>
          <span className="text-xs text-socle-muted">{entry.subjectType}</span>
        </div>
        <p className="truncate text-xs text-socle-ink">
          {entry.subjectType === 'group' ? 'Groupe' : 'Personne'}
        </p>
        {entry.inheritedFrom && (
          <p className="text-xs text-socle-slate">Hérité d&apos;un parent</p>
        )}
        {source === 'group' && (
          <p className="text-xs text-socle-slate">
            Accès accordé au groupe (tous les membres héritent de ce droit).
          </p>
        )}
      </div>
      {entry.revocable ? (
        <button
          type="button"
          onClick={() => onRevoke(entry)}
          disabled={revoking}
          className="shrink-0 text-sm font-semibold text-socle-danger underline-offset-4 hover:underline disabled:opacity-50"
        >
          Révoquer
        </button>
      ) : (
        <span className="shrink-0 text-xs text-socle-muted">Non révocable ici</span>
      )}
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

  const [subjectId, setSubjectId] = useState('')
  const [relation, setRelation] = useState('editor')
  const [subjectType, setSubjectType] = useState<'user' | 'group'>('user')
  const [error, setError] = useState<string | null>(null)

  const perms = useQuery({
    queryKey,
    queryFn: () => listAccess(api, objectType, objectId),
    enabled: Boolean(objectId),
    retry: false,
  })

  const groups = useQuery({
    queryKey: ['groups'],
    queryFn: () => listGroups(api),
    enabled: subjectType === 'group',
  })

  const grant = useMutation({
    mutationFn: () =>
      grantAccess(api, objectType, objectId, {
        relation,
        subjectType,
        subjectId: subjectId.trim(),
      }),
    onSuccess: async () => {
      setError(null)
      setSubjectId('')
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
    if (!subjectId.trim()) {
      setError(subjectType === 'group' ? 'Sélectionnez un groupe' : 'Identifiant utilisateur requis')
      return
    }
    setError(null)
    grant.mutate()
  }

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
      <div className="breadcrumb mb-6">
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

        {perms.isSuccess && perms.data.canManage && (
          <form
            onSubmit={onSubmit}
            data-visual-mask="access-grant-form"
            className="mb-4 flex flex-wrap items-end gap-3 rounded-xl border border-socle-line bg-white p-4"
          >
            <label className="flex flex-col gap-1 text-sm">
              <span className="text-socle-muted">Sujet</span>
              <select
                value={subjectType}
                onChange={(e) => {
                  setSubjectType(e.target.value as 'user' | 'group')
                  setSubjectId('')
                }}
                className="field-input"
              >
                <option value="user">Utilisateur</option>
                <option value="group">Groupe</option>
              </select>
            </label>
            {subjectType === 'group' ? (
              <label className="flex min-w-[16rem] flex-1 flex-col gap-1 text-sm">
                <span className="text-socle-muted">Groupe</span>
                <select
                  value={subjectId}
                  onChange={(e) => setSubjectId(e.target.value)}
                  className="field-input"
                  required
                >
                  <option value="">— choisir —</option>
                  {groups.data?.map((g) => (
                    <option key={g.id} value={g.id}>
                      {g.name}
                    </option>
                  ))}
                </select>
                {groups.data?.length === 0 && (
                  <span className="text-xs text-socle-muted">
                    Aucun groupe — créez-en un dans{' '}
                    <Link to="/team" className="underline">
                      Équipes
                    </Link>
                    .
                  </span>
                )}
              </label>
            ) : (
              <label className="flex min-w-[16rem] flex-1 flex-col gap-1 text-sm">
                <span className="text-socle-muted">Identifiant utilisateur</span>
                <input
                  value={subjectId}
                  onChange={(e) => setSubjectId(e.target.value)}
                  placeholder="Identifiant"
                  className="field-input text-xs"
                />
              </label>
            )}
            <label className="flex flex-col gap-1 text-sm">
              <span className="text-socle-muted">Niveau</span>
              <select
                value={relation}
                onChange={(e) => setRelation(e.target.value)}
                className="field-input"
              >
                <option value="viewer">viewer</option>
                <option value="editor">editor</option>
                <option value="owner">owner</option>
              </select>
            </label>
            <button type="submit" disabled={grant.isPending} className="btn-primary">
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

        <ul className="space-y-2" data-testid="access-list">
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
      <p className="mt-8 text-xs text-socle-muted">
        Seuls les accès <strong className="text-socle-slate">directs</strong> ou{' '}
        <strong className="text-socle-slate">via groupe</strong> sur cette ressource sont
        révocables ici. Les accès hérités se gèrent sur le parent indiqué. La visibilité et l’ACL
        d’une page sont réservées aux owners de l’espace.
      </p>
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
