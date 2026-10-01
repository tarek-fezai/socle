// SPDX-License-Identifier: AGPL-3.0-or-later
import { useEditor, EditorContent } from '@tiptap/react'
import StarterKit from '@tiptap/starter-kit'
import { useEffect, useState } from 'react'
import { Placeholder } from '../lib/placeholderExtension'
import { DEFAULT_PLACEHOLDER_HINT, PLACEHOLDER_NODE_TYPE, TEMPLATE_VARIABLES } from '../lib/templates'

type Props = {
  content: Record<string, unknown>
  onChange: (json: Record<string, unknown>) => void
  editable?: boolean
  /** Mode édition de modèle : insertion de zones à compléter et de variables {{date}}… */
  templateTools?: boolean
}

export function DocumentEditor({ content, onChange, editable = true, templateTools = false }: Props) {
  const [hint, setHint] = useState('')
  const editor = useEditor({
    extensions: [StarterKit, Placeholder],
    content,
    editable,
    immediatelyRender: false,
    editorProps: {
      attributes: {
        class:
          'prose prose-slate max-w-none min-h-[280px] focus:outline-none px-1 py-2',
      },
    },
    onUpdate: ({ editor: ed }) => {
      onChange(ed.getJSON() as Record<string, unknown>)
    },
  })

  useEffect(() => {
    if (!editor) return
    const current = JSON.stringify(editor.getJSON())
    const next = JSON.stringify(content)
    if (current !== next) {
      editor.commands.setContent(content)
    }
  }, [content, editor])

  if (!editor) return null

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
