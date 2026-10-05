// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { Node, NodeViewWrapper, ReactNodeViewRenderer, mergeAttributes, type NodeViewProps } from '@tiptap/react'
import { DATE_NODE_TYPE, formatDateFr, parseIsoDate, todayIsoDate } from './richBlockUtils'

function DateView({ node, selected, editor, updateAttributes }: NodeViewProps) {
  const value = typeof node.attrs.value === 'string' ? node.attrs.value : ''
  const editing = selected && editor.isEditable
  return (
    <NodeViewWrapper as="span" className={`doc-date edit-date${selected ? ' is-selected' : ''}`} data-drag-handle>
      {editing ? (
        <input
          type="date"
          className="edit-date-input"
          aria-label="Date"
          value={parseIsoDate(value) ? value : ''}
          onChange={(e) => {
            // Champ vidé : on garde l'ancienne valeur (le serveur exige une date ISO valide).
            if (parseIsoDate(e.target.value)) updateAttributes({ value: e.target.value })
          }}
        />
      ) : (
        formatDateFr(value)
      )}
    </NodeViewWrapper>
  )
}

/**
 * Date en ligne (nœud atomique). JSON : `{ type: 'date', attrs: { value: 'AAAA-MM-JJ' } }`.
 * Affichage : « 15 octobre 2026 ».
 */
export const DateNode = Node.create({
  name: DATE_NODE_TYPE,
  group: 'inline',
  inline: true,
  atom: true,
  selectable: true,
  draggable: true,

  addAttributes() {
    return {
      value: {
        default: todayIsoDate(),
        parseHTML: (el: HTMLElement) => el.getAttribute('data-date') ?? todayIsoDate(),
        renderHTML: (attrs: Record<string, unknown>) => ({ 'data-date': String(attrs.value ?? '') }),
      },
    }
  },

  parseHTML() {
    return [{ tag: 'span[data-date]' }]
  },

  renderHTML({ node, HTMLAttributes }) {
    return ['span', mergeAttributes(HTMLAttributes, { class: 'doc-date' }), formatDateFr(node.attrs.value)]
  },

  addNodeView() {
    return ReactNodeViewRenderer(DateView)
  },
})
