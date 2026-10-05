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

type ResourceKind = 'space' | 'folder' | 'document'

const VISIBILITY_OPTIONS: Array<{
  value: DocumentVisibility
  label: string
  hint: string
}> = [
  {
    value: 'organisation',
    label: 'Tous les utilisateurs',
    hint: "Toute personne authentifiée de l'instance peut consulter ce document.",
  },
  {
    value: 'space',
    label: "Membres de l'espace",
    hint: "Restreint aux membres ayant accès à l'espace (ou au dossier parent).",
  },
  {
    value: 'restricted',
    label: 'Personnes et équipes listées',
    hint: "Accès limité aux personnes et équipes listées ci-dessous, plus les owners de l'espace.",
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
        <p className="truncate font-mono text-xs text-socle-ink">{entry.subject}</p>
        {entry.inheritedFrom && (
          <p className="text-xs text-socle-slate">
            Hérité de <span className="font-mono">{entry.inheritedFrom}</span>
          </p>
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
      setError(subjectType === 'group' ? 'Sélectionnez un groupe' : 'UUID utilisateur requis')
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
    <main className="page-shell">
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

      <h1 className="serif-title">Accès et permissions</h1>
      <p className="mt-2 text-sm text-socle-muted">
        Personnes et équipes ayant accès à cet {resourceLabel} — distinction direct / via groupe /
        hérité.
      </p>
      <p className="mt-2 font-mono text-xs text-socle-faint">
        {objectType}:{objectId}
      </p>

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
        <div className="section-label mb-3">Personnes et équipes</div>

        {perms.isSuccess && perms.data.canManage && (
          <form
            onSubmit={onSubmit}
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
                <span className="text-socle-muted">UUID utilisateur</span>
                <input
                  value={subjectId}
                  onChange={(e) => setSubjectId(e.target.value)}
                  placeholder="id utilisateur"
                  className="field-input font-mono text-xs"
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
      <div className="section-label mb-3">Visibilité</div>
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
              className={`flex cursor-pointer items-start gap-3 rounded-[10px] border px-4 py-3.5 ${
                selected
                  ? 'border-socle-accent bg-[#FAFAFE]'
                  : 'border-socle-line bg-white'
              } ${!editable ? 'cursor-default opacity-90' : 'hover:bg-socle-soft'}`}
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
                className="mt-1"
                data-testid={`visibility-${opt.value}`}
              />
              <span>
                <span className="block text-[13.5px] font-semibold text-socle-ink">
                  {opt.label}
                </span>
                <span className="mt-0.5 block text-[12.5px] text-socle-muted">{opt.hint}</span>
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
