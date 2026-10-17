// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { FormEvent, useEffect, useId, useMemo, useRef, useState, type KeyboardEvent as ReactKeyboardEvent } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useAuth } from '../auth/AuthProvider'
import { NewFolderDialog } from '../components/NewFolderDialog'
import { CommentsPanel } from '../components/CommentsPanel'
import { api } from '../lib/api'
import { apiErrorMessage } from '../lib/apiError'
import { listComments } from '../lib/comments'
import { emptyDocBody } from '../lib/documents'
import { downloadSpaceZip } from '../lib/export'
import {
  addFavorite,
  favoriteKey,
  favoritesQueryKey,
  isFavorite,
  removeFavorite,
} from '../lib/favorites'
import {
  documentHref,
  folderHref,
  getSpaceTree,
  spaceTreeKey,
  type FolderDocumentCard,
} from '../lib/folders'
import { isPlatformAdmin } from '../lib/platformRoles'
import { getSpace, getSpacePage, saveSpacePage, updateSpace, type SpaceDetail, type SpaceFolderCard } from '../lib/spaces'
import { initialsOf, tagColors } from './document/documentPageUtils'
import { ChangeSummaryPopover } from './document/ChangeSummaryPopover'
import { DocumentSurfaceSwitch, DocumentTabs } from './document/DocumentChrome'
import { DocumentEditor } from './DocumentEditor'
import { DocumentHistoryPage } from './DocumentHistoryPage'
import './document/document-page.css'
import './document/document-edit.css'
import './folder/folder-page.css'
import './space/space-home.css'

type SpaceSurface = 'lire' | 'modifier' | 'historique' | 'commentaires'

const longDate = new Intl.DateTimeFormat('fr-FR', {
  day: 'numeric',
  month: 'long',
  year: 'numeric',
  timeZone: 'Europe/Paris',
})

const AVATAR_TONES = [
  { bg: '#0E0E10', fg: '#FFFFFF' },
  { bg: '#EEEDFD', fg: '#3730E0' },
  { bg: '#E6F4EC', fg: '#1E8E5A' },
  { bg: '#FBF3E4', fg: '#B7791F' },
  { bg: '#F5F5F7', fg: '#6B6B72' },
]

function formatLong(iso: string | null | undefined): string | null {
  if (!iso) return null
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return null
  return longDate.format(d)
}

function reliabilityPercent(value: SpaceDetail['reliabilityAverage']): number | null {
  if (value == null || value === '') return null
  const n = typeof value === 'number' ? value : Number(value)
  if (!Number.isFinite(n)) return null
  return Math.round(n)
}

function reliabilityColor(pct: number | null): string {
  return pct != null && pct < 70 ? '#B7791F' : '#1E8E5A'
}

function visibilityLabel(value: string | null | undefined): string {
  if (value === 'space') return "Visible par l'espace"
  if (value === 'restricted') return 'Accès restreint'
  return "Visible par l'organisation"
}

function unit(n: number, one: string, many: string): string {
  return `${n} ${n === 1 ? one : many}`
}

function folderCaption(card: SpaceFolderCard): string {
  const docs = unit(card.documentCount, 'document', 'documents')
  if (card.inReviewCount > 0) return `${docs} · ${card.inReviewCount} en revue`
  return `${docs} · à jour`
}

type CardTone = { label: string; color: string; dot: string; review: boolean; draft: boolean }

function cardTone(status: string): CardTone {
  if (status === 'en_revue') return { label: 'En revue', color: '#B7791F', dot: '#B7791F', review: true, draft: false }
  if (status === 'valide') return { label: 'Validé', color: '#1E8E5A', dot: '#1E8E5A', review: false, draft: false }
  if (status === 'brouillon') return { label: 'Brouillon', color: '#75757C', dot: '#C2C2C6', review: false, draft: true }
  if (status === 'archive' || status === 'archivé') {
    return { label: 'Archivé', color: '#9B9BA1', dot: '#9B9BA1', review: false, draft: false }
  }
  return { label: status, color: '#43434A', dot: '#9B9BA1', review: false, draft: false }
}

function cardMeta(doc: FolderDocumentCard, tone: CardTone): string | null {
  const when = formatLong(tone.review ? doc.reviewSince : doc.updatedAt)
  if (!when) return null
  const author = doc.updatedBy?.trim() || 'Système (migration)'
  if (tone.review) return `En revue depuis le ${when} · ${author}`
  if (tone.draft) return `Créé le ${when} · ${author}`
  return `Révisé le ${when} · ${author}`
}

function statusLine(counts: { validated: number; inReview: number; draft: number }): string {
  return `${unit(counts.validated, 'validé', 'validés')} · ${counts.inReview} en revue · ${unit(counts.draft, 'brouillon', 'brouillons')}`
}

function SpaceFavorite({ spaceId }: { spaceId: string }) {
  const qc = useQueryClient()
  const fav = useQuery({
    queryKey: favoriteKey('space', spaceId),
    queryFn: () => isFavorite(api, 'space', spaceId),
  })
  const toggle = useMutation({
    mutationFn: async () => {
      if (fav.data) {
        await removeFavorite(api, 'space', spaceId)
        return false
      }
      await addFavorite(api, 'space', spaceId)
      return true
    },
    onSuccess: (next) => {
      qc.setQueryData(favoriteKey('space', spaceId), next)
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

/** Accueil d'espace (SpaceHome.dc.html). L'arborescence reste dans la sidebar du shell. */
export function SpaceHomePage() {
  const { me } = useAuth()
  const { spaceId = '' } = useParams()
  const navigate = useNavigate()
  const qc = useQueryClient()
  const [tab, setTab] = useState<SpaceSurface>('lire')
  const [newFolderOpen, setNewFolderOpen] = useState(false)
  const [renaming, setRenaming] = useState(false)
  const [nameDraft, setNameDraft] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [draft, setDraft] = useState<Record<string, unknown> | null>(null)
  const [exporting, setExporting] = useState(false)

  const space = useQuery({
    queryKey: ['space', spaceId],
    queryFn: () => getSpace(api, spaceId),
    enabled: Boolean(spaceId),
  })
  const page = useQuery({
    queryKey: ['space-page', spaceId],
    queryFn: () => getSpacePage(api, spaceId),
    enabled: Boolean(spaceId),
  })
  const tree = useQuery({
    queryKey: spaceTreeKey(spaceId),
    queryFn: () => getSpaceTree(api, spaceId),
    enabled: Boolean(spaceId),
  })

  const pageDocumentId = page.data?.id ?? ''
  const comments = useQuery({
    queryKey: ['document-comments', pageDocumentId, 'ouvert', ''],
    queryFn: () => listComments(api, pageDocumentId, { status: 'ouvert' }),
    enabled: Boolean(pageDocumentId),
  })
  const openComments = comments.data?.openThreadCount ?? 0
  const content = draft ?? (page.data?.body as Record<string, unknown> | undefined) ?? emptyDocBody

  const save = useMutation({
    mutationFn: (input: { body: Record<string, unknown>; changeSummary?: string }) =>
      saveSpacePage(api, spaceId, input.body, input.changeSummary),
    onSuccess: (saved) => {
      qc.setQueryData(['space-page', spaceId], saved)
      setDraft(null)
      setTab('lire')
      setError(null)
      void qc.invalidateQueries({ queryKey: ['space', spaceId] })
      void qc.invalidateQueries({ queryKey: ['document-versions', saved.id] })
      void qc.invalidateQueries({ queryKey: ['document-comments', saved.id] })
    },
    onError: (e) => setError(apiErrorMessage(e, "Enregistrement de la page impossible")),
  })

  const rename = useMutation({
    mutationFn: () => updateSpace(api, spaceId, { name: nameDraft.trim() }),
    onSuccess: () => {
      setRenaming(false)
      setError(null)
      void qc.invalidateQueries({ queryKey: ['space', spaceId] })
      void qc.invalidateQueries({ queryKey: ['spaces'] })
      void qc.invalidateQueries({ queryKey: spaceTreeKey(spaceId) })
    },
    onError: (e) => setError(apiErrorMessage(e, 'Renommage impossible')),
  })

  function onRename(e: FormEvent) {
    e.preventDefault()
    if (nameDraft.trim() && nameDraft.trim() !== space.data?.name) rename.mutate()
    else setRenaming(false)
  }

  async function onExport(e: { preventDefault: () => void }) {
    e.preventDefault()
    if (exporting || !space.data) return
    setExporting(true)
    setError(null)
    try {
      await downloadSpaceZip(api, spaceId, `${space.data.name}.zip`)
    } catch (err) {
      setError(apiErrorMessage(err, "Export de l'espace impossible"))
    } finally {
      setExporting(false)
    }
  }

  if (space.isLoading || page.isLoading) {
    return <main className="fp-loading">Chargement…</main>
  }
  if (space.isError || !space.data) {
    return (
      <main className="fp-loading">
        <Link to="/spaces">← Espaces</Link>
        <p className="fp-error">Espace introuvable ou accès refusé.</p>
      </main>
    )
  }

  const s = space.data
  const canEdit = Boolean(s.canEdit)
  const publicOnly = s.membership === 'public-only'
  const hasPage = Boolean(page.data)
  const pct = reliabilityPercent(s.reliabilityAverage)
  const created = formatLong(s.createdAt)
  const counts = s.statusCounts ?? { validated: 0, inReview: 0, draft: 0 }
  const folders = s.folders ?? []
  const cards = s.rootDocuments ?? []
  const reviews = s.toReview ?? []
  const tags = s.tags ?? []
  const members = s.members ?? []
  const memberCount = s.memberCount ?? members.length
  const teamCount = s.teamCount ?? 0
  const extraMembers = Math.max(0, memberCount - members.length)
  const ownerInitials = s.owner?.owner?.initials?.trim() || (s.owner?.owner?.displayName ? initialsOf(s.owner.owner.displayName) : '')
  const activityWho = s.lastActivity?.who?.trim() || ''
  const activityWhen = formatLong(s.lastActivity?.at)

  const titleSlot = renaming ? (
    <form className="fp-rename-form" onSubmit={onRename}>
      <input
        className="fp-rename-input"
        value={nameDraft}
        onChange={(e) => setNameDraft(e.target.value)}
        aria-label="Nom de l'espace"
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
    <div className="fp-title-row" data-mock-id="sh-title">
      <h1 className="fp-title">{s.name}</h1>
    </div>
  )

  return (
    <div className="fp-page sh-page" data-testid="space-home">
      <div className="fp-topbar">
        <nav className="fp-crumbs" aria-label="Fil d'Ariane" data-mock-id="sh-crumbs">
          <Link to="/spaces">Espaces</Link>
          <span className="fp-crumb-sep">→</span>
          <span className="fp-crumb-current" aria-current="page">
            {s.name}
          </span>
        </nav>
        <div className="fp-actions doc-actions" data-mock-id="sh-actions">
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
          <SpaceFavorite spaceId={spaceId} />
          <DocumentSurfaceSwitch spaceId={spaceId} />
          <a
            href="#exporter"
            className="fp-icon-btn fp-export-icon"
            aria-label="Exporter l'espace"
            title="Exporter l'espace"
            data-testid="space-export"
            aria-busy={exporting || undefined}
            onClick={onExport}
          >
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#43434A" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
              <path d="M14 3v4a1 1 0 0 0 1 1h4" />
              <path d="M17 21H7a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h7l5 5v11a2 2 0 0 1-2 2z" />
            </svg>
          </a>
          {canEdit && (
            <>
              <span className="fp-actions-sep" aria-hidden />
              <a
                className="fp-text-btn"
                href="#dossier"
                role="button"
                data-testid="space-new-folder"
                onClick={(e) => {
                  e.preventDefault()
                  setNewFolderOpen(true)
                }}
              >
                + Dossier
              </a>
              <a
                className="fp-text-btn"
                href="#nouveau-document"
                role="button"
                data-testid="space-new-document"
                onClick={(e) => {
                  e.preventDefault()
                  navigate(`/docs/new?spaceId=${spaceId}`)
                }}
              >
                + Nouveau document
              </a>
            </>
          )}
          <SpaceActionsMenu
            canEdit={canEdit}
            onRename={() => {
              setNameDraft(s.name)
              setRenaming(true)
            }}
            onSettings={() => navigate(`/spaces/${spaceId}/settings`)}
            onHealth={() => navigate(`/spaces/${spaceId}/content-health`)}
            onTemplate={() => navigate(`/docs/new?spaceId=${spaceId}`)}
          />
        </div>
      </div>

      <DocumentTabs
        documentId={pageDocumentId || spaceId}
        mockPrefix="sh"
        ariaLabel="Sections de l'espace"
        current={tab === 'historique' ? 'history' : tab === 'modifier' ? 'edit' : 'read'}
        showEdit={canEdit}
        showAccess={!publicOnly}
        openComments={openComments}
        commentsOpen={tab === 'commentaires'}
        onToggleComments={() => setTab((current) => (current === 'commentaires' ? 'lire' : 'commentaires'))}
        onSelectTab={(next) => setTab(next === 'read' ? 'lire' : next === 'edit' ? 'modifier' : 'historique')}
        accessTo={`/spaces/${spaceId}/access`}
        historyDisabled={!hasPage}
        commentsDisabled={!hasPage}
      />

      <div className="fp-body-wrap">
        <div className="fp-main">
          {error && (
            <p className="fp-error" role="alert">
              {error}
            </p>
          )}

          {tab === 'historique' ? (
            page.data ? (
              <DocumentHistoryPage documentId={page.data.id} embedded />
            ) : (
              <p className="fp-empty" data-testid="space-history-empty">
                Aucune page d&apos;accueil pour l&apos;instant.
              </p>
            )
          ) : (
            <>
              <div className="fp-meta" data-mock-id="sh-meta">
                <span className="fp-status">
                  <span className="fp-dot" />
                  Actif
                </span>
                <span className="fp-sep-dot" />
                <span className="fp-meta-muted">Fiabilité moyenne {pct == null ? '—' : `${pct}%`}</span>
                {created && (
                  <>
                    <span className="fp-sep-dot" />
                    <span className="fp-meta-muted">Créé le {created}</span>
                  </>
                )}
                <span className="fp-sep-dot" />
                <span className="fp-lang">
                  <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="#6B6B72" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
                    <path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2" />
                    <circle cx="9" cy="7" r="4" />
                    <path d="M23 21v-2a4 4 0 0 0-3-3.87" />
                    <path d="M16 3.13a4 4 0 0 1 0 7.75" />
                  </svg>
                  {visibilityLabel(s.defaultVisibility)}
                </span>
              </div>

              {tab === 'modifier' && (
                <div className="fp-edit-save">
                  <ChangeSummaryPopover
                    label="Enregistrer"
                    buttonClassName="edit-ghost"
                    testId="edit-save-version"
                    disabled={save.isPending}
                    title="Créer une version"
                    onConfirm={(changeSummary) => {
                      const body = draft ?? (page.data?.body as Record<string, unknown> | undefined) ?? emptyDocBody
                      save.mutate({ body, changeSummary: changeSummary || undefined })
                    }}
                  />
                </div>
              )}

              {tab !== 'modifier' && !hasPage ? (
                <>
                  <div className="fp-title-row" data-mock-id="sh-title">
                    <h1 className="fp-title">{s.name}</h1>
                  </div>
                  <p className="sh-empty" data-testid="space-page-empty">
                    {canEdit
                      ? "Cette page d'accueil n'existe pas encore. Passez à Modifier pour l'écrire."
                      : "Cette page d'accueil n'a pas encore été écrite."}
                  </p>
                </>
              ) : (
                <DocumentEditor
                  key={tab === 'modifier' ? 'modifier' : 'lire'}
                  variant="document"
                  chrome={tab === 'modifier' ? 'full' : 'body'}
                  editable={tab === 'modifier'}
                  content={content}
                  documentId={page.data?.id}
                  titleSlot={titleSlot}
                  bodyMockId="sh-body"
                  onChange={(json) => {
                    if (tab !== 'modifier') return
                    setDraft(json)
                    setError(null)
                  }}
                />
              )}

              {tab !== 'modifier' && (
                <>
                  <p className="fp-count" data-mock-id="sh-count">
                    {unit(s.documentCount ?? 0, 'document', 'documents')} · {unit(folders.length, 'dossier', 'dossiers')}
                  </p>

                  <div className="sh-kicker" data-mock-id="sh-folders-label">
                    Dossiers
                  </div>
                  <div className="sh-folders">
                    {folders.map((folder, i) => {
                      const folderPct = reliabilityPercent(folder.reliabilityAverage)
                      return (
                        <Link
                          key={folder.id}
                          to={folderHref(folder.id)}
                          className="fp-card"
                          data-mock-id={`sh-folder-${i}`}
                        >
                          <span className="sh-folder-main">
                            <span className="sh-folder-icon" aria-hidden>
                              <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="#6B6B72" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                                <path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z" />
                              </svg>
                            </span>
                            <span>
                              <span className="fp-card-title">{folder.name}</span>
                              <span className="fp-card-meta">{folderCaption(folder)}</span>
                            </span>
                          </span>
                          {folderPct != null && (
                            <span className="sh-folder-rel">
                              <span className="fp-rel-dot" style={{ background: reliabilityColor(folderPct) }} />
                              {folderPct}%
                            </span>
                          )}
                        </Link>
                      )
                    })}
                  </div>

                  <div className="sh-kicker" data-mock-id="sh-root-label">
                    Documents à la racine
                  </div>
                  <div className="fp-cards">
                    {cards.map((doc, i) => {
                      const tone = cardTone(doc.status)
                      const meta = cardMeta(doc, tone)
                      return (
                        <Link
                          key={doc.id}
                          to={documentHref(doc.id)}
                          className="fp-card"
                          data-mock-id={`sh-card-${i}`}
                        >
                          <span className="fp-card-main">
                            <span className="fp-check" role="checkbox" aria-checked="false" aria-label="" />
                            <span className="fp-card-dot" style={{ background: tone.dot }} />
                            <span>
                              <span className="fp-card-title">{doc.title}</span>
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
            </>
          )}
        </div>

        {tab === 'commentaires' &&
          (page.data ? (
            <CommentsPanel
              documentId={page.data.id}
              versionNo={page.data.currentVersionNo}
              canComment={canEdit}
              spaceId={spaceId}
              className="fp-comments"
              onClose={() => setTab('lire')}
            />
          ) : (
            <p className="fp-empty" data-testid="space-comments-empty">
              Aucune page d&apos;accueil pour l&apos;instant.
            </p>
          ))}

        <aside className="fp-rail" aria-label="Informations de l'espace">
          <div className="fp-rail-sticky">
            <Link to={`/spaces/${spaceId}/access`} data-mock-id="sh-rail-owner" style={{ display: 'block', color: 'inherit' }}>
              <div className="fp-rail-label">Propriétaire</div>
              <div className="fp-person">
                {ownerInitials && <span className="fp-avatar" aria-hidden>{ownerInitials}</span>}
                <span className="fp-person-name">{s.owner?.name ?? '—'}</span>
              </div>
            </Link>

            <Link to={`/spaces/${spaceId}/access`} className="fp-rail-block" data-mock-id="sh-rail-members">
              <div className="fp-rail-label">Membres</div>
              <div className="sh-avatars">
                {members.map((m, i) => {
                  const tone = AVATAR_TONES[i] ?? AVATAR_TONES[0]!
                  return (
                    <span key={`${m.initials}-${i}`} className="sh-avatar" style={{ background: tone.bg, color: tone.fg }} aria-hidden>
                      {m.initials}
                    </span>
                  )
                })}
                {extraMembers > 0 && (
                  <span className="sh-avatar" style={{ background: AVATAR_TONES[4]!.bg, color: AVATAR_TONES[4]!.fg }} aria-hidden>
                    +{extraMembers}
                  </span>
                )}
              </div>
              <div className="sh-members-count">
                {unit(memberCount, 'membre', 'membres')} · {unit(teamCount, 'équipe', 'équipes')}
              </div>
            </Link>

            <div className="fp-rail-block" data-mock-id="sh-rail-activity">
              <div className="fp-rail-label">Dernière activité</div>
              <Link to="/notifications" className="fp-person">
                {activityWho && <span className="fp-avatar" aria-hidden>{initialsOf(activityWho)}</span>}
                <span>
                  <span className="fp-person-name">{activityWho || '—'}</span>
                  {activityWhen && <span className="fp-person-date">{activityWhen}</span>}
                </span>
              </Link>
            </div>

            <div className="fp-rail-block" data-mock-id="sh-rail-tags">
              <div className="fp-tags-head">
                <div className="fp-rail-label">Tags</div>
                {isPlatformAdmin(me?.roles) && (
                  <Link to="/admin/tags" className="fp-manage">
                    Gérer →
                  </Link>
                )}
              </div>
              <div className="fp-tags">
                {tags.map((t, i) => {
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

            <div className="fp-rail-block" data-mock-id="sh-rail-reliability">
              <div className="fp-rail-label">Fiabilité</div>
              <div className="fp-rel-line">
                <span className="fp-rel-dot" style={{ background: reliabilityColor(pct) }} />
                <span className="fp-rel-text">
                  {pct == null ? "— · moyenne de l'espace" : `${pct}% · moyenne de l'espace`}
                </span>
              </div>
              <div className="fp-rel-sub">{statusLine(counts)}</div>
            </div>

            <div className="fp-rail-block" data-mock-id="sh-rail-review">
              <div className="fp-rail-label">À revoir</div>
              {reviews.length === 0 ? (
                <span className="fp-person-name">Aucun document en revue</span>
              ) : (
                reviews.map((doc) => {
                  const since = formatLong(doc.reviewSince)
                  return (
                    <span key={doc.id}>
                      <Link to={documentHref(doc.id)} className="sh-review-title">
                        {doc.title}
                      </Link>
                      {since && <span className="sh-review-since">En revue depuis le {since}</span>}
                    </span>
                  )
                })
              )}
            </div>

            <div className="fp-rail-block" data-mock-id="sh-rail-export">
              <a href="#exporter" className="fp-export" onClick={onExport}>
                Exporter l&apos;espace →
              </a>
            </div>
          </div>
        </aside>
      </div>

      <NewFolderDialog
        open={newFolderOpen}
        onOpenChange={setNewFolderOpen}
        spaceId={spaceId}
        spaceName={s.name}
        folders={tree.data?.folders ?? []}
        defaultParentFolderId={null}
      />
    </div>
  )
}

function SpaceActionsMenu({
  canEdit,
  onRename,
  onSettings,
  onHealth,
  onTemplate,
}: {
  canEdit: boolean
  onRename: () => void
  onSettings: () => void
  onHealth: () => void
  onTemplate: () => void
}) {
  const [open, setOpen] = useState(false)
  const [active, setActive] = useState(0)
  const rootRef = useRef<HTMLDivElement>(null)
  const buttonRef = useRef<HTMLButtonElement>(null)
  const itemRefs = useRef<Array<HTMLButtonElement | null>>([])
  const menuId = useId()
  const items = useMemo(
    () => [
      ...(canEdit ? [{ label: 'Renommer', run: onRename, disabled: false }] : []),
      { label: "Paramètres de l'espace", run: onSettings, disabled: false },
      { label: 'Santé du contenu', run: onHealth, disabled: false },
      ...(canEdit ? [{ label: 'Depuis un modèle', run: onTemplate, disabled: false }] : []),
      { label: "Archiver l'espace", run: () => undefined, disabled: true },
    ],
    [canEdit, onHealth, onRename, onSettings, onTemplate],
  )

  useEffect(() => {
    if (!open) return
    itemRefs.current[active]?.focus()
  }, [open, active])

  useEffect(() => {
    if (!open) return
    function onDoc(e: MouseEvent) {
      if (!rootRef.current?.contains(e.target as Node)) setOpen(false)
    }
    document.addEventListener('mousedown', onDoc)
    return () => document.removeEventListener('mousedown', onDoc)
  }, [open])

  function choose(index: number) {
    const item = items[index]
    if (!item || item.disabled) return
    setOpen(false)
    item.run()
  }

  function step(dir: 1 | -1) {
    setActive((from) => {
      for (let n = 1; n <= items.length; n++) {
        const i = (from + dir * n + items.length) % items.length
        if (!items[i]?.disabled) return i
      }
      return from
    })
  }

  function onKey(e: ReactKeyboardEvent) {
    if (e.key === 'Escape') {
      e.preventDefault()
      setOpen(false)
      buttonRef.current?.focus()
      return
    }
    if (e.key === 'ArrowDown') {
      e.preventDefault()
      step(1)
      return
    }
    if (e.key === 'ArrowUp') {
      e.preventDefault()
      step(-1)
      return
    }
    if (e.key === 'Home') {
      e.preventDefault()
      setActive(items.findIndex((item) => !item.disabled))
      return
    }
    if (e.key === 'End') {
      e.preventDefault()
      let last = 0
      items.forEach((item, i) => {
        if (!item.disabled) last = i
      })
      setActive(last)
      return
    }
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault()
      choose(active)
    }
  }

  const title = items.map((item) => item.label).join(', ')

  return (
    <div className="fp-more-wrap" ref={rootRef}>
      <button
        ref={buttonRef}
        type="button"
        className="fp-more"
        aria-haspopup="menu"
        aria-expanded={open}
        aria-controls={open ? menuId : undefined}
        aria-label="Autres actions de l'espace"
        title={title}
        onClick={() => {
          setActive(items.findIndex((item) => !item.disabled))
          setOpen((v) => !v)
        }}
      >
        <svg width="14" height="14" viewBox="0 0 24 24" fill="#43434A" stroke="none" aria-hidden>
          <circle cx="5" cy="12" r="1.8" />
          <circle cx="12" cy="12" r="1.8" />
          <circle cx="19" cy="12" r="1.8" />
        </svg>
      </button>
      {open && (
        <div id={menuId} className="fp-menu" role="menu" aria-label="Autres actions de l'espace" onKeyDown={onKey}>
          {items.map((item, i) => (
            <button
              key={item.label}
              ref={(el) => {
                itemRefs.current[i] = el
              }}
              type="button"
              role="menuitem"
              className="fp-menu-item"
              tabIndex={-1}
              disabled={item.disabled}
              title={item.disabled ? 'Bientôt disponible' : undefined}
              data-testid={item.disabled ? 'space-archive-soon' : undefined}
              onClick={() => choose(i)}
            >
              {item.label}
            </button>
          ))}
        </div>
      )}
    </div>
  )
}
