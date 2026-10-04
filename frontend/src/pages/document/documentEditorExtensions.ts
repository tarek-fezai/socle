// SPDX-License-Identifier: AGPL-3.0-or-later
import Link from '@tiptap/extension-link'
import Underline from '@tiptap/extension-underline'
import StarterKit from '@tiptap/starter-kit'
import type { Extensions } from '@tiptap/react'
import { AttachmentImageNode, AttachmentNode } from '../../components/attachments/attachmentExtensions'
import { ButtonNode } from '../../components/rich-blocks/buttonExtension'
import { DateNode } from '../../components/rich-blocks/dateExtension'
import { tableExtensions } from '../../components/rich-blocks/tableExtensions'
import { VideoNode } from '../../components/rich-blocks/videoExtension'
import { Placeholder } from '../../lib/placeholderExtension'

/** Extensions TipTap de l'éditeur document — source de vérité pour le contrat getJSON ↔ validateur. */
export function documentEditorExtensions(): Extensions {
  return [
    StarterKit,
    Underline,
    Link.configure({
      openOnClick: false,
      autolink: false,
      linkOnPaste: true,
      HTMLAttributes: { rel: 'noopener noreferrer', target: '_blank' },
    }),
    Placeholder,
    AttachmentImageNode,
    AttachmentNode,
    DateNode,
    ButtonNode,
    VideoNode,
    ...tableExtensions,
  ]
}
