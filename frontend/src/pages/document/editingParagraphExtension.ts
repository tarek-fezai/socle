// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { Extension } from '@tiptap/react'
import { Plugin, PluginKey, type EditorState } from '@tiptap/pm/state'
import { Decoration, DecorationSet } from '@tiptap/pm/view'

export const EDITING_PARAGRAPH_CLASS = 'is-editing'

const key = new PluginKey<boolean>('editingParagraph')

/** Paragraphe de premier niveau qui contient le curseur (sélection vide), sinon null. */
export function editingParagraphRange(state: EditorState): { from: number; to: number } | null {
  const { selection } = state
  if (!selection.empty) return null
  const $pos = selection.$from
  if ($pos.depth !== 1 || $pos.parent.type.name !== 'paragraph') return null
  return { from: $pos.before(1), to: $pos.after(1) }
}

/**
 * État « paragraphe en cours d'édition » (Edit.dc.html) : classe `is-editing` sur le paragraphe de premier
 * niveau qui porte le curseur, uniquement quand l'éditeur a le focus et est modifiable.
 */
export const EditingParagraph = Extension.create({
  name: 'editingParagraph',

  addProseMirrorPlugins() {
    return [
      new Plugin<boolean>({
        key,
        state: {
          init: () => false,
          apply: (tr, focused) => tr.getMeta(key) ?? focused,
        },
        props: {
          handleDOMEvents: {
            focus: (view) => {
              view.dispatch(view.state.tr.setMeta(key, view.editable))
              return false
            },
            blur: (view) => {
              view.dispatch(view.state.tr.setMeta(key, false))
              return false
            },
          },
          decorations(state) {
            if (!key.getState(state)) return null
            const range = editingParagraphRange(state)
            if (!range) return null
            return DecorationSet.create(state.doc, [
              Decoration.node(range.from, range.to, { class: EDITING_PARAGRAPH_CLASS }),
            ])
          },
        },
      }),
    ]
  },
})
