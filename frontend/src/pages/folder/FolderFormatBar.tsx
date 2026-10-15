// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { useEffect, useRef, useState, type MouseEvent } from 'react'
import type { Editor } from '@tiptap/react'
import { InsertPanel, insertHyperlink, type ToolbarAttachments } from '../document/EditToolbar'
import '../document/document-edit.css'

type Props = {
  editor: Editor
  readOnly: boolean
  attachments?: ToolbarAttachments
}

function stop(e: MouseEvent) {
  e.preventDefault()
}

/** Barre compacte de la page de dossier (FolderProcedures.dc.html), mêmes commandes TipTap. */
export function FolderFormatBar({ editor, readOnly, attachments }: Props) {
  const [insertOpen, setInsertOpen] = useState(false)
  const insertRef = useRef<HTMLSpanElement>(null)
  useEffect(() => {
    if (!insertOpen) return
    const onDoc = (e: globalThis.MouseEvent) => {
      if (insertRef.current && !insertRef.current.contains(e.target as Node)) setInsertOpen(false)
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setInsertOpen(false)
    }
    document.addEventListener('mousedown', onDoc)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onDoc)
      document.removeEventListener('keydown', onKey)
    }
  }, [insertOpen])
  const run = (fn: (ed: Editor) => void) => () => {
    if (readOnly) return
    fn(editor)
  }

  return (
    <div
      className="fp-toolbar"
      role="toolbar"
      aria-label="Mise en forme"
      data-mock-id="fp-toolbar"
      data-testid="folder-format-bar"
    >
      <button
        type="button"
        className="fp-tool fp-tool-b"
        aria-label="Gras"
        disabled={readOnly}
        onMouseDown={stop}
        onClick={run((ed) => ed.chain().focus().toggleBold().run())}
      >
        B
      </button>
      <button
        type="button"
        className="fp-tool fp-tool-i"
        aria-label="Italique"
        disabled={readOnly}
        onMouseDown={stop}
        onClick={run((ed) => ed.chain().focus().toggleItalic().run())}
      >
        I
      </button>
      <button
        type="button"
        className="fp-tool fp-tool-u"
        aria-label="Souligné"
        disabled={readOnly}
        onMouseDown={stop}
        onClick={run((ed) => ed.chain().focus().toggleUnderline().run())}
      >
        U
      </button>
      <span className="fp-tool-sep" aria-hidden />
      <button
        type="button"
        className="fp-tool fp-tool-icon"
        aria-label="Insérer un lien"
        disabled={readOnly}
        onMouseDown={stop}
        onClick={run((ed) => insertHyperlink(ed))}
      >
        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="#43434A" strokeWidth="2" strokeLinecap="round" aria-hidden>
          <path d="M10 13a5 5 0 0 0 7 0l3-3a5 5 0 0 0-7-7l-1 1" />
          <path d="M14 11a5 5 0 0 0-7 0l-3 3a5 5 0 0 0 7 7l1-1" />
        </svg>
      </button>
      <button
        type="button"
        className="fp-tool fp-tool-icon"
        aria-label="Liste à puces"
        disabled={readOnly}
        onMouseDown={stop}
        onClick={run((ed) => ed.chain().focus().toggleBulletList().run())}
      >
        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="#43434A" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
          <line x1="9" y1="6" x2="21" y2="6" />
          <line x1="9" y1="12" x2="21" y2="12" />
          <line x1="9" y1="18" x2="21" y2="18" />
          <circle cx="4" cy="6" r="1.3" fill="#43434A" stroke="none" />
          <circle cx="4" cy="12" r="1.3" fill="#43434A" stroke="none" />
          <circle cx="4" cy="18" r="1.3" fill="#43434A" stroke="none" />
        </svg>
      </button>
      <span className="fp-insert" ref={insertRef}>
        <button
          type="button"
          className="fp-tool fp-tool-insert"
          aria-label="Insérer un embed"
          aria-haspopup="dialog"
          aria-expanded={insertOpen}
          disabled={readOnly}
          data-testid="folder-insert-btn"
          onMouseDown={stop}
          onClick={() => !readOnly && setInsertOpen((v) => !v)}
        >
          <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="#43434A" strokeWidth="2.4" strokeLinecap="round" aria-hidden>
            <line x1="12" y1="5" x2="12" y2="19" />
            <line x1="5" y1="12" x2="19" y2="12" />
          </svg>
          Insérer
        </button>
        {insertOpen && (
          <InsertPanel editor={editor} attachments={attachments} onDone={() => setInsertOpen(false)} />
        )}
      </span>
    </div>
  )
}
