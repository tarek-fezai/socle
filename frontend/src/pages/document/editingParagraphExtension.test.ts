// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { afterEach, describe, expect, it } from 'vitest'
import { Editor, getSchema } from '@tiptap/react'
import StarterKit from '@tiptap/starter-kit'
import { EditorState, TextSelection } from '@tiptap/pm/state'
import { documentEditorExtensions } from './documentEditorExtensions'
import { EDITING_PARAGRAPH_CLASS, EditingParagraph, editingParagraphRange } from './editingParagraphExtension'

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

describe('EditingParagraph (décoration ProseMirror)', () => {
  let editor: Editor | null = null
  afterEach(() => {
    editor?.destroy()
    editor = null
  })

  it('n’ajoute ni nœud ni marque au schéma (contrat TipTapContentValidator inchangé)', () => {
    const names = (exts: ReturnType<typeof documentEditorExtensions>) => {
      const s = getSchema(exts)
      return { nodes: Object.keys(s.nodes).sort(), marks: Object.keys(s.marks).sort() }
    }
    const all = documentEditorExtensions()
    expect(all).toContain(EditingParagraph)
    expect(names(all)).toEqual(names(all.filter((e) => e !== EditingParagraph)))
  })

  it('getJSON() strictement identique, curseur dans ou hors du paragraphe', () => {
    const element = document.createElement('div')
    document.body.appendChild(element)
    editor = new Editor({ element, extensions: documentEditorExtensions(), content: doc.toJSON() })
    const paraStart = doc.child(0).nodeSize

    editor.view.dom.dispatchEvent(new FocusEvent('focus'))
    editor.commands.setTextSelection(2)
    expect(editor.view.dom.querySelector(`.${EDITING_PARAGRAPH_CLASS}`)).toBeNull()
    const outside = JSON.stringify(editor.getJSON())

    editor.commands.setTextSelection(paraStart + 3)
    const decorated = editor.view.dom.querySelector(`p.${EDITING_PARAGRAPH_CLASS}`)
    expect(decorated?.textContent).toBe('Paragraphe')
    const inside = JSON.stringify(editor.getJSON())

    expect(inside).toBe(outside)
    expect(inside).toBe(JSON.stringify(editor.state.doc.toJSON()))
    expect(inside).not.toContain(EDITING_PARAGRAPH_CLASS)

    editor.view.dom.dispatchEvent(new FocusEvent('blur'))
    expect(editor.view.dom.querySelector(`.${EDITING_PARAGRAPH_CLASS}`)).toBeNull()
    expect(JSON.stringify(editor.getJSON())).toBe(outside)
  })
})
