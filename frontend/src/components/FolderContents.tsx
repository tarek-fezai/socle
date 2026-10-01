// SPDX-License-Identifier: AGPL-3.0-or-later
import { Link } from 'react-router-dom'
import { documentHref, folderHref, type TreeDocument, type TreeFolder } from '../lib/folders'
import type { MoveTarget } from './MoveDialog'

type Props = {
  folders: TreeFolder[]
  documents: TreeDocument[]
  /** Emplacement courant des éléments listés (null = racine). */
  parentId: string | null
  onMove: (target: MoveTarget) => void
  emptyText: string
}

function plural(n: number, one: string, many: string) {
  return `${n} ${n === 1 ? one : many}`
}

/** Liste sous-dossiers + documents d'un emplacement, avec action « Déplacer ». */
export function FolderContents({ folders, documents, parentId, onMove, emptyText }: Props) {
  if (folders.length === 0 && documents.length === 0) {
    return (
      <p className="rounded-xl border border-dashed border-[#DEDEE1] px-4 py-10 text-center text-sm text-socle-muted">
        {emptyText}
      </p>
    )
  }

  const moveBtn = (kind: MoveTarget['kind'], id: string, name: string) => (
    <button
      type="button"
      className="shrink-0 text-xs font-semibold text-socle-accent hover:underline"
      aria-label={`Déplacer ${name}`}
      onClick={() => onMove({ kind, id, name, currentParentId: parentId })}
    >
      Déplacer
    </button>
  )

  return (
    <div className="flex flex-col gap-2.5">
      {folders.length > 0 && (
        <ul className="space-y-2" aria-label="Sous-dossiers">
          {folders.map((f) => (
            <li
              key={f.id}
              className="flex items-center justify-between gap-3 rounded-xl border border-socle-line bg-white px-4 py-3 transition hover:border-[#C7C6F5] hover:bg-socle-mist/40"
            >
              <Link to={folderHref(f.id)} className="min-w-0 flex-1">
                <span className="block truncate font-semibold text-socle-ink">{f.name}</span>
                <span className="text-xs text-socle-muted">
                  {plural(f.documentCount, 'document', 'documents')} ·{' '}
                  {plural(f.folderCount, 'sous-dossier', 'sous-dossiers')}
                </span>
              </Link>
              {moveBtn('folder', f.id, f.name)}
            </li>
          ))}
        </ul>
      )}
      {documents.length > 0 && (
        <ul className="space-y-2" aria-label="Documents">
          {documents.map((d) => (
            <li
              key={d.id}
              className="flex items-center justify-between gap-3 rounded-xl border border-socle-line bg-white px-4 py-3 transition hover:border-[#C7C6F5] hover:bg-socle-mist/40"
            >
              <Link
                to={documentHref(d.id)}
                className="flex min-w-0 flex-1 items-center justify-between gap-3"
              >
                <span className="truncate font-medium text-socle-ink">
                  {d.title || 'Sans titre'}
                </span>
                <span className="shrink-0 text-xs text-socle-muted">{d.status}</span>
              </Link>
              {moveBtn('document', d.id, d.title || 'Sans titre')}
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
