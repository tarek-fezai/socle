import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../lib/api'
import { apiErrorMessage } from '../lib/approvals'
import {
  formatDeletedAgo,
  formatPurgeRemaining,
  listTrash,
  resourceTypeLabel,
  restoreTrashItem,
  type TrashItem,
  type TrashResourceTypeFilter,
} from '../lib/trash'

const TYPE_FILTERS: Array<{ label: string; value: TrashResourceTypeFilter }> = [
  { label: 'Tous les types', value: '' },
  { label: 'Documents', value: 'document' },
  { label: 'Dossiers', value: 'folder' },
  { label: 'Espaces', value: 'space' },
]

export function TrashPage() {
  const queryClient = useQueryClient()
  const [resourceType, setResourceType] = useState<TrashResourceTypeFilter>('')
  const [actionError, setActionError] = useState<string | null>(null)

  const page = useQuery({
    queryKey: ['trash', resourceType],
    queryFn: () =>
      listTrash(api, {
        resourceType: resourceType || undefined,
        limit: 50,
        offset: 0,
      }),
  })

  const restore = useMutation({
    mutationFn: (id: string) => restoreTrashItem(api, id),
    onSuccess: () => {
      setActionError(null)
      void queryClient.invalidateQueries({ queryKey: ['trash'] })
    },
    onError: (err) => {
      setActionError(apiErrorMessage(err, 'Impossible de restaurer cet élément.'))
    },
  })

  const total = page.data?.total ?? 0

  return (
    <main className="page-shell max-w-[760px]">
      <div className="mb-6 flex flex-wrap items-center justify-between gap-3">
        <div className="breadcrumb">
          <Link to="/">Accueil</Link>
          <span className="text-[#DEDEE1]">→</span>
          <span className="font-medium text-socle-ink">Corbeille</span>
        </div>
      </div>

      <h1 className="serif-title">Corbeille</h1>
      <p className="mt-1.5 text-sm text-socle-muted">
        {page.isSuccess
          ? `${total} élément${total === 1 ? '' : 's'} · conservés avant suppression définitive`
          : 'Éléments soft-deleted · restaurables avant purge'}
      </p>

      <div className="mt-6 flex flex-wrap gap-2">
        {TYPE_FILTERS.map((f) => {
          const active = resourceType === f.value
          return (
            <button
              key={f.label}
              type="button"
              onClick={() => {
                setResourceType(f.value)
                setActionError(null)
              }}
              className={
                active
                  ? 'rounded-lg bg-socle-mist px-3 py-1.5 text-[12.5px] font-medium text-socle-accent'
                  : 'rounded-lg border border-socle-line px-3 py-1.5 text-[12.5px] text-socle-slate hover:bg-[#ECECEE]'
              }
            >
              {f.label}
            </button>
          )
        })}
      </div>

      {page.isLoading && <p className="mt-8 text-socle-muted">Chargement…</p>}
      {page.isError && (
        <p className="mt-8 text-socle-danger">
          {apiErrorMessage(page.error, 'Impossible de charger la corbeille.')}
        </p>
      )}
      {actionError && <p className="mt-4 text-sm text-socle-danger">{actionError}</p>}

      {page.isSuccess && page.data.items.length === 0 && (
        <p className="mt-8 rounded-xl border border-dashed border-[#DEDEE1] px-4 py-10 text-center text-sm text-socle-muted">
          La corbeille est vide.
        </p>
      )}

      {page.isSuccess && page.data.items.length > 0 && (
        <ul className="mt-6 overflow-hidden rounded-[10px] border border-socle-line">
          {page.data.items.map((item, idx) => (
            <TrashRow
              key={item.id}
              item={item}
              last={idx === page.data.items.length - 1}
              restoring={restore.isPending && restore.variables === item.id}
              onRestore={() => {
                setActionError(null)
                restore.mutate(item.id)
              }}
            />
          ))}
        </ul>
      )}

      <p className="mt-5 text-[12.5px] text-socle-faint">
        Les documents restaurés reviennent à leur emplacement d&apos;origine dans l&apos;arborescence
        de l&apos;espace.
      </p>
    </main>
  )
}

function TrashRow({
  item,
  last,
  restoring,
  onRestore,
}: {
  item: TrashItem
  last: boolean
  restoring: boolean
  onRestore: () => void
}) {
  const purge = formatPurgeRemaining(item.purgeAt)
  const who = item.deletedByName?.trim() || 'un utilisateur'
  const type = resourceTypeLabel(item.resourceType)

  return (
    <li
      className={`flex flex-wrap items-center gap-3 px-[18px] py-3.5 hover:bg-[#FAFAFB] ${
        last ? '' : 'border-b border-[#F5F5F7]'
      }`}
    >
      <div className="min-w-0 flex-grow">
        <div className="truncate text-sm font-semibold text-socle-ink">
          {item.title || '(sans titre)'}
        </div>
        <div className="mt-0.5 text-xs text-socle-muted">
          {type} · Supprimé par {who} · {formatDeletedAgo(item.deletedAt)}
        </div>
      </div>
      <span
        className={`mr-2 shrink-0 text-[11.5px] ${
          purge.urgent
            ? 'inline-flex items-center gap-1.5 font-semibold text-socle-danger'
            : 'text-socle-muted'
        }`}
      >
        {purge.urgent && (
          <span className="inline-block h-[5px] w-[5px] rounded-full bg-socle-danger" aria-hidden />
        )}
        {purge.label}
      </span>
      <button
        type="button"
        disabled={restoring}
        onClick={onRestore}
        className="shrink-0 rounded-[7px] border border-socle-line px-3 py-1.5 text-[12.5px] font-semibold text-socle-accent hover:bg-[#F0EFFC] disabled:opacity-60"
      >
        {restoring ? 'Restauration…' : 'Restaurer'}
      </button>
    </li>
  )
}
