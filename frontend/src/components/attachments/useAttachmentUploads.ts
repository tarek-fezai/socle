// SPDX-License-Identifier: AGPL-3.0-or-later
import { useCallback, useEffect, useRef, useState } from 'react'
import type { Editor } from '@tiptap/react'
import { apiErrorMessage } from '../../lib/apiError'
import {
  ATTACHMENT_NODE_TYPE,
  IMAGE_NODE_TYPE,
  attachmentNodeAttrs,
  isImageMediaType,
  isVideoMediaType,
  uploadAttachment,
  type AttachmentInfo,
} from '../../lib/attachments'
import { VIDEO_NODE_TYPE } from '../rich-blocks/richBlockUtils'

export type UploadItem = {
  key: string
  filename: string
  /** 0..1 */
  progress: number
  status: 'uploading' | 'error'
  error?: string
}

/**
 * `image` : bloc image si le serveur confirme un type image/*, sinon fichier joint. `file` : toujours un
 * fichier joint. `video` : toujours un bloc vidéo (mp4 / webm uniquement).
 */
export type UploadKind = 'image' | 'file' | 'video'

export const VIDEO_UPLOAD_ERROR = 'Seules les vidéos MP4 et WebM sont acceptées.'

let seq = 0

const BLOCK_ATOM_TYPES = new Set([IMAGE_NODE_TYPE, ATTACHMENT_NODE_TYPE, VIDEO_NODE_TYPE])

function insertNode(editor: Editor, info: AttachmentInfo, kind: UploadKind, pos?: number) {
  const asImage = kind === 'image' && isImageMediaType(info.mediaType)
  const asVideo = kind === 'video'
  const node = {
    type: asVideo ? VIDEO_NODE_TYPE : asImage ? IMAGE_NODE_TYPE : ATTACHMENT_NODE_TYPE,
    attrs: { ...attachmentNodeAttrs(info), ...(asImage ? { alt: info.filename } : {}) },
  }
  const chain = editor.chain().focus()
  if (pos == null) chain.insertContent(node)
  else chain.insertContentAt(Math.min(Math.max(0, pos), editor.state.doc.content.size), node)
  chain.run()
  // Un bloc atomique en fin de document : prévoir un paragraphe pour continuer à écrire.
  const last = editor.state.doc.lastChild
  if (last && BLOCK_ATOM_TYPES.has(last.type.name)) {
    editor.commands.insertContentAt(editor.state.doc.content.size, { type: 'paragraph' })
  }
}

/**
 * Envoi de pièces jointes d'un document vers l'API, avec progression et erreurs serveur réelles,
 * puis insertion du bloc `image` / `attachment` dans l'éditeur.
 */
export function useAttachmentUploads(documentId: string | undefined, getEditor: () => Editor | null) {
  const [items, setItems] = useState<UploadItem[]>([])
  const controllers = useRef(new Set<AbortController>())

  useEffect(() => {
    const set = controllers.current
    return () => {
      set.forEach((c) => c.abort())
      set.clear()
    }
  }, [])

  const patch = useCallback((key: string, p: Partial<UploadItem>) => {
    setItems((prev) => prev.map((it) => (it.key === key ? { ...it, ...p } : it)))
  }, [])

  const dismiss = useCallback((key: string) => {
    setItems((prev) => prev.filter((it) => it.key !== key))
  }, [])

  const upload = useCallback(
    async (files: File[], opts: { kind?: UploadKind; pos?: number } = {}) => {
      if (!documentId) return
      let pos = opts.pos
      for (const file of files) {
        const key = `up-${++seq}`
        const kind: UploadKind =
          opts.kind ?? (isImageMediaType(file.type) ? 'image' : isVideoMediaType(file.type) ? 'video' : 'file')
        if (kind === 'video' && !isVideoMediaType(file.type)) {
          setItems((prev) => [
            ...prev,
            { key, filename: file.name, progress: 0, status: 'error', error: VIDEO_UPLOAD_ERROR },
          ])
          continue
        }
        setItems((prev) => [...prev, { key, filename: file.name, progress: 0, status: 'uploading' }])
        const ctrl = new AbortController()
        controllers.current.add(ctrl)
        try {
          const info = await uploadAttachment(documentId, file, {
            signal: ctrl.signal,
            onProgress: (f) => patch(key, { progress: f }),
          })
          const editor = getEditor()
          if (editor && !editor.isDestroyed) {
            insertNode(editor, info, kind, pos)
            // Fichiers suivants : à la suite du premier (le curseur a avancé).
            pos = undefined
          }
          dismiss(key)
        } catch (e) {
          if (ctrl.signal.aborted) {
            dismiss(key)
            return
          }
          patch(key, {
            status: 'error',
            error: apiErrorMessage(e, `Impossible d’envoyer « ${file.name} ».`),
          })
        } finally {
          controllers.current.delete(ctrl)
        }
      }
    },
    [documentId, getEditor, patch, dismiss],
  )

  return { items, upload, dismiss }
}
