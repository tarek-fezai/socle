// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { FormEvent, useEffect, useState } from 'react'
import * as Dialog from '@radix-ui/react-dialog'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { api } from '../lib/api'
import { apiErrorMessage } from '../lib/approvals'
import {
  needsVisibilityWarning,
  saveDocumentAsTemplate,
  visibilityLabel,
  type SaveAsTemplateResponse,
} from '../lib/templates'

type Props = {
  open: boolean
  onOpenChange: (open: boolean) => void
  documentId: string
  spaceId: string
  documentTitle: string
  /** organisation | space | restricted */
  visibility?: string | null
}

/** Dialogue « Enregistrer comme modèle » (portée globale ou espace). */
export function SaveAsTemplateDialog({
  open,
  onOpenChange,
  documentId,
  spaceId,
  documentTitle,
  visibility,
}: Props) {
  const qc = useQueryClient()
  const [scope, setScope] = useState<'global' | 'space'>('space')
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [ack, setAck] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [result, setResult] = useState<SaveAsTemplateResponse | null>(null)

  const warn = needsVisibilityWarning(visibility)
  const resultWarnings = Array.from(new Set(result?.warnings ?? []))

  useEffect(() => {
    if (!open) return
    setScope('space')
    setName(documentTitle)
    setDescription('')
    setAck(false)
    setError(null)
    setResult(null)
  }, [open, documentTitle])

  const save = useMutation({
    mutationFn: () =>
      saveDocumentAsTemplate(api, documentId, {
        scope,
        spaceId: scope === 'space' ? spaceId : undefined,
        name: name.trim(),
        description: description.trim() || null,
      }),
    onSuccess: (data) => {
      setError(null)
      setResult(data)
      void qc.invalidateQueries({ queryKey: ['templates'] })
    },
    onError: (e) => setError(apiErrorMessage(e, 'Enregistrement du modèle impossible')),
  })

  const canSubmit = name.trim().length > 0 && (!warn || ack) && !save.isPending

  function onSubmit(e: FormEvent) {
    e.preventDefault()
    if (canSubmit) save.mutate()
  }

  return (
    <Dialog.Root open={open} onOpenChange={onOpenChange}>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-40 bg-[rgba(14,14,16,0.45)]" />
        <Dialog.Content className="fixed left-1/2 top-[12vh] z-50 w-[480px] max-w-[calc(100vw-32px)] -translate-x-1/2 rounded-2xl bg-white p-7 shadow-[0_24px_60px_rgba(14,14,16,0.28)]">
          <Dialog.Title className="m-0 font-display text-2xl font-normal text-socle-ink">
            Enregistrer comme modèle
          </Dialog.Title>
          <Dialog.Description className="mb-4 mt-1.5 text-[13px] text-socle-muted">
            Le contenu de « {documentTitle || 'Sans titre'} » servira de point de départ aux
            prochains documents.
          </Dialog.Description>

          {result ? (
            <div className="space-y-4">
              <p className="rounded-lg bg-[#F1F8F3] px-3 py-2.5 text-sm text-socle-success" role="status">
                Modèle enregistré.
              </p>
              {resultWarnings.length > 0 && (
                <ul
                  className="list-disc space-y-1 rounded-lg border border-[#E8D9A8] bg-[#FBF3E4] py-2.5 pl-8 pr-3 text-sm text-socle-warn"
                  role="alert"
                  data-testid="visibility-warning-result"
                >
                  {resultWarnings.map((w) => (
                    <li key={w}>{w}</li>
                  ))}
                </ul>
              )}
              <div className="flex items-center justify-end gap-2">
                <Link to="/admin/templates" className="btn-ghost" onClick={() => onOpenChange(false)}>
                  Gérer les modèles
                </Link>
                <Dialog.Close className="btn-primary" type="button">
                  Fermer
                </Dialog.Close>
              </div>
            </div>
          ) : (
            <form onSubmit={onSubmit} className="space-y-4">
              <fieldset>
                <legend className="section-label mb-2">Portée</legend>
                <div className="flex gap-2" role="radiogroup" aria-label="Portée du modèle">
                  {(
                    [
                      ['space', 'Cet espace'],
                      ['global', 'Toute l’organisation'],
                    ] as const
                  ).map(([value, label]) => (
                    <label
                      key={value}
                      className={`flex flex-1 cursor-pointer items-center gap-2 rounded-lg border px-3 py-2 text-sm ${
                        scope === value
                          ? 'border-socle-accent bg-socle-mist font-semibold text-socle-accent'
                          : 'border-socle-line text-[#4B4B52] hover:bg-[#F5F5F7]'
                      }`}
                    >
                      <input
                        type="radio"
                        name="template-scope"
                        value={value}
                        checked={scope === value}
                        onChange={() => setScope(value)}
                        className="accent-[#3730E0]"
                      />
                      {label}
                    </label>
                  ))}
                </div>
              </fieldset>

              <label className="block text-sm">
                <span className="section-label">Nom du modèle</span>
                <input
                  className="field-input mt-1.5"
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  required
                  aria-label="Nom du modèle"
                />
              </label>

              <label className="block text-sm">
                <span className="section-label">
                  Description <span className="font-normal normal-case text-socle-faint">(optionnel)</span>
                </span>
                <textarea
                  className="field-input mt-1.5 min-h-[72px]"
                  value={description}
                  onChange={(e) => setDescription(e.target.value)}
                  aria-label="Description du modèle"
                />
              </label>

              {warn && (
                <div
                  className="rounded-lg border border-[#E8D9A8] bg-[#FBF3E4] px-3 py-2.5 text-[13px] text-socle-warn"
                  role="alert"
                  data-testid="visibility-warning"
                >
                  <p className="font-semibold">
                    Visibilité du document : {visibilityLabel(visibility)}
                  </p>
                  <p className="mt-1 text-socle-slate">
                    Ce document n’est pas visible de toute l’organisation. Une fois enregistré comme
                    modèle, son contenu pourra être consulté par tous ceux qui peuvent utiliser le
                    modèle.
                  </p>
                  <label className="mt-2 flex items-start gap-2 text-socle-ink">
                    <input
                      type="checkbox"
                      checked={ack}
                      onChange={(e) => setAck(e.target.checked)}
                      className="mt-0.5 accent-[#3730E0]"
                    />
                    <span>Je comprends et je souhaite continuer</span>
                  </label>
                </div>
              )}

              {error && (
                <p className="text-sm text-socle-danger" role="alert">
                  {error}
                </p>
              )}

              <div className="flex items-center justify-end gap-2">
                <Dialog.Close className="btn-ghost" type="button">
                  Annuler
                </Dialog.Close>
                <button type="submit" className="btn-primary" disabled={!canSubmit}>
                  {save.isPending ? 'Enregistrement…' : 'Enregistrer le modèle'}
                </button>
              </div>
            </form>
          )}
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  )
}
