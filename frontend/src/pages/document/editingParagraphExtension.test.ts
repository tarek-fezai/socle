// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { describe, expect, it } from 'vitest'
import { getSchema } from '@tiptap/react'
import StarterKit from '@tiptap/starter-kit'
import { EditorState, TextSelection } from '@tiptap/pm/state'
import { editingParagraphRange } from './editingParagraphExtension'

const schema = getSchema([StarterKit])
const doc = schema.nodeFromJSON({
  type: 'doc',
  content: [
    { type: 'heading', attrs: { level: 2 }, content: [{ type: 'text', text: 'Titre' }] },
    { type: 'paragraph', content: [{ type: 'text', text: 'Paragraphe' }] },
    {
      type: 'bulletList',
      content: [{ type: 'listItem', content: [{ type: 'paragraph', content: [{ type: 'text', text: 'Puce' }] }] }],
    },
  ],
})

function at(from: number, to = from) {
  return EditorState.create({ doc, selection: TextSelection.create(doc, from, to) })
}

describe('editingParagraphRange', () => {
  const paraStart = doc.child(0).nodeSize

  it('renvoie le paragraphe de premier niveau qui porte le curseur', () => {
    expect(editingParagraphRange(at(paraStart + 3))).toEqual({
      from: paraStart,
      to: paraStart + doc.child(1).nodeSize,
    })
  })

  it('ignore les titres, les paragraphes imbriqués et les sélections non vides', () => {
    expect(editingParagraphRange(at(2))).toBeNull()
    const listParagraph = paraStart + doc.child(1).nodeSize + 3
    expect(editingParagraphRange(at(listParagraph + 1))).toBeNull()
    expect(editingParagraphRange(at(paraStart + 1, paraStart + 4))).toBeNull()
  })
})
