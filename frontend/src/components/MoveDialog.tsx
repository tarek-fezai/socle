// SPDX-License-Identifier: AGPL-3.0-or-later
import { useEffect, useMemo, useState } from 'react'
import * as Dialog from '@radix-ui/react-dialog'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { api } from '../lib/api'
import { apiErrorMessage } from '../lib/approvals'
import { moveDocument } from '../lib/documents'
import {
  buildSpaceTree,
  descendantFolderIds,
  moveFolder,
  spaceTreeKey,
  type FolderTreeNode,
  type TreeDocument,
  type TreeFolder,
} from '../lib/folders'

export type MoveTarget = {
  kind: 'document' | 'folder'
  id: string
  name: string
  /** Emplacement actuel (null = racine de l'espace). */
  currentParentId: string | null
}

type Props = {
  open: boolean
  onOpenChange: (open: boolean) => void
  spaceId: string
  spaceName: string
  folders: TreeFolder[]
  target: MoveTarget | null
  onMoved?: (target: MoveTarget, destinationId: string | null) => void
}

/** Sélecteur de destination en arbre (dossiers uniquement) pour documents et dossiers. */
export function MoveDialog({
  open,
  onOpenChange,
  spaceId,
  spaceName,
  folders,
  target,
  onMoved,
}: Props) {
  const qc = useQueryClient()
  const [dest, setDest] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (open && target) {
      setDest(target.currentParentId)
      setError(null)
    }
  }, [open, target])

  // Un dossier ne peut être déplacé ni dans lui-même ni dans un de ses descendants.
  const blocked = useMemo(() => {
    if (!target || target.kind !== 'folder') return new Set<string>()
    const set = descendantFolderIds(folders, target.id)
    set.add(target.id)
    return set
  }, [folders, target])

  const nodes = useMemo(
    () => buildSpaceTree({ folders, documents: [] as TreeDocument[] }).folders,
    [folders],
  )

  const move = useMutation({
    mutationFn: async () => {
      if (!target) return
      if (target.kind === 'document') {
        await moveDocument(api, target.id, { folderId: dest })
      } else {
        await moveFolder(api, target.id, { parentFolderId: dest })
      }
    },
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: spaceTreeKey(spaceId) })
      if (target) {
        void qc.invalidateQueries({
          queryKey: [target.kind === 'document' ? 'document' : 'folder', target.id],
        })
        void qc.invalidateQueries({ queryKey: ['documents'] })
        onMoved?.(target, dest)
      }
      onOpenChange(false)
    },
    onError: (e) => setError(apiErrorMessage(e, 'Déplacement impossible')),
  })

  const unchanged = !target || dest === target.currentParentId
  const noun = target?.kind === 'folder' ? 'le dossier' : 'le document'

  function renderNodes(list: FolderTreeNode[], depth: number) {
    return list.map((n) => {
      const disabled = blocked.has(n.folder.id)
      const selected = dest === n.folder.id
      return (
        <li key={n.folder.id}>
          <DestinationRow
            label={n.folder.name}
            depth={depth}
            selected={selected}
            disabled={disabled}
            onSelect={() => setDest(n.folder.id)}
          />
          {n.folders.length > 0 && <ul>{renderNodes(n.folders, depth + 1)}</ul>}
        </li>
      )
    })
  }

  return (
    <Dialog.Root open={open} onOpenChange={onOpenChange}>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-40 bg-[rgba(14,14,16,0.45)]" />
        <Dialog.Content className="fixed left-1/2 top-[16vh] z-50 w-[460px] max-w-[calc(100vw-32px)] -translate-x-1/2 rounded-2xl bg-white p-7 shadow-[0_24px_60px_rgba(14,14,16,0.28)]">
          <div className="mb-1.5 flex items-start justify-between">
            <Dialog.Title className="m-0 font-display text-2xl font-normal text-socle-ink">
              Déplacer
            </Dialog.Title>
            <Dialog.Close
              aria-label="Fermer"
              className="flex h-7 w-7 items-center justify-center rounded-[7px] text-socle-slate hover:bg-[#F5F5F7]"
            >
              <svg
                width="13"
                height="13"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                strokeWidth="2"
                strokeLinecap="round"
                aria-hidden
              >
                <line x1="18" y1="6" x2="6" y2="18" />
                <line x1="6" y1="6" x2="18" y2="18" />
              </svg>
            </Dialog.Close>
          </div>
          <Dialog.Description className="mb-4 text-[13px] text-socle-muted">
            Choisissez la destination pour {noun}{' '}
            <span className="font-medium text-socle-ink">« {target?.name} »</span>.
          </Dialog.Description>

          <div className="section-label mb-2">Destination</div>
          <div
            role="radiogroup"
            aria-label="Destination"
            className="max-h-72 overflow-y-auto rounded-lg border border-socle-line p-1.5"
          >
            <DestinationRow
              label={`${spaceName || 'Espace'} (racine)`}
              depth={0}
              selected={dest === null}
              onSelect={() => setDest(null)}
              root
            />
            <ul>{renderNodes(nodes, 1)}</ul>
          </div>

          {error && (
            <p className="mt-3 text-sm text-socle-danger" role="alert">
              {error}
            </p>
          )}

          <div className="mt-5 flex items-center justify-end gap-2">
            <Dialog.Close className="btn-ghost" type="button">
              Annuler
            </Dialog.Close>
            <button
              type="button"
              className="btn-primary"
              disabled={unchanged || move.isPending}
              onClick={() => move.mutate()}
            >
              {move.isPending ? 'Déplacement…' : 'Déplacer ici'}
            </button>
          </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  )
}

function DestinationRow({
  label,
  depth,
  selected,
  disabled,
  root,
  onSelect,
}: {
  label: string
  depth: number
  selected: boolean
  disabled?: boolean
  root?: boolean
  onSelect: () => void
}) {
  return (
    <button
      type="button"
      role="radio"
      aria-checked={selected}
      disabled={disabled}
      onClick={onSelect}
      style={{ paddingLeft: 10 + depth * 16 }}
      className={`flex w-full items-center gap-2 rounded-[7px] py-[7px] pr-2.5 text-left text-[13.5px] disabled:cursor-not-allowed disabled:opacity-40 ${
        selected
          ? 'bg-socle-mist font-semibold text-socle-accent'
          : 'text-[#4B4B52] hover:bg-[#F5F5F7]'
      }`}
    >
      <span
        aria-hidden
        className={`h-[9px] w-[9px] shrink-0 ${root ? 'rounded-[3px] bg-socle-accent' : 'rounded-[3px] border border-socle-faint'}`}
      />
      <span className="truncate">{label}</span>
    </button>
  )
}
