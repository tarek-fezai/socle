// SPDX-License-Identifier: AGPL-3.0-or-later
import { FormEvent, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { AdminShell } from '../components/admin/AdminShell'
import { api } from '../lib/api'
import { apiErrorMessage } from '../lib/apiError'
import { tagColors } from './document/documentPageUtils'
import {
  createAdminTag,
  deleteAdminTag,
  listAdminTags,
  mergeAdminTag,
  renameAdminTag,
  tagCreationPolicyLabel,
  tagsAdminKey,
  updateTagCreationPolicy,
  type TagAdminView,
  type TagCreationPolicy,
} from '../lib/tagsAdmin'

type ModalKind = 'create' | 'rename' | 'delete' | 'merge' | null

function TagPill({ tag, index }: { tag: TagAdminView; index: number }) {
  const c = tagColors({ id: tag.id, name: tag.name, color: tag.color ?? undefined }, index)
  return (
    <span className="admin-tag-pill" style={{ color: c.fg, background: c.bg }}>
      <span className="admin-tag-pill__dot" style={{ background: c.fg }} aria-hidden />
      {tag.name}
    </span>
  )
}

/** Administration des tags organisation (TagsAdmin.dc.html). */
export function TagsAdminPage() {
  const qc = useQueryClient()
  const [modal, setModal] = useState<ModalKind>(null)
  const [activeTag, setActiveTag] = useState<TagAdminView | null>(null)
  const [nameInput, setNameInput] = useState('')
  const [mergeTargetId, setMergeTargetId] = useState('')
  const [transferAssignments, setTransferAssignments] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const tagsQuery = useQuery({
    queryKey: tagsAdminKey(),
    queryFn: () => listAdminTags(api),
  })

  const tags = tagsQuery.data?.tags ?? []
  const summary = tagsQuery.data?.summary

  const invalidate = () => void qc.invalidateQueries({ queryKey: tagsAdminKey() })

  const openCreate = () => {
    setError(null)
    setNameInput('')
    setActiveTag(null)
    setModal('create')
  }

  const openRename = (tag: TagAdminView) => {
    setError(null)
    setActiveTag(tag)
    setNameInput(tag.name)
    setModal('rename')
  }

  const openDelete = (tag: TagAdminView) => {
    setError(null)
    setActiveTag(tag)
    setModal('delete')
  }

  const openMerge = (tag: TagAdminView) => {
    setError(null)
    setActiveTag(tag)
    setMergeTargetId('')
    setTransferAssignments(tag.governed)
    setModal('merge')
  }

  const closeModal = () => {
    setModal(null)
    setActiveTag(null)
    setError(null)
  }

  const createMut = useMutation({
    mutationFn: (name: string) => createAdminTag(api, { name }),
    onSuccess: () => {
      closeModal()
      invalidate()
    },
    onError: (e) => setError(apiErrorMessage(e, 'Création impossible')),
  })

  const renameMut = useMutation({
    mutationFn: ({ id, name }: { id: string; name: string }) => renameAdminTag(api, id, name),
    onSuccess: () => {
      closeModal()
      invalidate()
    },
    onError: (e) => setError(apiErrorMessage(e, 'Renommage impossible')),
  })

  const deleteMut = useMutation({
    mutationFn: (id: string) => deleteAdminTag(api, id),
    onSuccess: () => {
      closeModal()
      invalidate()
    },
    onError: (e) => setError(apiErrorMessage(e, 'Suppression impossible')),
  })

  const mergeMut = useMutation({
    mutationFn: ({ sourceId, targetTagId }: { sourceId: string; targetTagId: string }) =>
      mergeAdminTag(api, sourceId, { targetTagId, transferAssignments }),
    onSuccess: () => {
      closeModal()
      invalidate()
    },
    onError: (e) => setError(apiErrorMessage(e, 'Fusion impossible')),
  })

  const policyMut = useMutation({
    mutationFn: (policy: TagCreationPolicy) => updateTagCreationPolicy(api, policy),
    onSuccess: () => invalidate(),
    onError: (e) => setError(apiErrorMessage(e, 'Politique impossible à mettre à jour')),
  })

  const statsLine = useMemo(() => {
    if (!summary) return null
    return `${summary.tagCount} tags · ${summary.taggedDocumentCount} documents étiquetés.`
  }, [summary])

  const mergeTargets = tags.filter((t) => t.id !== activeTag?.id)

  function onModalSubmit(e: FormEvent) {
    e.preventDefault()
    const name = nameInput.trim()
    if (modal === 'create') {
      if (!name) return
      createMut.mutate(name)
      return
    }
    if (modal === 'rename' && activeTag) {
      if (!name) return
      renameMut.mutate({ id: activeTag.id, name })
      return
    }
    if (modal === 'merge' && activeTag && mergeTargetId) {
      mergeMut.mutate({ sourceId: activeTag.id, targetTagId: mergeTargetId })
    }
  }

  const busy =
    createMut.isPending || renameMut.isPending || deleteMut.isPending || mergeMut.isPending

  return (
    <AdminShell
      active="tags"
      breadcrumb={[
        { label: 'Administration', to: '/admin/tags' },
        { label: 'Tags' },
      ]}
      rightRail={
        summary ? (
          <aside className="admin-rail" data-mock-id="tags-rail">
            <div>
              <div className="admin-rail__label">Tags actifs</div>
              <div className="admin-rail__value" data-mock-id="tags-rail-count">
                {summary.tagCount}
              </div>
            </div>
            <div>
              <div className="admin-rail__label">Documents étiquetés</div>
              <div className="admin-rail__value" data-mock-id="tags-rail-tagged">
                {summary.taggedDocumentCount}{' '}
                <span className="admin-rail__value-sub">/ {summary.totalDocumentCount}</span>
              </div>
            </div>
            <div>
              <div className="admin-rail__label">Qui peut créer un tag</div>
              <div
                role="button"
                tabIndex={0}
                className="admin-rail__text admin-policy-btn"
                data-mock-id="tags-rail-policy"
                onClick={() =>
                  policyMut.mutate(
                    summary.tagCreationPolicy === 'any_editor' ? 'admins_only' : 'any_editor',
                  )
                }
                onKeyDown={(e) => {
                  if (e.key === 'Enter' || e.key === ' ') {
                    e.preventDefault()
                    policyMut.mutate(
                      summary.tagCreationPolicy === 'any_editor' ? 'admins_only' : 'any_editor',
                    )
                  }
                }}
                title="Cliquer pour alterner la politique"
              >
                {tagCreationPolicyLabel(summary.tagCreationPolicy)}
              </div>
            </div>
          </aside>
        ) : null
      }
    >
      <div className="admin-title-row">
        <h1 className="admin-title" data-mock-id="tags-title">
          Tags
        </h1>
        <button type="button" className="admin-cta" data-mock-id="tags-cta" onClick={openCreate}>
          + Nouveau tag
        </button>
      </div>
      <p className="admin-lead" data-mock-id="tags-stats">
        Étiquettes utilisées pour classer et retrouver les documents dans toute l&apos;organisation.
        {statsLine ? ` ${statsLine}` : tagsQuery.isLoading ? ' …' : ''}
      </p>

      {tagsQuery.isError && (
        <p className="admin-alert admin-alert--error" role="alert">
          {apiErrorMessage(tagsQuery.error, 'Impossible de charger les tags')}
        </p>
      )}

      <div className="admin-table-wrap" data-mock-id="tags-table">
        <div className="admin-table-head">
          <div style={{ flex: 2 }}>Tag</div>
          <div style={{ flex: 1 }}>Documents</div>
          <div style={{ flex: 1.6 }}>Créé par</div>
          <div style={{ width: 240 }}>Actions</div>
        </div>
        {tags.map((tag, index) => (
          <div key={tag.id} className="admin-table-row">
            <div style={{ flex: 2 }}>
              <TagPill tag={tag} index={index} />
            </div>
            <div style={{ flex: 1, color: '#43434A' }}>{tag.documentCount}</div>
            <div style={{ flex: 1.6, color: '#6B6B72' }}>{tag.createdByDisplayName ?? '—'}</div>
            <div className="admin-actions" style={{ width: 240 }}>
              {tag.documentCount > 0 && (
                <Link
                  to={`/tags/${tag.id}/export`}
                  className="admin-action admin-action--neutral"
                  data-mock-id={index === 0 ? 'tags-action-export' : undefined}
                >
                  Exporter
                </Link>
              )}
              {!tag.governed && (
                <button
                  type="button"
                  className="admin-action admin-action--accent"
                  onClick={() => openRename(tag)}
                >
                  Renommer
                </button>
              )}
              {tag.governed ? (
                <button
                  type="button"
                  className="admin-action admin-action--accent"
                  onClick={() => openMerge(tag)}
                >
                  Fusionner
                </button>
              ) : null}
              <button
                type="button"
                className="admin-action admin-action--danger"
                onClick={() => openDelete(tag)}
              >
                Supprimer
              </button>
            </div>
          </div>
        ))}
      </div>

      <div className="admin-callout">
        <p style={{ margin: 0 }} data-mock-id="tags-merge-callout">
          « Fusionner » réattribue tous les documents d&apos;un tag vers un autre et supprime le
          doublon — utile pour unifier des variantes comme « obsolète » et « à archiver ».
        </p>
      </div>

      {modal && (
        <div className="admin-modal-backdrop" role="presentation" onClick={closeModal}>
          <div
            className="admin-modal"
            role="dialog"
            aria-modal="true"
            aria-labelledby="tags-modal-title"
            onClick={(e) => e.stopPropagation()}
          >
            {modal === 'delete' && activeTag ? (
              <>
                <h2 id="tags-modal-title">Supprimer le tag</h2>
                <p>
                  Supprimer « {activeTag.name} » ? Les documents ne seront plus étiquetés avec ce
                  tag.
                </p>
                {error && (
                  <p className="admin-alert admin-alert--error" role="alert">
                    {error}
                  </p>
                )}
                <div className="admin-form-actions" style={{ paddingBottom: 0 }}>
                  <button type="button" className="admin-btn-ghost" onClick={closeModal}>
                    Annuler
                  </button>
                  <button
                    type="button"
                    className="admin-cta"
                    disabled={deleteMut.isPending}
                    onClick={() => deleteMut.mutate(activeTag.id)}
                  >
                    Supprimer
                  </button>
                </div>
              </>
            ) : (
              <form onSubmit={onModalSubmit}>
                <h2 id="tags-modal-title">
                  {modal === 'create' && 'Nouveau tag'}
                  {modal === 'rename' && 'Renommer le tag'}
                  {modal === 'merge' && 'Fusionner le tag'}
                </h2>
                {modal === 'merge' && activeTag ? (
                  <>
                    <p>
                      Tous les documents de « {activeTag.name} » seront réattribués au tag cible,
                      puis « {activeTag.name} » sera supprimé.
                    </p>
                    <label className="admin-form-label" htmlFor="merge-target">
                      Tag cible
                    </label>
                    <select
                      id="merge-target"
                      className="admin-form-input"
                      value={mergeTargetId}
                      onChange={(e) => setMergeTargetId(e.target.value)}
                      required
                    >
                      <option value="">Choisir…</option>
                      {mergeTargets.map((t) => (
                        <option key={t.id} value={t.id}>
                          {t.name}
                        </option>
                      ))}
                    </select>
                    {activeTag.governed && (
                      <label className="admin-toggle-row">
                        <input
                          type="checkbox"
                          checked={transferAssignments}
                          onChange={(e) => setTransferAssignments(e.target.checked)}
                        />
                        Transférer les attributions de gouvernance vers la cible
                      </label>
                    )}
                  </>
                ) : (
                  <>
                    <label className="admin-form-label" htmlFor="tag-name">
                      Nom
                    </label>
                    <input
                      id="tag-name"
                      className="admin-form-input"
                      value={nameInput}
                      onChange={(e) => setNameInput(e.target.value)}
                      required
                      autoFocus
                    />
                  </>
                )}
                {error && (
                  <p className="admin-alert admin-alert--error" role="alert">
                    {error}
                  </p>
                )}
                {modal === 'rename' && activeTag && (
                  <p style={{ fontSize: 13, marginBottom: 12 }}>
                    <button
                      type="button"
                      className="admin-action admin-action--accent"
                      onClick={() => openMerge(activeTag)}
                    >
                      Fusionner ce tag avec un autre…
                    </button>
                  </p>
                )}
                <div className="admin-form-actions" style={{ paddingBottom: 0 }}>
                  <button type="button" className="admin-btn-ghost" onClick={closeModal}>
                    Annuler
                  </button>
                  <button type="submit" className="admin-cta" disabled={busy}>
                    {modal === 'create' && 'Créer'}
                    {modal === 'rename' && 'Enregistrer'}
                    {modal === 'merge' && 'Fusionner'}
                  </button>
                </div>
              </form>
            )}
          </div>
        </div>
      )}
    </AdminShell>
  )
}
