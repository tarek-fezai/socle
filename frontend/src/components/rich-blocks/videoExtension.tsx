// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { Node, NodeViewWrapper, ReactNodeViewRenderer, mergeAttributes, type NodeViewProps } from '@tiptap/react'
import { RichVideo } from './RichBlockViews'
import { VIDEO_NODE_TYPE } from './richBlockUtils'

function strAttr(name: string, data: string) {
  return {
    default: null as string | null,
    parseHTML: (el: HTMLElement) => el.getAttribute(data),
    renderHTML: (attrs: Record<string, unknown>) =>
      typeof attrs[name] === 'string' && attrs[name] ? { [data]: attrs[name] as string } : {},
  }
}

function VideoView({ node, selected }: NodeViewProps) {
  const a = node.attrs as Record<string, unknown>
  const id = typeof a.id === 'string' ? a.id : ''
  return (
    <NodeViewWrapper className={`edit-attachment-node${selected ? ' is-selected' : ''}`} data-drag-handle>
      {id ? (
        <RichVideo id={id} filename={typeof a.filename === 'string' ? a.filename : undefined} />
      ) : (
        <span className="doc-attachment-image is-error" role="note">
          Vidéo indisponible
        </span>
      )}
    </NodeViewWrapper>
  )
}

/**
 * Bloc vidéo (pièce jointe mp4 / webm). JSON : `{ type: 'video', attrs: { id, filename?, mediaType?, sizeBytes? } }`.
 */
export const VideoNode = Node.create({
  name: VIDEO_NODE_TYPE,
  group: 'block',
  inline: false,
  atom: true,
  selectable: true,
  draggable: true,

  addAttributes() {
    return {
      id: strAttr('id', 'data-attachment-id'),
      filename: strAttr('filename', 'data-filename'),
      mediaType: strAttr('mediaType', 'data-media-type'),
      sizeBytes: {
        default: null as number | null,
        parseHTML: (el: HTMLElement) => {
          const n = Number(el.getAttribute('data-size-bytes'))
          return Number.isFinite(n) && n > 0 ? n : null
        },
        renderHTML: (attrs: Record<string, unknown>) =>
          attrs.sizeBytes != null ? { 'data-size-bytes': String(attrs.sizeBytes) } : {},
      },
    }
  },

  parseHTML() {
    return [{ tag: 'div[data-video]' }]
  },

  renderHTML({ node, HTMLAttributes }) {
    return [
      'div',
      mergeAttributes(HTMLAttributes, { 'data-video': 'true' }),
      String(node.attrs.filename ?? 'Vidéo'),
    ]
  },

  addNodeView() {
    return ReactNodeViewRenderer(VideoView)
  },
})
