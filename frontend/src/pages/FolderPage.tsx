// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { FormEvent, useEffect, useMemo, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { useMutation, useQueries, useQuery, useQueryClient } from '@tanstack/react-query'
import { useAuth } from '../auth/AuthProvider'
import { NewFolderDialog } from '../components/NewFolderDialog'
import { api } from '../lib/api'
import { apiErrorMessage } from '../lib/apiError'
import { createDocument, emptyDocBody, getDocument, type DocumentDetail, type TagRef } from '../lib/documents'
import {
  addFavorite,
  favoriteKey,
  favoritesQueryKey,
  isFavorite,
  removeFavorite,
} from '../lib/favorites'
import {
  childrenOf,
  folderPath,
  deleteFolder,
  documentEditHref,
  documentHref,
  folderHref,
  getFolder,
  getFolderPage,
  getSpaceTree,
  saveFolderPage,
  spaceBrowseHref,
  spaceTreeKey,
  updateFolder,
  type ActorRef,
  type Folder,
  type StatusCounts,
} from '../lib/folders'
import { initialsOf, tagColors } from './document/documentPageUtils'
import { DocumentEditor } from './DocumentEditor'
import { DocumentHistoryPage } from './DocumentHistoryPage'
import './folder/folder-page.css'

const longDate = new Intl.DateTimeFormat('fr-FR', {
  day: 'numeric',
  month: 'long',
  year: 'numeric',
  timeZone: 'Europe/Paris',
})

function formatLong(iso: string | null | undefined): string | null {
  if (!iso) return null
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return null
  return longDate.format(d)
}

function reliabilityPercent(value: Folder['reliabilityAverage']): number | null {
  if (value == null || value === '') return null
  const n = typeof value === 'number' ? value : Number(value)
  if (!Number.isFinite(n)) return null
  return Math.round(n)
}

function isReferenceType(docType: string | null | undefined): boolean {
  return /r[eé]f[eé]rence/i.test(docType ?? '')
}

function systemActor(actor: ActorRef | null | undefined): boolean {
  return !actor || actor.kind === 'system' || !actor.displayName
}

function actorLabel(actor: ActorRef | null | undefined): string {
  if (systemActor(actor)) return 'Système (migration)'
  return actor!.displayName!
}

type CardTone = { label: string; color: string; dot: string; review: boolean }

function cardTone(status: string, docType: string | null | undefined): CardTone {
  if (isReferenceType(docType)) return { label: 'Référence', color: '#6B6B72', dot: '#8B95A1', review: false }
  if (status === 'en_revue') return { label: 'En revue', color: '#B7791F', dot: '#B7791F', review: true }
  if (status === 'valide') return { label: 'Validé', color: '#1E8E5A', dot: '#1E8E5A', review: false }
  if (status === 'brouillon') return { label: 'Brouillon', color: '#9B9BA1', dot: '#9B9BA1', review: false }
  if (status === 'archive' || status === 'archivé') {
    return { label: 'Archivé', color: '#9B9BA1', dot: '#9B9BA1', review: false }
  }
  return { label: status, color: '#43434A', dot: '#9B9BA1', review: false }
}

function reliabilityCaption(
  details: Array<DocumentDetail | undefined>,
  counts: StatusCounts,
): string {
  const loaded = details.filter((d): d is DocumentDetail => Boolean(d))
  const allReference =
    loaded.length > 0 && loaded.every((d) => isReferenceType(d.docType)) && counts.inReview === 0
  if (allReference) {
    const n = loaded.length
    return `${n} document${n > 1 ? 's' : ''} de référence à jour`
  }
  const v = counts.validated
  const r = counts.inReview
  return `${v} document${v > 1 ? 's' : ''} validé${v > 1 ? 's' : ''} · ${r} en revue`
}

function FolderFavorite({ folderId }: { folderId: string }) {
  const qc = useQueryClient()
  const fav = useQuery({
    queryKey: favoriteKey('folder', folderId),
    queryFn: () => isFavorite(api, 'folder', folderId),
  })
  const toggle = useMutation({
    mutationFn: async () => {
      if (fav.data) {
        await removeFavorite(api, 'folder', folderId)
        return false
      }
      await addFavorite(api, 'folder', folderId)
      return true
    },
    onSuccess: (next) => {
      qc.setQueryData(favoriteKey('folder', folderId), next)
      void qc.invalidateQueries({ queryKey: favoritesQueryKey })
    },
  })
  const on = Boolean(fav.data)
  return (
    <button
      type="button"
      className="fp-icon-btn"
      aria-label={on ? 'Retirer des favoris' : 'Ajouter aux favoris'}
      aria-pressed={on}
      title={on ? 'Dans vos favoris' : 'Ajouter aux favoris'}
      data-testid="favorite-star"
      onClick={() => toggle.mutate()}
    >
      <svg
        width="14"
        height="14"
        viewBox="0 0 24 24"
        fill={on ? '#B7791F' : 'none'}
        stroke={on ? '#B7791F' : '#9B9BA1'}
        strokeWidth="1.8"
        strokeLinecap="round"
        strokeLinejoin="round"
        aria-hidden
      >
        <polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2" />
      </svg>
    </button>
  )
}

function PersonMark({ actor, self }: { actor: ActorRef | null | undefined; self: boolean }) {
  if (systemActor(actor)) {
    return (
      <span className="fp-gear" aria-hidden>
        ⚙
      </span>
    )
  }
  return (
    <span className={self ? 'fp-avatar' : 'fp-avatar fp-avatar--other'} aria-hidden>
      {initialsOf(actor!.displayName!)}
    </span>
  )
}

/** Écran de dossier (FolderProcedures / FolderReference). La sidebar de l'app reste celle du shell. */
export function FolderPage() {
  const { me } = useAuth()
  const { id = '' } = useParams()
  const navigate = useNavigate()
  const qc = useQueryClient()
  const [tab, setTab] = useState<'apercu' | 'historique'>('apercu')
  const [newFolderOpen, setNewFolderOpen] = useState(false)
  const [renaming, setRenaming] = useState(false)
  const [nameDraft, setNameDraft] = useState('')
  const [confirmDelete, setConfirmDelete] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [draft, setDraft] = useState<Record<string, unknown> | null>(null)

  const folder = useQuery({
    queryKey: ['folder', id],
    queryFn: () => getFolder(api, id),
    enabled: Boolean(id),
  })
  const page = useQuery({
    queryKey: ['folder-page', id],
    queryFn: () => getFolderPage(api, id),
    enabled: Boolean(id),
  })
  const spaceId = folder.data?.spaceId ?? ''
  const tree = useQuery({
    queryKey: spaceTreeKey(spaceId),
    queryFn: () => getSpaceTree(api, spaceId),
    enabled: Boolean(spaceId),
  })

  const treeFolders = useMemo(() => tree.data?.folders ?? [], [tree.data])
  const pageDocumentId = folder.data?.pageDocumentId ?? page.data?.id ?? null
  const children = useMemo(() => {
    const raw = childrenOf({ folders: treeFolders, documents: tree.data?.documents ?? [] }, id)
    return {
      folders: raw.folders,
      documents: raw.documents.filter((d) => d.id !== pageDocumentId),
    }
  }, [treeFolders, tree.data, id, pageDocumentId])

  const docQueries = useQueries({
    queries: children.documents.map((d) => ({
      queryKey: ['document', d.id],
      queryFn: () => getDocument(api, d.id),
    })),
  })

  const canEdit = page.data ? Boolean(page.data.permissions?.canEdit) : page.isSuccess
  const content = draft ?? (page.data?.body as Record<string, unknown> | undefined) ?? emptyDocBody

  const save = useMutation({
    mutationFn: (body: Record<string, unknown>) =>
      saveFolderPage(api, id, body, 'Page de dossier'),
    onSuccess: (saved) => {
      qc.setQueryData(['folder-page', id], saved)
      void qc.invalidateQueries({ queryKey: ['folder', id] })
      void qc.invalidateQueries({ queryKey: ['document-versions', saved.id] })
    },
    onError: (e) => setError(apiErrorMessage(e, 'Enregistrement de la page impossible')),
  })

  useEffect(() => {
    if (!draft || !canEdit) return
    const handle = window.setTimeout(() => save.mutate(draft), 800)
    return () => window.clearTimeout(handle)
    // save.mutate est stable ; le timer ne doit repartir que quand le brouillon change.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [draft, canEdit])

  const createDoc = useMutation({
    mutationFn: () => createDocument(api, 'Sans titre', spaceId, emptyDocBody, id),
    onSuccess: (doc) => {
      void qc.invalidateQueries({ queryKey: spaceTreeKey(spaceId) })
      navigate(documentEditHref(doc.id))
    },
    onError: (e) => setError(apiErrorMessage(e, 'Création refusée — accès editor requis')),
  })

  const rename = useMutation({
    mutationFn: () => updateFolder(api, id, { name: nameDraft.trim() }),
    onSuccess: () => {
      setRenaming(false)
      setError(null)
      void qc.invalidateQueries({ queryKey: ['folder', id] })
      void qc.invalidateQueries({ queryKey: spaceTreeKey(spaceId) })
    },
    onError: (e) => setError(apiErrorMessage(e, 'Renommage impossible')),
  })

  const remove = useMutation({
    mutationFn: () => deleteFolder(api, id),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: spaceTreeKey(spaceId) })
      void qc.invalidateQueries({ queryKey: ['trash'] })
      const parent = folder.data?.parentFolderId
      navigate(parent ? folderHref(parent) : spaceBrowseHref(spaceId), { replace: true })
    },
    onError: (e) => setError(apiErrorMessage(e, 'Suppression impossible')),
  })

  function onRename(e: FormEvent) {
    e.preventDefault()
    if (nameDraft.trim() && nameDraft.trim() !== folder.data?.name) rename.mutate()
    else setRenaming(false)
  }

  if (folder.isLoading || page.isLoading) {
    return <main className="fp-loading">Chargement…</main>
  }
  if (folder.isError || !folder.data) {
    return (
      <main className="fp-loading">
        <Link to="/spaces">← Espaces</Link>
        <p className="fp-error">Dossier introuvable ou accès refusé.</p>
      </main>
    )
  }

  const f = folder.data
  const spaceName = tree.data?.spaceName ?? ''
  const trail = folderPath(treeFolders, id)
  const crumbs: { label: string; to?: string }[] = [
    ...(spaceName ? [{ label: spaceName, to: spaceBrowseHref(f.spaceId) }] : []),
    ...trail.map((node, i) => ({
      label: node.name,
      to: i < trail.length - 1 ? folderHref(node.id) : undefined,
    })),
  ]
  if (crumbs.length === 0) crumbs.push({ label: f.name })
  const archived = f.status === 'archivé'
  const pct = reliabilityPercent(f.reliabilityAverage)
  const created = formatLong(f.createdAt)
  const counts = f.statusCounts ?? { validated: 0, inReview: 0, draft: 0 }
  const details = docQueries.map((q) => q.data)
  const docCount = children.documents.length
  const tags = new Map<string, TagRef>()
  for (const d of details) {
    for (const t of d?.tags ?? []) {
      if (t.id && !tags.has(t.id)) tags.set(t.id, t)
    }
  }
  const tagList = [...tags.values()]
  const ownerName = f.ownerGroup?.name ?? null
  const ownerActor = f.lastModifiedBy?.kind === 'user' ? f.lastModifiedBy : null
  const selfActor = (actor: ActorRef | null | undefined) =>
    Boolean(actor?.kind === 'user' && actor.id && me?.id && actor.id === me.id)

  const titleSlot = renaming ? (
    <form className="fp-rename-form" onSubmit={onRename}>
      <input
        className="fp-rename-input"
        value={nameDraft}
        onChange={(e) => setNameDraft(e.target.value)}
        aria-label="Nom du dossier"
        autoFocus
        required
      />
      <button type="submit" className="fp-text-btn" disabled={rename.isPending}>
        Enregistrer
      </button>
      <button type="button" className="fp-text-btn" onClick={() => setRenaming(false)}>
        Annuler
      </button>
    </form>
  ) : (
    <div className="fp-title-row" data-mock-id="fp-title">
      <h1 className="fp-title">{f.name}</h1>
      {canEdit && (
        <button
          type="button"
          className="fp-rename"
          aria-label="Renommer le dossier"
          onClick={() => {
            setNameDraft(f.name)
            setRenaming(true)
          }}
        >
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#9B9BA1" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
            <path d="M17 3a2.85 2.83 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5Z" />
          </svg>
        </button>
      )}
    </div>
  )

  return (
    <div className="fp-page" data-testid="folder-page">
      <div className="fp-topbar">
        <nav className="fp-crumbs" aria-label="Fil d'Ariane" data-mock-id="fp-crumbs">
          {crumbs.map((c, i) => (
            <span key={`${c.label}-${i}`} style={{ display: 'contents' }}>
              {i > 0 && <span className="fp-crumb-sep">→</span>}
              {c.to && i < crumbs.length - 1 ? (
                <Link to={c.to}>{c.label}</Link>
              ) : (
                <span className="fp-crumb-current" aria-current="page">
                  {c.label}
                </span>
              )}
            </span>
          ))}
        </nav>
        <div className="fp-actions" data-mock-id="fp-actions">
          <a
            className="fp-icon-btn fp-icon-btn--help"
            href="https://docs.example.com"
            aria-label="Centre d'aide"
            title="Centre d'aide"
            target="_blank"
            rel="noreferrer"
          >
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#6B6B72" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
              <circle cx="12" cy="12" r="10" />
              <path d="M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3" />
              <line x1="12" y1="17" x2="12.01" y2="17" />
            </svg>
          </a>
          <a className="fp-icon-btn fp-icon-btn--kbd" href="#raccourcis" aria-label="Raccourcis clavier (?)" title="Raccourcis clavier" onClick={(e) => e.preventDefault()}>
            ?
          </a>
          <FolderFavorite folderId={id} />
          <Link
            to={`/folders/${id}/export`}
            className="fp-icon-btn"
            aria-label="Exporter le dossier"
            title="Exporter le dossier"
          >
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#43434A" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
              <path d="M14 3v4a1 1 0 0 0 1 1h4" />
              <path d="M17 21H7a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h7l5 5v11a2 2 0 0 1-2 2z" />
            </svg>
          </Link>
          {canEdit && (
            <>
              <span className="fp-actions-sep" aria-hidden />
              <a
                className="fp-text-btn"
                href="#sous-dossier"
                role="button"
                onClick={(e) => {
                  e.preventDefault()
                  setNewFolderOpen(true)
                }}
              >
                + Sous-dossier
              </a>
              <a
                className="fp-text-btn"
                href="#nouveau-document"
                role="button"
                aria-disabled={createDoc.isPending || undefined}
                onClick={(e) => {
                  e.preventDefault()
                  if (!createDoc.isPending) createDoc.mutate()
                }}
              >
                + Nouveau document
              </a>
            </>
          )}
        </div>
      </div>

      <div className="fp-body-wrap">
        <div className="fp-main">
          <div className="fp-tabs" data-mock-id="fp-tabs">
            <div className="fp-tablist" role="tablist" aria-label="Dossier">
              <a
                href={`/folders/${id}`}
                className={`fp-tab${tab === 'apercu' ? ' is-on' : ''}`}
                role="tab"
                aria-selected={tab === 'apercu'}
                onClick={(e) => {
                  e.preventDefault()
                  setTab('apercu')
                }}
              >
                Aperçu
              </a>
              <a
                href={`/folders/${id}#historique`}
                className={`fp-tab${tab === 'historique' ? ' is-on' : ''}`}
                role="tab"
                aria-selected={tab === 'historique'}
                onClick={(e) => {
                  e.preventDefault()
                  setTab('historique')
                }}
              >
                Historique
              </a>
              <Link className="fp-tab" to={`/folders/${id}/access`}>
                Accès
              </Link>
            </div>
            <span className="fp-tab-note">Page de dossier modifiable — comme un document</span>
          </div>

          {error && (
            <p className="fp-error" role="alert">
              {error}
            </p>
          )}

          {tab === 'historique' ? (
            page.data ? (
              <DocumentHistoryPage documentId={page.data.id} embedded />
            ) : (
              <p className="fp-empty" data-testid="folder-history-empty">
                Aucune page de dossier pour l&apos;instant.
              </p>
            )
          ) : (
            <>
              <div className="fp-meta" data-mock-id="fp-meta">
                <span className={`fp-status${archived ? ' is-archived' : ''}`}>
                  <span className="fp-dot" />
                  {archived ? 'Archivé' : 'Actif'}
                </span>
                <span className="fp-sep-dot" />
                <span className="fp-meta-muted">
                  Fiabilité moyenne {pct == null ? '—' : `${pct}%`}
                </span>
                {created && (
                  <>
                    <span className="fp-sep-dot" />
                    <span className="fp-meta-muted">Créé le {created}</span>
                  </>
                )}
                <span className="fp-sep-dot" />
                <span className="fp-lang">
                  <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="#6B6B72" strokeWidth="2" strokeLinecap="round" aria-hidden>
                    <circle cx="12" cy="12" r="10" />
                    <line x1="2" y1="12" x2="22" y2="12" />
                    <path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z" />
                  </svg>
                  Français
                </span>
              </div>

              {page.isSuccess && (
                <DocumentEditor
                  variant="document"
                  chrome={canEdit ? 'folder' : 'body'}
                  editable={canEdit}
                  content={content}
                  documentId={page.data?.id}
                  titleSlot={titleSlot}
                  onChange={(json) => {
                    if (!canEdit) return
                    setDraft(json)
                    setError(null)
                  }}
                />
              )}

              <p className="fp-count" data-mock-id="fp-count">
                {docCount} document{docCount === 1 ? '' : 's'} · sous-dossier de l&apos;espace{' '}
                <Link to={spaceBrowseHref(f.spaceId)}>{spaceName || 'Espace'}</Link>
              </p>

              <div className="fp-cards">
                {children.documents.map((doc, i) => {
                  const detail = details[i]
                  const tone = cardTone(detail?.status ?? doc.status, detail?.docType)
                  const when = formatLong(detail?.updatedAt)
                  const author = detail?.updatedBy?.displayName || (detail ? 'Système (migration)' : '')
                  const meta =
                    when && author
                      ? tone.review
                        ? `En revue depuis le ${when} · ${author}`
                        : `Révisé le ${when} · ${author}`
                      : null
                  return (
                    <Link
                      key={doc.id}
                      to={documentHref(doc.id)}
                      className="fp-card"
                      data-mock-id={`fp-card-${i}`}
                    >
                      <span className="fp-card-main">
                        <span className="fp-check" role="checkbox" aria-checked="false" aria-label="" />
                        <span className="fp-card-dot" style={{ background: tone.dot }} />
                        <span>
                          <span className="fp-card-title">{detail?.title || doc.title}</span>
                          {meta && <span className="fp-card-meta">{meta}</span>}
                        </span>
                      </span>
                      <span className="fp-card-status" style={{ color: tone.color }}>
                        {tone.label}
                      </span>
                    </Link>
                  )
                })}
              </div>
            </>
          )}
        </div>

        <aside className="fp-rail" aria-label="Informations du dossier">
          <div className="fp-rail-sticky">
            <div data-mock-id="fp-rail-docs">
              <div className="fp-rail-label">Documents</div>
              <div className="fp-rail-docs">
                {children.documents.map((doc) => (
                  <Link key={doc.id} to={documentHref(doc.id)} className="fp-rail-link">
                    {doc.title}
                  </Link>
                ))}
              </div>
            </div>

            <Link to={`/folders/${id}/access`} className="fp-rail-block" data-mock-id="fp-rail-owner">
              <div className="fp-rail-label">Propriétaire</div>
              <div className="fp-person">
                <PersonMark actor={ownerActor} self={selfActor(ownerActor)} />
                <span className="fp-person-name">{ownerName ?? '—'}</span>
              </div>
            </Link>

            <div className="fp-rail-block" data-mock-id="fp-rail-author">
              <div className="fp-rail-label">Créé par</div>
              <a href="#historique" className="fp-person" onClick={(e) => { e.preventDefault(); setTab('historique') }}>
                <PersonMark actor={f.creator} self={selfActor(f.creator)} />
                <span>
                  <span className="fp-person-name">{f.creator ? actorLabel(f.creator) : '—'}</span>
                  {created && <span className="fp-person-date">{created}</span>}
                </span>
              </a>
            </div>

            <a
              href="#historique"
              className="fp-rail-block"
              data-mock-id="fp-rail-modified"
              onClick={(e) => { e.preventDefault(); setTab('historique') }}
            >
              <div className="fp-rail-label">Dernière modification</div>
              <div className="fp-person">
                <PersonMark actor={f.lastModifiedBy} self={selfActor(f.lastModifiedBy)} />
                <span>
                  <span className="fp-person-name">{f.lastModifiedBy ? actorLabel(f.lastModifiedBy) : '—'}</span>
                  {formatLong(f.lastModifiedAt) && (
                    <span className="fp-person-date">{formatLong(f.lastModifiedAt)}</span>
                  )}
                </span>
              </div>
            </a>

            <div className="fp-rail-block" data-mock-id="fp-rail-tags">
              <div className="fp-tags-head">
                <div className="fp-rail-label">Tags</div>
                <Link to="/admin/tags" className="fp-manage">
                  Gérer →
                </Link>
              </div>
              <div className="fp-tags">
                {tagList.map((t, i) => {
                  const c = tagColors(t, i)
                  return (
                    <Link
                      key={t.id}
                      to={`/search?q=${encodeURIComponent(t.name)}`}
                      className="fp-tag"
                      style={{ color: c.fg, background: c.bg }}
                    >
                      {t.name}
                    </Link>
                  )
                })}
              </div>
            </div>

            <div className="fp-rail-block" data-mock-id="fp-rail-reliability">
              <div className="fp-rail-label">Fiabilité</div>
              <div className="fp-rel-line">
                <span className="fp-rel-dot" style={{ background: pct != null && pct < 70 ? '#B7791F' : '#1E8E5A' }} />
                <span className="fp-rel-text">
                  {pct == null ? '— · moyenne du dossier' : `${pct}% · moyenne du dossier`}
                </span>
              </div>
              <div className="fp-rel-sub">{reliabilityCaption(details, counts)}</div>
            </div>

            <div className="fp-rail-block" data-mock-id="fp-rail-export">
              <Link to={`/folders/${id}/export`} className="fp-export">
                Exporter le dossier →
              </Link>
            </div>

            {canEdit && (
              <div className="fp-delete">
                {confirmDelete ? (
                  <div role="group" aria-label="Confirmer la suppression du dossier">
                    <span className="fp-delete-q">Déplacer ce dossier vers la corbeille ?</span>
                    <button type="button" className="fp-delete-cancel" onClick={() => setConfirmDelete(false)} disabled={remove.isPending}>
                      Annuler
                    </button>
                    <button
                      type="button"
                      className="fp-delete-confirm"
                      data-testid="folder-delete-confirm"
                      onClick={() => remove.mutate()}
                      disabled={remove.isPending}
                    >
                      {remove.isPending ? 'Suppression…' : 'Confirmer'}
                    </button>
                  </div>
                ) : (
                  <button
                    type="button"
                    className="fp-delete-btn"
                    data-testid="folder-delete"
                    onClick={() => setConfirmDelete(true)}
                  >
                    Supprimer le dossier
                  </button>
                )}
              </div>
            )}
          </div>
        </aside>
      </div>

      <NewFolderDialog
        open={newFolderOpen}
        onOpenChange={setNewFolderOpen}
        spaceId={f.spaceId}
        spaceName={spaceName || 'Espace'}
        folders={treeFolders}
        defaultParentFolderId={id}
      />
    </div>
  )
}
