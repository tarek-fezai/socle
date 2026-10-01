// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import type { ReactNode } from 'react'

const getMock = vi.fn()
const cyDestroy = vi.fn()
const cyOn = vi.fn()

vi.mock('../lib/api', () => ({
  api: {
    get: (...args: unknown[]) => getMock(...args),
  },
}))

vi.mock('cytoscape', () => {
  const factory = vi.fn(() => ({
    destroy: cyDestroy,
    on: cyOn,
  }))
  return { default: factory }
})

import { GraphPage } from './GraphPage'
import cytoscape from 'cytoscape'

const SPACE = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'
const DOC_A = 'cccccccc-cccc-cccc-cccc-cccccccccccc'
const DOC_B = 'dddddddd-dddd-dddd-dddd-dddddddddddd'

function wrap(ui: ReactNode) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return (
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[`/spaces/${SPACE}/graph`]}>
        <Routes>
          <Route path="/spaces/:spaceId/graph" element={ui} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  )
}

describe('GraphPage', () => {
  beforeEach(() => {
    getMock.mockReset()
    cyDestroy.mockReset()
    cyOn.mockReset()
    vi.mocked(cytoscape).mockClear()
  })

  it('charge le graphe et initialise Cytoscape avec arêtes intra/inter', async () => {
    getMock.mockImplementation((url: string) => {
      if (url === `/api/v1/spaces/${SPACE}`) {
        return Promise.resolve({
          data: { id: SPACE, name: 'Identité', color: null, createdAt: null, canManage: true, isOwner: true, isResponsible: true },
        })
      }
      if (url === `/api/v1/spaces/${SPACE}/graph`) {
        return Promise.resolve({
          data: {
            spaceId: SPACE,
            nodes: [
              { id: DOC_A, title: 'Composite', spaceId: SPACE },
              { id: DOC_B, title: 'Cible', spaceId: SPACE },
            ],
            edges: [{ sourceId: DOC_A, targetId: DOC_B, kind: 'intra' }],
          },
        })
      }
      return Promise.reject(new Error(url))
    })

    render(wrap(<GraphPage />))

    await waitFor(() => {
      expect(screen.getByTestId('transclusion-graph')).toBeTruthy()
    })
    await waitFor(() => {
      expect(cytoscape).toHaveBeenCalled()
    })

    const opts = vi.mocked(cytoscape).mock.calls[0][0] as unknown as {
      elements: Array<{ data: Record<string, unknown> }>
      style: Array<{ selector: string }>
    }
    expect(opts.elements.some((e) => e.data.kind === 'intra')).toBe(true)
    expect(opts.style.some((s) => s.selector.includes('intra'))).toBe(true)
    expect(opts.style.some((s) => s.selector.includes('inter'))).toBe(true)
    expect(getMock).toHaveBeenCalledWith(`/api/v1/spaces/${SPACE}/graph`)
  })

  it('n’affiche pas d’id secret quand l’API omet la cible non lisible', async () => {
    const SECRET = 'eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee'
    getMock.mockImplementation((url: string) => {
      if (url === `/api/v1/spaces/${SPACE}`) {
        return Promise.resolve({
          data: { id: SPACE, name: 'Identité', color: null, createdAt: null, canManage: true, isOwner: true, isResponsible: true },
        })
      }
      if (url === `/api/v1/spaces/${SPACE}/graph`) {
        return Promise.resolve({
          data: {
            spaceId: SPACE,
            nodes: [{ id: DOC_A, title: 'Composite', spaceId: SPACE }],
            edges: [],
          },
        })
      }
      return Promise.reject(new Error(url))
    })

    const { container } = render(wrap(<GraphPage />))
    await waitFor(() => expect(screen.getByTestId('transclusion-graph')).toBeTruthy())
    expect(container.textContent).not.toContain(SECRET)
    expect(container.textContent).not.toContain('Titre secret')
  })
})
