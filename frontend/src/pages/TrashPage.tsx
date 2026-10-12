// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../lib/api'
import { apiErrorMessage } from '../lib/approvals'
import {
  formatDeletedAgo,
  formatPurgeRemaining,
  listTrash,
  purgeTrashItem,
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

  const purge = useMutation({
    mutationFn: (id: string) => purgeTrashItem(api, id),
    onSuccess: () => {
      setActionError(null)
      void queryClient.invalidateQueries({ queryKey: ['trash'] })
    },
    onError: (err) => {
      setActionError(apiErrorMessage(err, 'Impossible de supprimer définitivement.'))
    },
  })

  const total = page.data?.total ?? 0

  return (
    <main className="mx-auto w-[760px] max-w-[760px] px-0 pt-11 leading-[normal]">
      <div className="mb-6 flex flex-wrap items-center justify-between gap-3">
        <div className="breadcrumb">
          <Link to="/">Accueil</Link>
          <span className="text-[#DEDEE1]">→</span>
          <span className="font-medium text-socle-ink">Corbeille</span>
        </div>
      </div>

      <h1
        className="mb-1.5 font-display text-[36px] font-normal leading-[normal] text-socle-ink"
        data-mock-id="trash-title"
      >
        Corbeille
      </h1>
      <p className="mb-7 text-[14px] leading-[normal] text-[#9B9BA1]" data-mock-id="trash-lead">
        {page.isSuccess
          ? `${total} document${total === 1 ? '' : 's'} supprimé${total === 1 ? '' : 's'} · conservés 30 jours avant suppression définitive`
          : 'Éléments soft-deleted · restaurables avant purge'}
      </p>

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
        <ul className="mt-6 overflow-hidden rounded-[10px] border border-socle-line" data-mock-id="trash-list">
          {page.data.items.map((item, idx) => (
            <TrashRow
              key={item.id}
              index={idx}
              item={item}
              last={idx === page.data.items.length - 1}
              restoring={restore.isPending && restore.variables === item.id}
              purging={purge.isPending && purge.variables === item.id}
              onRestore={() => {
                setActionError(null)
                restore.mutate(item.id)
              }}
              onPurge={() => {
                setActionError(null)
                purge.mutate(item.id)
              }}
            />
          ))}
        </ul>
      )}

      <p className="mt-5 text-[12.5px] leading-[normal] text-[#75757C]" data-mock-id="trash-footer">
        Les documents restaurés reviennent à leur emplacement d&apos;origine dans l&apos;arborescence
        de l&apos;espace.
      </p>

      <div className="mt-6 flex flex-wrap gap-2" data-visual-mask="trash-type-filters">
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
    </main>
  )
}

function TrashRow({
  item,
  index,
  last,
  restoring,
  purging,
  onRestore,
  onPurge,
}: {
  item: TrashItem
  index: number
  last: boolean
  restoring: boolean
  purging: boolean
  onRestore: () => void
  onPurge: () => void
}) {
  const remaining = formatPurgeRemaining(item.purgeAt)
  const who = item.deletedByName?.trim() || 'un utilisateur'

  return (
    <li
      data-mock-id={`trash-row-${index}`}
      className={`flex items-center px-[18px] py-[14px] hover:bg-[#FAFAFB] ${
        last ? '' : 'border-b border-[#F5F5F7]'
      }`}
    >
      <div className="min-w-0 flex-grow">
        <div
          className="mb-[3px] truncate text-[14px] font-semibold leading-[normal] text-socle-ink"
          data-mock-id={`trash-row-${index}-title`}
        >
          {item.title || '(sans titre)'}
        </div>
        <div
          className="text-[12px] leading-[normal] text-[#9B9BA1]"
          data-mock-id={`trash-row-${index}-meta`}
        >
          Supprimé par {who} · {formatDeletedAgo(item.deletedAt)}
        </div>
      </div>
      <span
        data-mock-id={`trash-row-${index}-purge`}
        className={`mr-[22px] shrink-0 text-[11.5px] leading-[normal] ${
          remaining.urgent
            ? 'inline-flex items-center gap-[5px] font-semibold text-socle-danger'
            : 'text-[#9B9BA1]'
        }`}
      >
        {remaining.urgent && (
          <span className="inline-block h-[5px] w-[5px] rounded-full bg-socle-danger" aria-hidden />
        )}
        {remaining.label}
      </span>
      <div className="flex shrink-0 gap-1.5">
        <button
          type="button"
          data-mock-id={`trash-row-${index}-restore`}
          disabled={restoring || purging}
          onClick={onRestore}
          className="rounded-[7px] border border-socle-line px-3 py-1.5 text-[12.5px] font-semibold leading-[normal] text-socle-accent hover:bg-[#F0EFFC] disabled:opacity-60"
        >
          {restoring ? 'Restauration…' : 'Restaurer'}
        </button>
        <button
          type="button"
          data-mock-id={`trash-row-${index}-delete`}
          disabled={restoring || purging}
          onClick={() => {
            if (restoring || purging) return
            if (
              window.confirm(
                'Supprimer définitivement cet élément ? Cette action est irréversible.',
              )
            ) {
              onPurge()
            }
          }}
          className="text-[12.5px] font-semibold text-[#B54708] hover:underline disabled:opacity-60"
          style={{
            all: 'unset',
            appearance: 'none',
            WebkitAppearance: 'none',
            display: 'inline',
            background: 'transparent',
            border: 0,
            borderRadius: 0,
            boxShadow: 'none',
            padding: '6px 10px',
            margin: 0,
            font: 'inherit',
            fontFamily: 'inherit',
            fontSize: 12.5,
            fontWeight: 600,
            lineHeight: 'inherit',
            letterSpacing: 'inherit',
            textAlign: 'inherit',
            verticalAlign: 'baseline',
            color: '#B54708',
            cursor: 'pointer',
            boxSizing: 'content-box',
            textDecoration: 'none',
            WebkitFontSmoothing: 'auto',
            textRendering: 'auto',
          }}
        >
          {purging ? 'Suppression…' : 'Supprimer définitivement'}
        </button>
      </div>
    </li>
  )
}
