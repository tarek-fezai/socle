// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * Contrat éditeur → TipTapContentValidator :
 * construit via TipTap (mêmes extensions que DocumentEditor) un document couvrant
 * chaque fonctionnalité active, exporte getJSON(), et fige la fixture partagée
 * avec le backend (`backend/src/test/resources/tiptap/editor-contract.json`).
 *
 * Régénérer : `UPDATE_TIPTAP_FIXTURE=1 pnpm vitest run src/pages/document/tiptapEditorContract.fixture.test.ts`
 */
import { Editor } from '@tiptap/react'
import { describe, expect, it } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import {
  BUTTON_NODE_TYPE,
  DATE_NODE_TYPE,
  DEFAULT_BUTTON_HREF,
  DEFAULT_BUTTON_LABEL,
} from '../../components/rich-blocks/richBlockUtils'
import { PLACEHOLDER_NODE_TYPE } from '../../lib/templates'
import { documentEditorExtensions } from './documentEditorExtensions'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const FIXTURE_FE = path.resolve(__dirname, '../../test/fixtures/tiptap/editor-contract.json')
const FIXTURE_BE = path.resolve(__dirname, '../../../../backend/src/test/resources/tiptap/editor-contract.json')

const IMG_ID = '11111111-1111-1111-1111-111111111111'
const FILE_ID = '22222222-2222-2222-2222-222222222222'
const VIDEO_ID = '33333333-3333-3333-3333-333333333333'
const DOC_ID = 'dddddddd-dddd-dddd-dddd-ddddddddddd1'
const INTERNAL_DOC = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'

function buildContractDoc(): Record<string, unknown> {
  const editor = new Editor({
    extensions: documentEditorExtensions(),
    content: { type: 'doc', content: [{ type: 'paragraph' }] },
  })

  // Capturer attrs par défaut TipTap pour orderedList (start + type).
  editor.commands.setContent({
    type: 'doc',
    content: [{ type: 'paragraph', content: [{ type: 'text', text: 'Un' }] }],
  })
  editor.chain().focus().selectAll().toggleOrderedList().run()
  const orderedAttrs = (
    (editor.getJSON() as { content?: Array<{ type?: string; attrs?: Record<string, unknown> }> }).content ?? []
  ).find((n) => n.type === 'orderedList')?.attrs ?? { start: 1, type: null }

  editor.commands.setContent({
    type: 'doc',
    content: [
      {
        type: 'heading',
        attrs: { level: 1 },
        content: [{ type: 'text', text: 'Titre contrat' }],
      },
      {
        type: 'heading',
        attrs: { level: 2 },
        content: [{ type: 'text', text: 'Sous-titre' }],
      },
      {
        type: 'paragraph',
        content: [
          { type: 'text', text: 'Gras ', marks: [{ type: 'bold' }] },
          { type: 'text', text: 'italique ', marks: [{ type: 'italic' }] },
          { type: 'text', text: 'barré ', marks: [{ type: 'strike' }] },
          { type: 'text', text: 'souligné ', marks: [{ type: 'underline' }] },
          { type: 'text', text: 'code', marks: [{ type: 'code' }] },
          { type: 'text', text: ' et ' },
          {
            type: 'text',
            text: 'lien',
            marks: [
              {
                type: 'link',
                attrs: {
                  href: 'https://example.org/docs',
                  target: '_blank',
                  rel: 'noopener noreferrer',
                  class: null,
                },
              },
            ],
          },
          { type: 'text', text: '.' },
        ],
      },
      {
        type: 'bulletList',
        content: [
          {
            type: 'listItem',
            content: [{ type: 'paragraph', content: [{ type: 'text', text: 'Puce' }] }],
          },
        ],
      },
      {
        type: 'orderedList',
        attrs: orderedAttrs,
        content: [
          {
            type: 'listItem',
            content: [{ type: 'paragraph', content: [{ type: 'text', text: 'Un' }] }],
          },
          {
            type: 'listItem',
            content: [{ type: 'paragraph', content: [{ type: 'text', text: 'Deux' }] }],
          },
        ],
      },
      {
        type: 'codeBlock',
        attrs: { language: 'typescript' },
        content: [{ type: 'text', text: 'const x = 1' }],
      },
      {
        type: 'blockquote',
        content: [{ type: 'paragraph', content: [{ type: 'text', text: 'Citation' }] }],
      },
      { type: 'horizontalRule' },
      {
        type: 'table',
        content: [
          {
            type: 'tableRow',
            content: [
              {
                type: 'tableHeader',
                attrs: { colspan: 1, rowspan: 1, colwidth: null },
                content: [{ type: 'paragraph', content: [{ type: 'text', text: 'Col A' }] }],
              },
              {
                type: 'tableHeader',
                attrs: { colspan: 1, rowspan: 1, colwidth: null },
                content: [{ type: 'paragraph', content: [{ type: 'text', text: 'Col B' }] }],
              },
            ],
          },
          {
            type: 'tableRow',
            content: [
              {
                type: 'tableCell',
                attrs: { colspan: 1, rowspan: 1, colwidth: null },
                content: [{ type: 'paragraph', content: [{ type: 'text', text: '1' }] }],
              },
              {
                type: 'tableCell',
                attrs: { colspan: 1, rowspan: 1, colwidth: null },
                content: [{ type: 'paragraph', content: [{ type: 'text', text: '2' }] }],
              },
            ],
          },
        ],
      },
      { type: DATE_NODE_TYPE, attrs: { value: '2026-10-04' } },
      {
        type: BUTTON_NODE_TYPE,
        attrs: { label: DEFAULT_BUTTON_LABEL, href: DEFAULT_BUTTON_HREF, documentId: null },
      },
      {
        type: BUTTON_NODE_TYPE,
        attrs: { label: 'Voir le doc', href: null, documentId: INTERNAL_DOC },
      },
      {
        type: 'image',
        attrs: {
          id: IMG_ID,
          src: null,
          alt: 'Schéma',
          caption: null,
          filename: 'schema.png',
          mediaType: 'image/png',
          sizeBytes: 1200,
          width: 640,
          height: 480,
        },
      },
      {
        type: 'attachment',
        attrs: {
          id: FILE_ID,
          filename: 'note.pdf',
          mediaType: 'application/pdf',
          sizeBytes: 4096,
        },
      },
      {
        type: 'video',
        attrs: {
          id: VIDEO_ID,
          filename: 'demo.mp4',
          mediaType: 'video/mp4',
          sizeBytes: 8192,
        },
      },
      {
        type: PLACEHOLDER_NODE_TYPE,
        attrs: { hint: 'Zone à compléter' },
      },
      {
        type: 'paragraph',
        content: [{ type: 'text', text: 'Fin' }],
      },
    ],
  })

  const json = editor.getJSON() as { type: string; content?: unknown[] }
  // Transclusion : pas d'extension TipTap — ajoutée au contrat JSON (corps seed / lecture).
  const content = Array.isArray(json.content) ? [...json.content] : []
  content.push({
    type: 'transclusion',
    attrs: { documentId: DOC_ID },
  })
  editor.destroy()
  return { type: 'doc', content }
}

function collectTypes(node: unknown, nodes: Set<string>, marks: Set<string>) {
  if (!node || typeof node !== 'object') return
  const n = node as { type?: string; marks?: Array<{ type?: string }>; content?: unknown[] }
  if (typeof n.type === 'string') nodes.add(n.type)
  if (Array.isArray(n.marks)) for (const m of n.marks) if (m?.type) marks.add(m.type)
  if (Array.isArray(n.content)) for (const c of n.content) collectTypes(c, nodes, marks)
}

describe('contrat TipTap éditeur → validateur', () => {
  it('exporte getJSON() couvrant chaque fonctionnalité active et fige la fixture', () => {
    const doc = buildContractDoc()
    const nodes = new Set<string>()
    const marks = new Set<string>()
    collectTypes(doc, nodes, marks)

    for (const t of [
      'heading',
      'bulletList',
      'orderedList',
      'codeBlock',
      'blockquote',
      'table',
      'tableHeader',
      'date',
      'button',
      'image',
      'attachment',
      'video',
      'transclusion',
      'placeholder',
    ]) {
      expect(nodes.has(t), `nœud manquant: ${t}`).toBe(true)
    }
    for (const m of ['bold', 'italic', 'strike', 'underline', 'code', 'link']) {
      expect(marks.has(m), `marque manquante: ${m}`).toBe(true)
    }

    const ordered = (doc.content as Array<{ type?: string; attrs?: Record<string, unknown> }>).find(
      (n) => n.type === 'orderedList',
    )
    expect(ordered?.attrs).toBeTruthy()
    expect(Object.prototype.hasOwnProperty.call(ordered?.attrs, 'type')).toBe(true)

    const text = `${JSON.stringify(doc, null, 2)}\n`
    if (process.env.UPDATE_TIPTAP_FIXTURE === '1') {
      fs.mkdirSync(path.dirname(FIXTURE_FE), { recursive: true })
      fs.mkdirSync(path.dirname(FIXTURE_BE), { recursive: true })
      fs.writeFileSync(FIXTURE_FE, text, 'utf8')
      fs.writeFileSync(FIXTURE_BE, text, 'utf8')
    }

    expect(fs.existsSync(FIXTURE_FE), 'fixture FE manquante — lancer avec UPDATE_TIPTAP_FIXTURE=1').toBe(true)
    expect(fs.existsSync(FIXTURE_BE), 'fixture BE manquante — lancer avec UPDATE_TIPTAP_FIXTURE=1').toBe(true)
    expect(fs.readFileSync(FIXTURE_FE, 'utf8')).toBe(text)
    expect(fs.readFileSync(FIXTURE_BE, 'utf8')).toBe(text)
  })
})
