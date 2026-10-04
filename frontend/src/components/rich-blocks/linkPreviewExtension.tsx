// SPDX-License-Identifier: AGPL-3.0-or-later
import { useState } from 'react'
import { Node, NodeViewWrapper, ReactNodeViewRenderer, mergeAttributes, type NodeViewProps } from '@tiptap/react'
import { api } from '../../lib/api'
import { isSafeHttpUrl } from '../../lib/comments'
import { LinkPreviewCard } from './RichBlockViews'
import { DEFAULT_LINK_PREVIEW_URL, domainFromUrl, LINK_PREVIEW_NODE_TYPE } from './richBlockUtils'

function nullableStr(name: string, data: string) {
  return {
    default: null as string | null,
    parseHTML: (el: HTMLElement) => el.getAttribute(data),
    renderHTML: (attrs: Record<string, unknown>) =>
      typeof attrs[name] === 'string' && attrs[name] ? { [data]: attrs[name] as string } : {},
  }
}

function LinkPreviewEditView({ node, selected, editor, updateAttributes }: NodeViewProps) {
  const a = node.attrs as { url?: unknown; title?: unknown; domain?: unknown; thumbnailId?: unknown }
  const url = typeof a.url === 'string' ? a.url : DEFAULT_LINK_PREVIEW_URL
  const title = typeof a.title === 'string' ? a.title : null
  const domain = typeof a.domain === 'string' ? a.domain : domainFromUrl(url)
  const thumbnailId = typeof a.thumbnailId === 'string' ? a.thumbnailId : null
  const editing = selected && editor.isEditable
  const [draftUrl, setDraftUrl] = useState<string | null>(null)
  const [refreshing, setRefreshing] = useState(false)

  const applyUrl = (raw: string) => {
    const trimmed = raw.trim()
    if (!isSafeHttpUrl(trimmed)) return
    updateAttributes({ url: trimmed, domain: domainFromUrl(trimmed), title: null, thumbnailId: null })
  }

  const refresh = () => {
    if (!isSafeHttpUrl(url) || refreshing) return
    setRefreshing(true)
    void api
      .post('/api/v1/link-previews', { url })
      .then((r) => {
        const d = r.data as {
          url?: string
          domain?: string
          title?: string | null
          thumbnailAttachmentId?: string | null
        }
        updateAttributes({
          url: typeof d.url === 'string' ? d.url : url,
          domain: typeof d.domain === 'string' ? d.domain : domainFromUrl(url),
          title: typeof d.title === 'string' && d.title.trim() ? d.title.trim() : null,
          thumbnailId: typeof d.thumbnailAttachmentId === 'string' ? d.thumbnailAttachmentId : null,
        })
      })
      .finally(() => setRefreshing(false))
  }

  return (
    <NodeViewWrapper
      className={`edit-attachment-node edit-link-preview-node${selected ? ' is-selected' : ''}`}
      data-drag-handle
    >
      <LinkPreviewCard url={url} title={title} domain={domain} thumbnailId={thumbnailId} />
      {editing ? (
        <div className="edit-link-preview-form" data-testid="edit-link-preview-form">
          <label>
            <span>URL</span>
            <input
              type="url"
              value={draftUrl ?? url}
              onChange={(e) => setDraftUrl(e.target.value)}
              onBlur={() => {
                applyUrl(draftUrl ?? url)
                setDraftUrl(null)
              }}
            />
          </label>
          <button type="button" className="edit-link-preview-refresh" disabled={refreshing} onClick={refresh}>
            {refreshing ? 'Actualisation…' : 'Actualiser l’aperçu'}
          </button>
        </div>
      ) : null}
    </NodeViewWrapper>
  )
}

/**
 * Carte d'aperçu de lien. JSON : `{ type: 'linkPreview', attrs: { url, title?, domain?, thumbnailId? } }`.
 */
export const LinkPreviewNode = Node.create({
  name: LINK_PREVIEW_NODE_TYPE,
  group: 'block',
  inline: false,
  atom: true,
  selectable: true,
  draggable: true,

  addAttributes() {
    return {
      url: {
        default: DEFAULT_LINK_PREVIEW_URL,
        parseHTML: (el: HTMLElement) => el.getAttribute('data-url') ?? DEFAULT_LINK_PREVIEW_URL,
        renderHTML: (attrs: Record<string, unknown>) => ({ 'data-url': String(attrs.url ?? '') }),
      },
      title: nullableStr('title', 'data-title'),
      domain: nullableStr('domain', 'data-domain'),
      thumbnailId: nullableStr('thumbnailId', 'data-thumbnail-id'),
    }
  },

  parseHTML() {
    return [{ tag: 'div[data-link-preview]' }]
  },

  renderHTML({ node, HTMLAttributes }) {
    return [
      'div',
      mergeAttributes(HTMLAttributes, { 'data-link-preview': 'true' }),
      String(node.attrs.url ?? ''),
    ]
  },

  addNodeView() {
    return ReactNodeViewRenderer(LinkPreviewEditView)
  },
})
