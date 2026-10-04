// SPDX-License-Identifier: AGPL-3.0-or-later
import { useState } from 'react'
import { Node, NodeViewWrapper, ReactNodeViewRenderer, mergeAttributes, type NodeViewProps } from '@tiptap/react'
import { api } from '../../lib/api'
import { PollView } from './RichBlockViews'
import {
  DEFAULT_POLL_OPTIONS,
  DEFAULT_POLL_QUESTION,
  POLL_NODE_TYPE,
  parseStringList,
} from './richBlockUtils'

function jsonAttr(name: string, data: string, fallback: unknown) {
  return {
    default: fallback,
    parseHTML: (el: HTMLElement) => {
      const raw = el.getAttribute(data)
      if (!raw) return fallback
      try {
        return JSON.parse(raw) as unknown
      } catch {
        return fallback
      }
    },
    renderHTML: (attrs: Record<string, unknown>) => {
      const v = attrs[name]
      return v === undefined || v === null ? {} : { [data]: JSON.stringify(v) }
    },
  }
}

function PollEditView({ node, selected, editor, updateAttributes }: NodeViewProps) {
  const a = node.attrs as { id?: unknown; question?: unknown; options?: unknown }
  const id = typeof a.id === 'string' ? a.id : ''
  const question = typeof a.question === 'string' ? a.question : DEFAULT_POLL_QUESTION
  const options = parseStringList(a.options)
  const editing = selected && editor.isEditable
  const [draftQ, setDraftQ] = useState<string | null>(null)
  const [draftOpts, setDraftOpts] = useState<string | null>(null)

  const closePoll = () => {
    if (!id) return
    void api.post(`/api/v1/polls/${encodeURIComponent(id)}/close`).then(() => {
      editor.commands.focus()
    })
  }

  return (
    <NodeViewWrapper className={`edit-attachment-node edit-poll-node${selected ? ' is-selected' : ''}`} data-drag-handle>
      <PollView pollId={id} question={question} options={options.length >= 2 ? options : [...DEFAULT_POLL_OPTIONS]} />
      {editing ? (
        <div className="edit-poll-form" data-testid="edit-poll-form">
          <label>
            <span>Question</span>
            <input
              type="text"
              value={draftQ ?? question}
              onChange={(e) => setDraftQ(e.target.value)}
              onBlur={() => {
                const next = (draftQ ?? question).trim()
                if (next) updateAttributes({ question: next })
                setDraftQ(null)
              }}
            />
          </label>
          <label>
            <span>Options (une par ligne)</span>
            <textarea
              rows={3}
              value={draftOpts ?? options.join('\n')}
              onChange={(e) => setDraftOpts(e.target.value)}
              onBlur={() => {
                const lines = (draftOpts ?? options.join('\n'))
                  .split('\n')
                  .map((l) => l.trim())
                  .filter(Boolean)
                if (lines.length >= 2) updateAttributes({ options: lines })
                setDraftOpts(null)
              }}
            />
          </label>
          <button type="button" className="edit-poll-close-btn" onClick={closePoll}>
            Fermer le sondage
          </button>
        </div>
      ) : null}
    </NodeViewWrapper>
  )
}

/**
 * Sondage en bloc. JSON : `{ type: 'poll', attrs: { id, question, options } }`.
 */
export const PollNode = Node.create({
  name: POLL_NODE_TYPE,
  group: 'block',
  inline: false,
  atom: true,
  selectable: true,
  draggable: true,

  addAttributes() {
    return {
      id: {
        default: null as string | null,
        parseHTML: (el: HTMLElement) => el.getAttribute('data-poll-id'),
        renderHTML: (attrs: Record<string, unknown>) =>
          typeof attrs.id === 'string' && attrs.id ? { 'data-poll-id': attrs.id } : {},
      },
      question: {
        default: DEFAULT_POLL_QUESTION,
        parseHTML: (el: HTMLElement) => el.getAttribute('data-question') ?? DEFAULT_POLL_QUESTION,
        renderHTML: (attrs: Record<string, unknown>) => ({ 'data-question': String(attrs.question ?? '') }),
      },
      options: jsonAttr('options', 'data-options', [...DEFAULT_POLL_OPTIONS]),
    }
  },

  parseHTML() {
    return [{ tag: 'div[data-poll]' }]
  },

  renderHTML({ node, HTMLAttributes }) {
    return ['div', mergeAttributes(HTMLAttributes, { 'data-poll': 'true' }), String(node.attrs.question ?? '')]
  },

  addNodeView() {
    return ReactNodeViewRenderer(PollEditView)
  },
})
