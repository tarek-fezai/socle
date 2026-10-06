// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'

const postMock = vi.fn()
vi.mock('../lib/api', () => ({ api: { post: (...args: unknown[]) => postMock(...args) } }))

import { MoveDialog } from './MoveDialog'
import type { TreeFolder } from '../lib/folders'

const folders: TreeFolder[] = [
  {
    id: 'a',
    name: 'Référence',
    parentFolderId: null,
    position: 0,
    documentCount: 0,
    folderCount: 1,
  },
  { id: 'a1', name: 'Rôles', parentFolderId: 'a', position: 0, documentCount: 0, folderCount: 0 },
  {
    id: 'b',
    name: 'Procédures',
    parentFolderId: null,
    position: 1,
    documentCount: 0,
    folderCount: 0,
  },
]

function wrap(ui: ReactNode) {
  const client = new QueryClient({ defaultOptions: { mutations: { retry: false } } })
  return <QueryClientProvider client={client}>{ui}</QueryClientProvider>
}

describe('MoveDialog', () => {
  beforeEach(() => postMock.mockReset())

  it('déplace un document vers un dossier', async () => {
    postMock.mockResolvedValue({ data: {} })
    const onMoved = vi.fn()
    render(
      wrap(
        <MoveDialog
          open
          onOpenChange={() => undefined}
          spaceId="s1"
          spaceName="Identité"
          folders={folders}
          target={{ kind: 'document', id: 'd1', name: 'Politique', currentParentId: null }}
          onMoved={onMoved}
        />,
      ),
    )

    const move = screen.getByRole('button', { name: 'Déplacer ici' }) as HTMLButtonElement
    expect(move.disabled).toBe(true) // emplacement inchangé

    fireEvent.click(screen.getByRole('radio', { name: 'Rôles' }))
    expect(move.disabled).toBe(false)
    fireEvent.click(move)

    await waitFor(() =>
      expect(postMock).toHaveBeenCalledWith('/api/v1/documents/d1/move', { folderId: 'a1' }),
    )
    await waitFor(() => expect(onMoved).toHaveBeenCalled())
  })

  it('interdit de déplacer un dossier dans lui-même ou un descendant', async () => {
    postMock.mockResolvedValue({ data: {} })
    render(
      wrap(
        <MoveDialog
          open
          onOpenChange={() => undefined}
          spaceId="s1"
          spaceName="Identité"
          folders={folders}
          target={{ kind: 'folder', id: 'a', name: 'Référence', currentParentId: null }}
        />,
      ),
    )

    expect((screen.getByRole('radio', { name: 'Référence' }) as HTMLButtonElement).disabled).toBe(
      true,
    )
    expect((screen.getByRole('radio', { name: 'Rôles' }) as HTMLButtonElement).disabled).toBe(true)

    fireEvent.click(screen.getByRole('radio', { name: 'Procédures' }))
    fireEvent.click(screen.getByRole('button', { name: 'Déplacer ici' }))
    await waitFor(() =>
      expect(postMock).toHaveBeenCalledWith('/api/v1/folders/a/move', { parentFolderId: 'b' }),
    )
  })

  it('permet le retour à la racine', async () => {
    postMock.mockResolvedValue({ data: {} })
    render(
      wrap(
        <MoveDialog
          open
          onOpenChange={() => undefined}
          spaceId="s1"
          spaceName="Identité"
          folders={folders}
          target={{ kind: 'document', id: 'd1', name: 'Politique', currentParentId: 'b' }}
        />,
      ),
    )
    fireEvent.click(screen.getByRole('radio', { name: 'Identité (racine)' }))
    fireEvent.click(screen.getByRole('button', { name: 'Déplacer ici' }))
    await waitFor(() =>
      expect(postMock).toHaveBeenCalledWith('/api/v1/documents/d1/move', { folderId: null }),
    )
  })
})
