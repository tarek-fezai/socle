// SPDX-License-Identifier: AGPL-3.0-or-later
import { useEditor, EditorContent } from '@tiptap/react'
import StarterKit from '@tiptap/starter-kit'
import { useEffect } from 'react'

type Props = {
  content: Record<string, unknown>
  onChange: (json: Record<string, unknown>) => void
  editable?: boolean
}

export function DocumentEditor({ content, onChange, editable = true }: Props) {
  const editor = useEditor({
    extensions: [StarterKit],
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
      <EditorContent editor={editor} className="px-4 py-3" />
    </div>
  )
}

function ToolbarButton({
  active,
  onClick,
  label,
}: {
  active: boolean
  onClick: () => void
  label: string
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={`rounded px-2.5 py-1 text-xs font-medium transition ${
        active
          ? 'bg-socle-accent text-white'
          : 'bg-socle-mist text-socle-slate hover:bg-socle-slate/10'
      }`}
    >
      {label}
    </button>
  )
}
