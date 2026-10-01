// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import type { ReactNode } from 'react'

const getMock = vi.fn()

vi.mock('../lib/api', () => ({
  api: { get: (...args: unknown[]) => getMock(...args) },
}))

import { DocumentExportPage } from './ExportPage'

const DOC = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb'

function wrap(ui: ReactNode) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return (
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[`/docs/${DOC}/export`]}>
        <Routes>
          <Route path="/docs/:id/export" element={ui} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  )
}

describe('DocumentExportPage', () => {
  beforeEach(() => {
    getMock.mockReset()
    vi.stubGlobal('URL', {
      createObjectURL: vi.fn(() => 'blob:mock'),
      revokeObjectURL: vi.fn(),
    })
  })

  it('télécharge le PDF via l’endpoint document export', async () => {
    getMock.mockResolvedValue({
      data: new Blob(['%PDF-mock'], { type: 'application/pdf' }),
      headers: { 'content-disposition': 'attachment; filename="Composite.pdf"' },
    })

    render(wrap(<DocumentExportPage />))
    fireEvent.click(screen.getByTestId('export-download'))

    await waitFor(() => {
      expect(getMock).toHaveBeenCalledWith(
        `/api/v1/documents/${DOC}/export`,
        expect.objectContaining({ responseType: 'blob' }),
      )
    })
  })
})
