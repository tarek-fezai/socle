// SPDX-License-Identifier: AGPL-3.0-or-later
import { Link, useParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { StaleBadge } from '../components/StaleBadge'
import { api } from '../lib/api'
import { fetchContentHealth } from '../lib/contentHealth'
import { getSpace } from '../lib/spaces'

export function ContentHealthPage() {
  const { spaceId = '' } = useParams()

  const space = useQuery({
    queryKey: ['space', spaceId],
    queryFn: () => getSpace(api, spaceId),
    enabled: Boolean(spaceId),
  })

  const health = useQuery({
    queryKey: ['content-health', spaceId],
    queryFn: () => fetchContentHealth(api, spaceId),
    enabled: Boolean(spaceId),
    staleTime: 0,
    refetchOnMount: 'always',
  })

  if (space.isLoading || health.isLoading) {
    return <main className="page-shell text-socle-muted">Chargement…</main>
  }

  if (health.isError || !health.data) {
    return (
      <main className="page-shell">
        <Link to="/spaces" className="text-sm font-semibold text-socle-accent">
          ← Espaces
        </Link>
        <p className="mt-4 text-socle-danger">Santé du contenu indisponible ou accès refusé.</p>
      </main>
    )
  }

  const data = health.data

  return (
    <main className="page-shell max-w-[780px]">
      <div className="breadcrumb mb-6">
        <Link to="/spaces">Espaces</Link>
        <span className="text-[#DEDEE1]">→</span>
        <Link to={`/spaces/${spaceId}`}>{space.data?.name ?? 'Espace'}</Link>
        <span className="text-[#DEDEE1]">→</span>
        <span className="font-medium text-socle-ink">Santé du contenu</span>
      </div>

      <h1 className="serif-title">Santé du contenu</h1>
      <p className="mt-1.5 text-sm text-socle-muted">
        Documents sans écriture de contenu depuis plus de {data.thresholdDays} jours (seuil
        d&apos;instance). Filtré par vos droits de lecture.
      </p>

      <div className="mt-6 flex flex-wrap gap-6 text-sm">
        <div>
          <div className="text-[11px] font-semibold uppercase tracking-wide text-socle-muted">
            Visibles
          </div>
          <div className="font-display text-2xl text-socle-ink">{data.viewableDocumentCount}</div>
        </div>
        <div>
          <div className="text-[11px] font-semibold uppercase tracking-wide text-socle-muted">
            Obsolètes
          </div>
          <div className="font-display text-2xl text-socle-warn">{data.staleCount}</div>
        </div>
      </div>

      <ul className="mt-8 space-y-2" data-testid="stale-document-list">
        {data.staleDocuments.map((d) => (
          <li key={d.id}>
            <Link
              to={`/docs/${d.id}`}
              className="flex items-center justify-between gap-3 rounded-xl border border-socle-line bg-white px-4 py-3 hover:border-[#C7C6F5]"
            >
              <div className="min-w-0">
                <div className="truncate font-semibold text-socle-ink">{d.title}</div>
                <div className="text-[12px] text-socle-muted">
                  {d.ageDays} jour{d.ageDays === 1 ? '' : 's'} · {d.status}
                </div>
              </div>
              <StaleBadge stale contentModifiedAt={d.contentModifiedAt} compact />
            </Link>
          </li>
        ))}
      </ul>

      {data.staleDocuments.length === 0 ? (
        <p className="mt-8 text-sm text-socle-muted">
          Aucun document obsolète parmi ceux que vous pouvez lire.
        </p>
      ) : null}
    </main>
  )
}
