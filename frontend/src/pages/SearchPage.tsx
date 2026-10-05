// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { FormEvent, useState } from 'react'
import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { api } from '../lib/api'
import { apiErrorMessage } from '../lib/approvals'
import { searchDocuments } from '../lib/search'
import { listSpaces } from '../lib/spaces'

/**
 * Recherche full-text — résultats déjà filtrés côté API par droit de lecture OpenFGA.
 * Aucune page inaccessible ne doit apparaître (y compris en extrait).
 */
export function SearchPage() {
  const [draft, setDraft] = useState('')
  const [q, setQ] = useState('')
  const [spaceId, setSpaceId] = useState('')
  const [tag, setTag] = useState('')
  const [docType, setDocType] = useState('')

  const spaces = useQuery({
    queryKey: ['spaces'],
    queryFn: () => listSpaces(api),
  })

  const results = useQuery({
    queryKey: ['search', q, spaceId, tag, docType],
    queryFn: () =>
      searchDocuments(api, {
        q,
        spaceId: spaceId || undefined,
        tag: tag || undefined,
        docType: docType || undefined,
      }),
    enabled: q.trim().length > 0,
    retry: false,
  })

  function onSubmit(e: FormEvent) {
    e.preventDefault()
    setQ(draft.trim())
  }

  return (
    <main className="page-shell max-w-[820px]">
      <div className="breadcrumb mb-6">
        <Link to="/">Accueil</Link>
        <span className="text-[#DEDEE1]">→</span>
        <span className="font-medium text-socle-ink">Recherche</span>
      </div>

      <h1 className="serif-title">Recherche</h1>
      <p className="mt-1.5 text-sm text-socle-muted">
        Plein texte sur titre, contenu et tags — uniquement les pages que vous pouvez lire.
      </p>

      <form onSubmit={onSubmit} className="mt-8 space-y-4">
        <div className="flex flex-wrap gap-3">
          <input
            className="min-w-[220px] flex-1 rounded-lg border border-socle-line bg-white px-3 py-2.5 text-sm"
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            placeholder="Mot-clé, procédure, politique…"
            aria-label="Requête de recherche"
          />
          <button type="submit" className="btn-primary" disabled={!draft.trim()}>
            Rechercher
          </button>
        </div>

        <div className="flex flex-wrap gap-3 text-sm">
          <label className="flex flex-col gap-1">
            Espace
            <select
              className="rounded-lg border border-socle-line bg-white px-3 py-2"
              value={spaceId}
              onChange={(e) => setSpaceId(e.target.value)}
            >
              <option value="">Tous mes espaces</option>
              {spaces.data?.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.name}
                </option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-1">
            Tag
            <input
              className="rounded-lg border border-socle-line bg-white px-3 py-2"
              value={tag}
              onChange={(e) => setTag(e.target.value)}
              placeholder="ex. IAM"
            />
          </label>
          <label className="flex flex-col gap-1">
            Type
            <input
              className="rounded-lg border border-socle-line bg-white px-3 py-2"
              value={docType}
              onChange={(e) => setDocType(e.target.value)}
              placeholder="ex. politique"
            />
          </label>
        </div>
      </form>

      {q && results.isLoading && (
        <p className="mt-8 text-sm text-socle-muted">Recherche…</p>
      )}
      {results.isError && (
        <p className="mt-8 text-sm text-socle-danger" role="alert">
          {apiErrorMessage(results.error, 'Recherche impossible')}
        </p>
      )}

      {results.isSuccess && (
        <div className="mt-8">
          <p className="mb-4 text-xs text-socle-muted">
            {results.data.total === 0
              ? `Aucun résultat pour « ${results.data.query} »`
              : `${results.data.total} résultat${results.data.total > 1 ? 's' : ''} pour « ${results.data.query} »`}
          </p>
          <ul className="space-y-3">
            {results.data.results.map((hit) => (
              <li key={hit.id}>
                <Link
                  to={`/docs/${hit.id}`}
                  className="block rounded-xl border border-socle-line bg-white px-4 py-3 hover:border-[#C7C6F5] hover:bg-socle-mist/40"
                >
                  <div className="flex flex-wrap items-baseline justify-between gap-2">
                    <span className="font-semibold text-socle-ink">{hit.title}</span>
                    <span className="text-[11px] text-socle-slate">
                      {hit.spaceName}
                      {hit.docType ? ` · ${hit.docType}` : ''}
                    </span>
                  </div>
                  {hit.excerpt && (
                    <p className="mt-1.5 line-clamp-2 text-sm text-socle-muted">{hit.excerpt}</p>
                  )}
                </Link>
              </li>
            ))}
          </ul>
        </div>
      )}
    </main>
  )
}
