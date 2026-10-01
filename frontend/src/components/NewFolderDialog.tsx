// SPDX-License-Identifier: AGPL-3.0-or-later
import { FormEvent, useEffect, useMemo, useState } from 'react'
import * as Dialog from '@radix-ui/react-dialog'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { api } from '../lib/api'
import { apiErrorMessage } from '../lib/approvals'
import {
  createFolder,
  flattenFolderOptions,
  spaceTreeKey,
  type Folder,
  type TreeFolder,
} from '../lib/folders'

type Props = {
  open: boolean
  onOpenChange: (open: boolean) => void
  spaceId: string
  spaceName: string
  folders: TreeFolder[]
  /** Emplacement présélectionné (null/absent = racine de l'espace). */
  defaultParentFolderId?: string | null
  onCreated?: (folder: Folder) => void
}

/** Maquette NewFolder : nom + emplacement (racine ou dossier existant). */
export function NewFolderDialog({
  open,
  onOpenChange,
  spaceId,
  spaceName,
  folders,
  defaultParentFolderId = null,
  onCreated,
}: Props) {
  const qc = useQueryClient()
  const [name, setName] = useState('')
  const [parentId, setParentId] = useState<string>(defaultParentFolderId ?? '')
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (open) {
      setName('')
      setError(null)
      setParentId(defaultParentFolderId ?? '')
    }
  }, [open, defaultParentFolderId])

  const options = useMemo(() => flattenFolderOptions({ folders, documents: [] }), [folders])

  const create = useMutation({
    mutationFn: () =>
      createFolder(api, { spaceId, parentFolderId: parentId || null, name: name.trim() }),
    onSuccess: (folder) => {
      void qc.invalidateQueries({ queryKey: spaceTreeKey(spaceId) })
      onOpenChange(false)
      onCreated?.(folder)
    },
    onError: (e) => setError(apiErrorMessage(e, 'Création du dossier impossible')),
  })

  function onSubmit(e: FormEvent) {
    e.preventDefault()
    if (!name.trim() || create.isPending) return
    setError(null)
    create.mutate()
  }

  return (
    <Dialog.Root open={open} onOpenChange={onOpenChange}>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-40 bg-[rgba(14,14,16,0.45)]" />
        <Dialog.Content className="fixed left-1/2 top-[20vh] z-50 w-[460px] max-w-[calc(100vw-32px)] -translate-x-1/2 rounded-2xl bg-white p-7 shadow-[0_24px_60px_rgba(14,14,16,0.28)]">
          <div className="mb-1.5 flex items-start justify-between">
            <Dialog.Title className="m-0 font-display text-2xl font-normal text-socle-ink">
              Nouveau dossier
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
          <Dialog.Description className="mb-5 text-[13px] text-socle-muted">
            Regroupe des documents dans l&apos;arborescence de l&apos;espace. Un dossier peut
            lui-même contenir des sous-dossiers.
          </Dialog.Description>

          <form onSubmit={onSubmit}>
            <label className="mb-[18px] block">
              <span className="section-label mb-2 block">Nom du dossier</span>
              <input
                className="field-input"
                value={name}
                onChange={(e) => setName(e.target.value)}
                placeholder="Ex. Continuité & reprise"
                maxLength={200}
                autoFocus
                required
              />
            </label>

            <label className="mb-[22px] block">
              <span className="section-label mb-2 block">Emplacement</span>
              <select
                className="field-input"
                value={parentId}
                onChange={(e) => setParentId(e.target.value)}
                data-testid="new-folder-location"
              >
                <option value="">{spaceName || 'Espace'} (racine)</option>
                {options.map((o) => (
                  <option key={o.id} value={o.id ?? ''}>
                    {'\u00A0\u00A0'.repeat(o.depth - 1)}
                    {o.name}
                  </option>
                ))}
              </select>
              <span className="mt-1.5 block text-[11.5px] text-socle-faint">
                Choisissez la racine de l&apos;espace ou un dossier existant pour créer un
                sous-dossier.
              </span>
            </label>

            {error && (
              <p className="mb-3 text-sm text-socle-danger" role="alert">
                {error}
              </p>
            )}

            <div className="flex items-center justify-end gap-2">
              <Dialog.Close className="btn-ghost" type="button">
                Annuler
              </Dialog.Close>
              <button
                type="submit"
                className="btn-primary"
                disabled={create.isPending || !name.trim()}
              >
                {create.isPending ? 'Création…' : 'Créer le dossier'}
              </button>
            </div>
          </form>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  )
}
