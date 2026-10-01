// SPDX-License-Identifier: AGPL-3.0-or-later
import { Link, useParams } from 'react-router-dom'
import { useMutation } from '@tanstack/react-query'
import { useState } from 'react'
import { api } from '../lib/api'
import { downloadExport, type ExportScope } from '../lib/export'

type Props = {
  scope: ExportScope
  /** Libellé de la portée (document / dossier / tag). */
  scopeLabel: string
  backTo: string
  backLabel: string
  fallbackFilename: string
}

function ExportShell({ scope, scopeLabel, backTo, backLabel, fallbackFilename }: Props) {
  const { id = '' } = useParams()
  const [error, setError] = useState<string | null>(null)

  const download = useMutation({
    mutationFn: () => downloadExport(api, scope, id, fallbackFilename),
    onSuccess: () => setError(null),
    onError: () => setError('Export impossible — accès refusé ou ressource introuvable.'),
  })

  return (
    <main className="page-shell max-w-[720px]">
      <div className="breadcrumb mb-6">
        <Link to={backTo}>{backLabel}</Link>
        <span className="text-[#DEDEE1]">→</span>
        <span className="font-medium text-socle-ink">Exporter</span>
      </div>

      <h1 className="serif-title">Exporter en PDF</h1>
      <p className="mt-1.5 text-sm text-socle-muted">
        Portée : {scopeLabel}. Les blocs transclus et les documents de collection sont filtrés
        par vos droits de lecture (même règles que la vue web).
      </p>

      <div className="mt-8 rounded-xl border border-socle-line bg-white px-5 py-4">
        <div className="text-[11px] font-semibold uppercase tracking-wide text-socle-muted">
          Format
        </div>
        <p className="mt-1 font-semibold text-socle-ink">PDF</p>
        <p className="mt-3 text-sm text-socle-muted">
          Contenu non accessible (transclusion ou document hors périmètre) : indicateur visible,
          jamais le contenu confidentiel.
        </p>
      </div>

      {error ? <p className="mt-4 text-sm text-socle-danger">{error}</p> : null}

      <div className="mt-8 flex flex-wrap gap-3">
        <button
          type="button"
          data-testid="export-download"
          disabled={!id || download.isPending}
          onClick={() => download.mutate()}
          className="rounded-lg bg-socle-accent px-4 py-2.5 text-sm font-semibold text-white disabled:opacity-50"
        >
          {download.isPending ? 'Génération…' : 'Télécharger le PDF'}
        </button>
        <Link
          to={backTo}
          className="rounded-lg border border-socle-line px-4 py-2.5 text-sm font-semibold text-socle-ink"
        >
          Fermer
        </Link>
      </div>
    </main>
  )
}

export function DocumentExportPage() {
  const { id = '' } = useParams()
  return (
    <ExportShell
      scope="document"
      scopeLabel="ce document"
      backTo={`/docs/${id}`}
      backLabel="Document"
      fallbackFilename="document.pdf"
    />
  )
}

export function FolderExportPage() {
  const { id = '' } = useParams()
  return (
    <ExportShell
      scope="folder"
      scopeLabel="ce dossier (documents lisibles uniquement)"
      backTo={`/folders/${id}/access`}
      backLabel="Dossier"
      fallbackFilename="dossier.pdf"
    />
  )
}

export function TagExportPage() {
  return (
    <ExportShell
      scope="tag"
      scopeLabel="ce tag (documents lisibles uniquement)"
      backTo="/search"
      backLabel="Recherche"
      fallbackFilename="tag.pdf"
    />
  )
}
