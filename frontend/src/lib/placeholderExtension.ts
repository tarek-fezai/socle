// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { Node, mergeAttributes } from '@tiptap/react'
import { DEFAULT_PLACEHOLDER_HINT, PLACEHOLDER_NODE_TYPE } from './templates'

/**
 * Bloc atomique « zone à compléter » : porté par les modèles, à remplacer par l'auteur
 * du document créé. JSON : `{ type: 'placeholder', attrs: { hint } }`.
 * Rendu : indice en gris italique (classe `.socle-placeholder`, cf. index.css).
 *
 * Insertion : `editor.commands.insertContent({ type: 'placeholder', attrs: { hint } })`.
 */
export const Placeholder = Node.create({
  name: PLACEHOLDER_NODE_TYPE,
  group: 'block',
  inline: false,
  atom: true,
  selectable: true,
  draggable: true,

  addAttributes() {
    return {
      hint: {
        default: DEFAULT_PLACEHOLDER_HINT,
        parseHTML: (el) =>
          el.getAttribute('data-hint') ?? (el.textContent || DEFAULT_PLACEHOLDER_HINT),
        renderHTML: (attrs) => ({ 'data-hint': String(attrs.hint ?? '') }),
      },
    }
  },

  parseHTML() {
    return [{ tag: 'div[data-placeholder]' }]
  },

  renderHTML({ node, HTMLAttributes }) {
    const hint = String(node.attrs.hint || DEFAULT_PLACEHOLDER_HINT)
    return [
      'div',
      mergeAttributes(HTMLAttributes, {
        'data-placeholder': 'true',
        class: 'socle-placeholder',
      }),
      hint,
    ]
  },
})
