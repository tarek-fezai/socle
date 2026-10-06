// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'

const listComments = vi.fn()
const createComment = vi.fn()
const resolveComment = vi.fn()
const reopenComment = vi.fn()

vi.mock('../lib/api', () => ({ api: {} }))
vi.mock('../lib/comments', async () => {
  const actual = await vi.importActual<typeof import('../lib/comments')>('../lib/comments')
  return {
    ...actual,
    listComments: (...args: unknown[]) => listComments(...args),
    createComment: (...args: unknown[]) => createComment(...args),
    resolveComment: (...args: unknown[]) => resolveComment(...args),
    reopenComment: (...args: unknown[]) => reopenComment(...args),
    fetchMentionSuggestions: vi.fn().mockResolvedValue([]),
  }
})

import { CommentsPanel } from './CommentsPanel'
import type { CommentsPage, CommentView } from '../lib/comments'

const DOC = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'
const THREAD_ID = 'cccccccc-cccc-cccc-cccc-cccccccccccc'

function thread(partial: Partial<CommentView> = {}): CommentView {
  return {
    id: THREAD_ID,
    documentId: DOC,
    parentId: null,
    authorId: '11111111-1111-1111-1111-111111111111',
    authorDisplayName: 'Claire Dubois',
    authorAnonymized: false,
    body: 'Faut-il préciser les comptes externes ?',
    status: 'ouvert',
    deleted: false,
    deletedLabel: null,
    anchor: {
      exact: 'comptes de service',
      prefix: null,
      suffix: null,
      blockId: null,
      versionNo: 1,
      attached: true,
      startOffset: 10,
      endOffset: 28,
    },
    createdAt: new Date(Date.now() - 3_600_000).toISOString(),
    updatedAt: new Date().toISOString(),
    replies: [],
    mentionWarnings: [],
    ...partial,
  }
}

function page(partial: Partial<CommentsPage> = {}): CommentsPage {
  return {
    documentId: DOC,
    versionNo: 1,
    threads: [thread()],
    detached: [],
    openThreadCount: 1,
    ...partial,
  }
}

function wrap(ui: ReactNode) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return <QueryClientProvider client={client}>{ui}</QueryClientProvider>
}

describe('CommentsPanel', () => {
  beforeEach(() => {
    listComments.mockReset()
    createComment.mockReset()
    resolveComment.mockReset()
    reopenComment.mockReset()
  })

  it('affiche le panneau et le fil ouvert', async () => {
    listComments.mockResolvedValue(page())
    render(
      wrap(
        <CommentsPanel documentId={DOC} canComment readOnly={false} />,
      ),
    )

    await waitFor(() => {
      expect(screen.getByText('Faut-il préciser les comptes externes ?')).toBeTruthy()
    })
    expect(screen.getByText(/1 commentaire actif/)).toBeTruthy()
    expect(screen.getByText(/comptes de service/)).toBeTruthy()
    expect(listComments).toHaveBeenCalledWith(expect.anything(), DOC, {
      status: 'ouvert',
      version: undefined,
    })
  })

  it('crée un commentaire ancré', async () => {
    listComments.mockResolvedValue(page({ threads: [], openThreadCount: 0 }))
    createComment.mockResolvedValue(
      thread({
        body: 'Nouveau point',
        mentionWarnings: [],
      }),
    )

    render(
      wrap(
        <CommentsPanel
          documentId={DOC}
          canComment
          draftAnchor={{ exact: 'moindre privilège', prefix: 'principe ', suffix: ' doit' }}
        />,
      ),
    )

    await waitFor(() => expect(screen.getByTestId('draft-anchor')).toBeTruthy())
    fireEvent.change(screen.getByTestId('comment-composer'), {
      target: { value: 'Nouveau point' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Envoyer' }))

    await waitFor(() =>
      expect(createComment).toHaveBeenCalledWith(expect.anything(), DOC, {
        body: 'Nouveau point',
        parentId: null,
        anchor: { exact: 'moindre privilège', prefix: 'principe ', suffix: ' doit' },
      }),
    )
  })

  it('résout un fil ouvert', async () => {
    listComments.mockResolvedValue(page())
    resolveComment.mockResolvedValue(thread({ status: 'resolu' }))

    render(wrap(<CommentsPanel documentId={DOC} canComment />))

    await waitFor(() => screen.getByTestId(`resolve-${THREAD_ID}`))
    fireEvent.click(screen.getByTestId(`resolve-${THREAD_ID}`))

    await waitFor(() => expect(resolveComment).toHaveBeenCalledWith(expect.anything(), THREAD_ID))
  })
})
