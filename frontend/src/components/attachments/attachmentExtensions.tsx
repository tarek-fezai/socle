// SPDX-License-Identifier: AGPL-3.0-or-later
import { Node, NodeViewWrapper, ReactNodeViewRenderer, mergeAttributes, type NodeViewProps } from '@tiptap/react'
import { ATTACHMENT_NODE_TYPE, IMAGE_NODE_TYPE } from '../../lib/attachments'
import { AttachmentFile, AttachmentImage } from './AttachmentViews'

function numAttr(name: string, data: string) {
  return {
    default: null as number | null,
    parseHTML: (el: HTMLElement) => {
      const raw = el.getAttribute(data) ?? (name === 'width' || name === 'height' ? el.getAttribute(name) : null)
      const n = raw == null ? NaN : Number(raw)
      return Number.isFinite(n) && n > 0 ? n : null
    },
    renderHTML: (attrs: Record<string, unknown>) => (attrs[name] != null ? { [data]: String(attrs[name]) } : {}),
  }
}

function strAttr(name: string, data: string, fallback: string | null = null) {
  return {
    default: fallback,
    parseHTML: (el: HTMLElement) => el.getAttribute(data) ?? fallback,
    renderHTML: (attrs: Record<string, unknown>) =>
      typeof attrs[name] === 'string' && attrs[name] ? { [data]: attrs[name] as string } : {},
  }
}

function ImageView({ node, selected }: NodeViewProps) {
  const a = node.attrs as Record<string, unknown>
  const id = typeof a.id === 'string' ? a.id : null
  const alt = typeof a.alt === 'string' ? a.alt : ''
  const src = typeof a.src === 'string' && /^(https?:\/\/|\/(?!\/))/.test(a.src) ? a.src : null
  return (
    <NodeViewWrapper className={`edit-attachment-node${selected ? ' is-selected' : ''}`} data-drag-handle>
      <figure className="doc-figure edit-figure">
        {id ? (
          <AttachmentImage
            id={id}
            alt={alt}
            filename={typeof a.filename === 'string' ? a.filename : undefined}
            width={a.width as number | null}
            height={a.height as number | null}
          />
        ) : src ? (
          <img src={src} alt={alt} />
        ) : (
          <span className="doc-attachment-image is-error" role="note">
            Image indisponible
          </span>
        )}
      </figure>
    </NodeViewWrapper>
  )
}

function AttachmentView({ node, selected }: NodeViewProps) {
  const a = node.attrs as Record<string, unknown>
  const id = typeof a.id === 'string' ? a.id : ''
  return (
    <NodeViewWrapper className={`edit-attachment-node${selected ? ' is-selected' : ''}`} data-drag-handle>
      {id ? (
        <AttachmentFile
          id={id}
          filename={typeof a.filename === 'string' ? a.filename : undefined}
          sizeBytes={typeof a.sizeBytes === 'number' ? a.sizeBytes : null}
          mediaType={typeof a.mediaType === 'string' ? a.mediaType : undefined}
        />
      ) : (
        <span className="doc-attachment-image is-error" role="note">
          Fichier joint indisponible
        </span>
      )}
    </NodeViewWrapper>
  )
}

/**
 * Bloc image. JSON : `{ type: 'image', attrs: { id, filename, sizeBytes, mediaType, width, height, alt } }`
 * (Markdown : `![alt](attachment:<id>)`). Les images sans `id` (URL `src` historique) restent lisibles.
 */
export const AttachmentImageNode = Node.create({
  name: IMAGE_NODE_TYPE,
  group: 'block',
  inline: false,
  atom: true,
  selectable: true,
  draggable: true,

  addAttributes() {
    return {
      id: strAttr('id', 'data-attachment-id'),
      src: { default: null, parseHTML: (el: HTMLElement) => el.getAttribute('src'), renderHTML: () => ({}) },
      alt: { default: '', parseHTML: (el: HTMLElement) => el.getAttribute('alt') ?? '', renderHTML: () => ({}) },
      caption: { default: null, parseHTML: () => null, renderHTML: () => ({}) },
      filename: strAttr('filename', 'data-filename'),
      mediaType: strAttr('mediaType', 'data-media-type'),
      sizeBytes: numAttr('sizeBytes', 'data-size-bytes'),
      width: numAttr('width', 'data-width'),
      height: numAttr('height', 'data-height'),
    }
  },

  parseHTML() {
    return [{ tag: 'img[data-attachment-id]' }]
  },

  renderHTML({ node, HTMLAttributes }) {
    // Pas de `src` : l'image est protégée (jeton) et rendue par la vue de nœud.
    return ['img', mergeAttributes(HTMLAttributes, { alt: String(node.attrs.alt ?? '') })]
  },

  addNodeView() {
    return ReactNodeViewRenderer(ImageView)
  },
})

/**
 * Bloc « fichier joint ». JSON : `{ type: 'attachment', attrs: { id, filename, sizeBytes, mediaType } }`
 * (Markdown : `::attachment{id="…"}`).
 */
export const AttachmentNode = Node.create({
  name: ATTACHMENT_NODE_TYPE,
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
      sizeBytes: numAttr('sizeBytes', 'data-size-bytes'),
    }
  },

  parseHTML() {
    return [{ tag: 'div[data-attachment]' }]
  },

  renderHTML({ node, HTMLAttributes }) {
    return [
      'div',
      mergeAttributes(HTMLAttributes, { 'data-attachment': 'true' }),
      String(node.attrs.filename ?? 'Fichier joint'),
    ]
  },

  addNodeView() {
    return ReactNodeViewRenderer(AttachmentView)
  },
})
