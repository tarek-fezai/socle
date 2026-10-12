// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
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

function Check({ on }: { on: boolean }) {
  if (!on) {
    return <span className="h-4 w-4 shrink-0 rounded border-[1.5px] border-[#DEDEE1]" />
  }
  return (
    <span className="flex h-4 w-4 shrink-0 items-center justify-center rounded bg-socle-accent">
      <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="#FFFFFF" strokeWidth="3">
        <polyline points="20 6 9 17 4 12" />
      </svg>
    </span>
  )
}

function ExportShell({ scope, backTo, backLabel, fallbackFilename }: Props) {
  const { id = '' } = useParams()
  const [error, setError] = useState<string | null>(null)

  const download = useMutation({
    mutationFn: () => downloadExport(api, scope, id, fallbackFilename),
    onSuccess: () => setError(null),
    onError: () => setError('Export impossible — accès refusé ou ressource introuvable.'),
  })

  const scopeChip =
    scope === 'folder' ? 'Un dossier' : scope === 'tag' ? 'Un tag' : 'Ce document'

  return (
    <div className="-mx-6 flex h-[calc(100vh-0px)] min-h-[900px] flex-col leading-[normal] md:-mx-0">
      <div className="flex h-[60px] shrink-0 items-center justify-between border-b border-socle-line px-11">
        <div className="flex items-center gap-[7px] text-[13px] text-[#75757C]" data-mock-id="export-crumb">
          <Link to={backTo} className="text-[#9B9BA1]">
            {backLabel}
          </Link>
          <span className="text-[#DEDEE1]">→</span>
          <span className="font-medium text-socle-ink">Exporter</span>
        </div>
        <Link
          to={backTo}
          data-mock-id="export-close"
          className="rounded-[7px] border border-socle-line px-3.5 py-[7px] text-[13px] font-medium leading-[normal] text-[#43434A] hover:bg-[#F5F5F7]"
        >
          Fermer
        </Link>
      </div>

      <div className="flex min-h-0 flex-1">
        <div className="flex-1 bg-[#F5F5F7]" aria-hidden />
        <aside
          className="flex shrink-0 flex-col overflow-hidden border-l border-socle-line"
          style={{ width: 320, boxSizing: 'content-box', padding: '28px 24px' }}
        >
          <div className="mb-2.5 text-[12px] font-semibold uppercase tracking-[0.05em] text-[#9B9BA1]">
            Portée
          </div>
          <div className="mb-6 flex flex-wrap gap-1.5">
            <span className="rounded-[7px] bg-[#F0EFFC] px-2.5 py-1.5 text-[12px] font-semibold text-socle-accent">
              {scopeChip}
            </span>
          </div>

          <div className="mb-2.5 text-[12px] font-semibold uppercase tracking-[0.05em] text-[#9B9BA1]">
            Format
          </div>
          <div className="mb-6 flex flex-col gap-2">
            <div
              data-mock-id="export-format-pdf"
              className="flex items-center justify-between rounded-[9px] border-[1.5px] border-socle-accent bg-[#F0EFFC] px-3.5 py-[11px]"
            >
              <span className="text-[13.5px] font-semibold leading-[normal] text-socle-ink">PDF</span>
              <span className="text-[11px] leading-[normal] text-[#6B6B72]">Mise en page fixe</span>
            </div>
            <div
              data-visual-mask="export-format-word"
              className="flex items-center justify-between rounded-[9px] border border-socle-line px-3.5 py-[11px]"
            >
              <span className="text-[13.5px] font-medium leading-[normal] text-socle-ink">Word</span>
              <span className="text-[11px] leading-[normal] text-[#9B9BA1]">Bientôt</span>
            </div>
            <div
              data-visual-mask="export-format-markdown"
              className="flex items-center justify-between rounded-[9px] border border-socle-line px-3.5 py-[11px]"
            >
              <span className="text-[13.5px] font-medium leading-[normal] text-socle-ink">Markdown</span>
              <span className="text-[11px] leading-[normal] text-[#9B9BA1]">Bientôt</span>
            </div>
          </div>

          <div className="mb-2.5 text-[12px] font-semibold uppercase tracking-[0.05em] text-[#9B9BA1]">
            Contenu inclus
          </div>
          <div className="mb-6 flex flex-col gap-2.5 text-[13px] text-socle-ink">
            <div className="flex items-center gap-[9px]">
              <Check on />
              Schémas et images
            </div>
            <div className="flex items-center gap-[9px]">
              <Check on />
              En-tête et pied de page
            </div>
            <div className="flex items-center gap-[9px] text-[#9B9BA1]">
              <Check on={false} />
              Commentaires non résolus
            </div>
            <div className="flex items-center gap-[9px]">
              <Check on />
              Filigrane « Confidentiel »
            </div>
          </div>

          {error ? <p className="mb-3 text-sm text-socle-danger">{error}</p> : null}

          <div className="mt-auto border-t border-socle-line pt-[18px]">
            <button
              type="button"
              data-testid="export-download"
              data-mock-id="export-download"
              disabled={!id || download.isPending}
              onClick={() => download.mutate()}
              className="block w-full rounded-lg bg-socle-accent py-[11px] text-center text-[14px] font-semibold leading-[normal] text-white hover:bg-[#2C27C7] disabled:opacity-50"
            >
              {download.isPending ? 'Génération…' : 'Télécharger le PDF'}
            </button>
          </div>
        </aside>
      </div>
    </div>
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
