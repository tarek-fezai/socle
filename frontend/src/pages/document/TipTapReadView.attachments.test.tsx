// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import type { TipTapNode } from '../../lib/documents'
import { attachmentObjectUrl, clearAttachmentUrlCache } from '../../lib/attachments'
import { IMAGE_UNAVAILABLE_LABEL } from '../../components/attachments/AttachmentViews'
import { TipTapReadView } from './TipTapReadView'
import { EDITOR_NODE_TYPES, findUnsupportedContent, hasUnsupportedContent } from './documentEditUtils'

vi.mock('../../lib/attachments', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../lib/attachments')>()),
  attachmentObjectUrl: vi.fn(),
}))

const doc = (...content: TipTapNode[]): TipTapNode => ({ type: 'doc', content })

function view(body: TipTapNode) {
  return render(
    <MemoryRouter>
      <TipTapReadView body={body} />
    </MemoryRouter>,
  )
}

beforeEach(() => {
  clearAttachmentUrlCache()
  vi.mocked(attachmentObjectUrl).mockReset()
})

describe('TipTapReadView — pièces jointes', () => {
  it('rend une image jointe via l’API (URL d’objet) avec ses dimensions', async () => {
    vi.mocked(attachmentObjectUrl).mockResolvedValue('blob:http://localhost/abc')
    view(
      doc({
        type: 'image',
        attrs: { id: 'att-1', alt: 'Schéma', filename: 'schema.png', mediaType: 'image/png', sizeBytes: 10, width: 640, height: 480 },
      }),
    )
    const img = (await screen.findByTestId('attachment-image')) as HTMLImageElement
    expect(img.getAttribute('src')).toBe('blob:http://localhost/abc')
    expect(img.getAttribute('alt')).toBe('Schéma')
    expect(img.getAttribute('width')).toBe('640')
    expect(img.getAttribute('height')).toBe('480')
    expect(attachmentObjectUrl).toHaveBeenCalledWith('att-1')
    expect(screen.queryByTestId('unsupported-block')).toBeNull()
  })

  it('indique une image indisponible si le chargement échoue', async () => {
    vi.mocked(attachmentObjectUrl).mockRejectedValue(new Error('403'))
    view(doc({ type: 'image', attrs: { id: 'att-2', alt: '', filename: 'secret.png' } }))
    const note = await screen.findByTestId('attachment-image-error')
    expect(note.textContent).toContain(IMAGE_UNAVAILABLE_LABEL)
    expect(note.textContent).toContain('secret.png')
  })

  it('garde le rendu des images historiques (src)', () => {
    view(doc({ type: 'image', attrs: { src: 'https://example.org/a.png', alt: 'a' } }))
    expect(document.querySelector('img')?.getAttribute('src')).toBe('https://example.org/a.png')
    expect(attachmentObjectUrl).not.toHaveBeenCalled()
  })

  it('rend un fichier joint : nom, taille, type, bouton de téléchargement', async () => {
    view(
      doc({
        type: 'attachment',
        attrs: { id: 'att-3', filename: 'rapport.pdf', mediaType: 'application/pdf', sizeBytes: 1_536_000 },
      }),
    )
    const card = await screen.findByTestId('attachment-file')
    expect(card.textContent).toContain('rapport.pdf')
    expect(card.textContent).toContain('1,5 Mo')
    expect(card.textContent).toContain('application/pdf')
    expect(screen.getByRole('button', { name: 'Télécharger rapport.pdf' })).toBeTruthy()
    expect(screen.queryByTestId('unsupported-block')).toBeNull()
  })

  it('un fichier joint sans identifiant reste un bloc non pris en charge', () => {
    view(doc({ type: 'attachment', attrs: { filename: 'x.pdf' } }))
    expect(screen.getByTestId('unsupported-block')).toBeTruthy()
  })

  it('réserve la place de l’image pendant le chargement', async () => {
    vi.mocked(attachmentObjectUrl).mockReturnValue(new Promise(() => undefined))
    view(doc({ type: 'image', attrs: { id: 'att-4', width: 100, height: 50 } }))
    await waitFor(() => expect(screen.getByTestId('attachment-image-loading')).toBeTruthy())
  })
})

describe('documentEditUtils — pièces jointes', () => {
  it('image et attachment sont représentables par l’éditeur (corps non verrouillé)', () => {
    expect(EDITOR_NODE_TYPES.has('image')).toBe(true)
    expect(EDITOR_NODE_TYPES.has('attachment')).toBe(true)
    const body = doc(
      { type: 'image', attrs: { id: 'a' } },
      { type: 'attachment', attrs: { id: 'b' } },
    )
    expect(hasUnsupportedContent(findUnsupportedContent(body))).toBe(false)
  })
})
