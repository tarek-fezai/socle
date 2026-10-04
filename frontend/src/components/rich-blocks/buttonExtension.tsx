// SPDX-License-Identifier: AGPL-3.0-or-later
import { useState } from 'react'
import { Node, NodeViewWrapper, ReactNodeViewRenderer, mergeAttributes, type NodeViewProps } from '@tiptap/react'
import { RichButton } from './RichBlockViews'
import { BUTTON_NODE_TYPE, DEFAULT_BUTTON_LABEL } from './richBlockUtils'

function nullableStr(name: string, data: string) {
  return {
    default: null as string | null,
    parseHTML: (el: HTMLElement) => el.getAttribute(data),
    renderHTML: (attrs: Record<string, unknown>) =>
      typeof attrs[name] === 'string' && attrs[name] ? { [data]: attrs[name] as string } : {},
  }
}

function ButtonView({ node, selected, editor, updateAttributes }: NodeViewProps) {
  const a = node.attrs as { label?: unknown; href?: unknown; documentId?: unknown }
  const label = typeof a.label === 'string' ? a.label : ''
  const href = typeof a.href === 'string' ? a.href : ''
  const hasDocument = typeof a.documentId === 'string' && a.documentId !== ''
  const editing = selected && editor.isEditable
  // Brouillons de saisie, validés à la sortie du champ (un bouton sans lien ni document serait refusé par le serveur).
  const [draft, setDraft] = useState<{ label?: string; href?: string }>({})

  return (
    <NodeViewWrapper className={`edit-attachment-node edit-button-node${selected ? ' is-selected' : ''}`} data-drag-handle>
      <RichButton label={label} href={href} documentId={a.documentId} />
      {editing ? (
        <div className="edit-button-form" data-testid="edit-button-form">
          <label>
            <span>Libellé</span>
            <input
              type="text"
              value={draft.label ?? label}
              onChange={(e) => setDraft((d) => ({ ...d, label: e.target.value }))}
              onBlur={() => {
                const next = (draft.label ?? label).trim()
                if (next) updateAttributes({ label: next })
                setDraft((d) => ({ ...d, label: undefined }))
              }}
            />
          </label>
          {hasDocument ? null : (
            <label>
              <span>Lien</span>
              <input
                type="url"
                placeholder="https://"
                value={draft.href ?? href}
                onChange={(e) => setDraft((d) => ({ ...d, href: e.target.value }))}
                onBlur={() => {
                  const next = (draft.href ?? href).trim()
                  if (next) updateAttributes({ href: next })
                  setDraft((d) => ({ ...d, href: undefined }))
                }}
              />
            </label>
          )}
        </div>
      ) : null}
    </NodeViewWrapper>
  )
}

/**
 * Bouton (CTA) en bloc. JSON : `{ type: 'button', attrs: { label, href?, documentId? } }` —
 * `href` : lien externe http(s) ; `documentId` : renvoi vers `/docs/{id}` si le lecteur y a accès.
 */
export const ButtonNode = Node.create({
  name: BUTTON_NODE_TYPE,
  group: 'block',
  inline: false,
  atom: true,
  selectable: true,
  draggable: true,

  addAttributes() {
    return {
      label: {
        default: DEFAULT_BUTTON_LABEL,
        parseHTML: (el: HTMLElement) => el.getAttribute('data-label') ?? (el.textContent || DEFAULT_BUTTON_LABEL),
        renderHTML: (attrs: Record<string, unknown>) => ({ 'data-label': String(attrs.label ?? '') }),
      },
      href: nullableStr('href', 'data-href'),
      documentId: nullableStr('documentId', 'data-document-id'),
    }
  },

  parseHTML() {
    return [{ tag: 'div[data-cta]' }]
  },

  renderHTML({ node, HTMLAttributes }) {
    return [
      'div',
      mergeAttributes(HTMLAttributes, { 'data-cta': 'true' }),
      String(node.attrs.label || DEFAULT_BUTTON_LABEL),
    ]
  },

  addNodeView() {
    return ReactNodeViewRenderer(ButtonView)
  },
})
