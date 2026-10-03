// SPDX-License-Identifier: AGPL-3.0-or-later
import { useEditor, EditorContent, type Editor } from '@tiptap/react'
import StarterKit from '@tiptap/starter-kit'
import { useEffect, useRef, useState, type ReactNode } from 'react'
import { Placeholder } from '../lib/placeholderExtension'
import { DEFAULT_PLACEHOLDER_HINT, PLACEHOLDER_NODE_TYPE, TEMPLATE_VARIABLES } from '../lib/templates'
import { EditToolbar } from './document/EditToolbar'

type Props = {
  content: Record<string, unknown>
  onChange: (json: Record<string, unknown>) => void
  editable?: boolean
  /** Mode édition de modèle : insertion de zones à compléter et de variables {{date}}… */
  templateTools?: boolean
  /**
   * `classic` : cadre + mini barre (modèles). `document` : écran Modifier (Edit.dc.html) —
   * barre d'outils complète, `titleSlot` entre la barre et le corps, aucun cadre.
   */
  variant?: 'classic' | 'document'
  /** Variante `document` : contenu rendu entre la barre d'outils et le corps (titre). */
  titleSlot?: ReactNode
  /** Instance TipTap disponible (aller au paragraphe, focus…) ; `null` au démontage. */
  onEditorReady?: (editor: Editor | null) => void
  /** Variante `document` : masque l'éditeur (aperçu) sans le démonter. */
  hidden?: boolean
}

export function DocumentEditor({
  content,
  onChange,
  editable = true,
  templateTools = false,
  variant = 'classic',
  titleSlot,
  onEditorReady,
  hidden = false,
}: Props) {
  const [hint, setHint] = useState('')
  const lastEmitted = useRef<Record<string, unknown> | null>(null)
  const initialContent = useRef<Record<string, unknown>>(content)
  const documentVariant = variant === 'document'
  const editor = useEditor({
    extensions: [StarterKit, Placeholder],
    content,
    editable,
    immediatelyRender: false,
    editorProps: {
      attributes: {
        class: documentVariant
          ? 'edit-prose focus:outline-none'
          : 'prose prose-slate max-w-none min-h-[280px] focus:outline-none px-1 py-2',
        ...(documentVariant ? { 'aria-label': 'Contenu du document', 'data-testid': 'edit-prosemirror' } : {}),
      },
    },
    onUpdate: ({ editor: ed }) => {
      const json = ed.getJSON() as Record<string, unknown>
      lastEmitted.current = json
      onChange(json)
    },
  })

  useEffect(() => {
    onEditorReady?.(editor ?? null)
    return () => onEditorReady?.(null)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [editor])

  useEffect(() => {
    if (!editor) return
    // Pas de `update` : changer l'état éditable n'est pas une modification du contenu
    // (sinon l'enregistrement automatique créerait une version à l'ouverture).
    if (editor.isEditable !== editable) editor.setEditable(editable, false)
  }, [editor, editable])

  useEffect(() => {
    if (!editor) return
    // Contenu issu de notre propre saisie ou fourni à la création : rien à resynchroniser.
    if (lastEmitted.current === content || initialContent.current === content) return
    initialContent.current = content
    const current = JSON.stringify(editor.getJSON())
    const next = JSON.stringify(content)
    if (current !== next) {
      // Remplacement externe : pas d'événement `update` (ce n'est pas une saisie).
      editor.commands.setContent(content, false)
    }
  }, [content, editor])

  if (!editor) return null

  if (documentVariant) {
    return (
      <div className="edit-editor" hidden={hidden} data-testid="edit-editor">
        <EditToolbar editor={editor} readOnly={!editable} />
        {titleSlot}
        <EditorContent editor={editor} className="edit-editor-content" />
      </div>
    )
  }

  function insertPlaceholder() {
    editor
      ?.chain()
      .focus()
      // Paragraphe vide à la suite : le curseur ne reste pas sélectionné sur le nœud atomique.
      .insertContent([
        {
          type: PLACEHOLDER_NODE_TYPE,
          attrs: { hint: hint.trim() || DEFAULT_PLACEHOLDER_HINT },
        },
        { type: 'paragraph' },
      ])
      .run()
    setHint('')
  }

  return (
    <div className="rounded-lg border border-socle-slate/15 bg-white/80 shadow-sm">
      <div className="flex flex-wrap gap-1 border-b border-socle-slate/10 px-2 py-2">
        <ToolbarButton
          active={editor.isActive('bold')}
          onClick={() => editor.chain().focus().toggleBold().run()}
          label="Gras"
        />
        <ToolbarButton
          active={editor.isActive('italic')}
          onClick={() => editor.chain().focus().toggleItalic().run()}
          label="Italique"
        />
        <ToolbarButton
          active={editor.isActive('heading', { level: 2 })}
          onClick={() => editor.chain().focus().toggleHeading({ level: 2 }).run()}
          label="H2"
        />
        <ToolbarButton
          active={editor.isActive('bulletList')}
          onClick={() => editor.chain().focus().toggleBulletList().run()}
          label="Liste"
        />
      </div>
      {templateTools && (
        <div
          className="flex flex-wrap items-center gap-1.5 border-b border-socle-slate/10 bg-socle-soft px-2 py-2"
          data-testid="template-tools"
        >
          <input
            value={hint}
            onChange={(e) => setHint(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') {
                e.preventDefault()
                insertPlaceholder()
              }
            }}
            aria-label="Indice de la zone à compléter"
            placeholder="Indice (ex. Objet de la politique)"
            className="w-56 rounded-md border border-socle-line bg-white px-2 py-1 text-xs outline-none focus:border-[#C7C6F5]"
          />
          <ToolbarButton active={false} onClick={insertPlaceholder} label="Zone à compléter" />
          <span className="mx-1 h-4 w-px bg-socle-line" aria-hidden />
          {TEMPLATE_VARIABLES.map((v) => (
            <ToolbarButton
              key={v}
              active={false}
              mono
              onClick={() => editor.chain().focus().insertContent(v).run()}
              label={v}
            />
          ))}
        </div>
      )}
      <EditorContent editor={editor} className="px-4 py-3" />
    </div>
  )
}

function ToolbarButton({
  active,
  onClick,
  label,
  mono,
}: {
  active: boolean
  onClick: () => void
  label: string
  mono?: boolean
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={`rounded px-2.5 py-1 text-xs font-medium transition ${mono ? 'font-mono' : ''} ${
        active
          ? 'bg-socle-accent text-white'
          : 'bg-socle-mist text-socle-slate hover:bg-socle-slate/10'
      }`}
    >
      {label}
    </button>
  )
}
