// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import type { TipTapNode } from '../../lib/documents'
import { api } from '../../lib/api'
import { attachmentObjectUrl, clearAttachmentUrlCache } from '../../lib/attachments'
import {
  DOCUMENT_INACCESSIBLE_LABEL,
  VIDEO_UNAVAILABLE_LABEL,
  clearDocumentAccessCache,
} from '../../components/rich-blocks/RichBlockViews'
import { chartAccessibilityLabel, formatDateFr, todayIsoDate } from '../../components/rich-blocks/richBlockUtils'
import { TipTapReadView } from './TipTapReadView'

vi.mock('../../lib/api', () => ({ api: { get: vi.fn(), put: vi.fn(), post: vi.fn() } }))
vi.mock('../../lib/attachments', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../lib/attachments')>()),
  attachmentObjectUrl: vi.fn(),
}))

const DOC_ID = '3f2b8c1e-5d4a-4e6f-9a7b-1c2d3e4f5a6b'
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
  clearDocumentAccessCache()
  vi.mocked(attachmentObjectUrl).mockReset()
  vi.mocked(api.get).mockReset()
})

describe('TipTapReadView — date', () => {
  it('affiche la date en français dans le texte du paragraphe', () => {
    view(
      doc({
        type: 'paragraph',
        content: [
          { type: 'text', text: 'Revue le ' },
          { type: 'date', attrs: { value: '2026-10-15' } },
        ],
      }),
    )
    const chip = document.querySelector('.doc-date')
    expect(chip?.textContent).toBe('15 octobre 2026')
    expect(document.querySelector('p')?.textContent).toBe('Revue le 15 octobre 2026')
    expect(screen.queryByTestId('unsupported-block')).toBeNull()
  })

  it('formatDateFr / todayIsoDate', () => {
    expect(formatDateFr('2026-01-01')).toBe('1 janvier 2026')
    expect(formatDateFr('pas une date')).toBe('pas une date')
    expect(todayIsoDate(new Date(2026, 9, 4))).toBe('2026-10-04')
  })
})

describe('TipTapReadView — bouton', () => {
  it('lien externe : nouvel onglet, noopener noreferrer', () => {
    view(doc({ type: 'button', attrs: { label: 'Voir le site', href: 'https://example.org/x' } }))
    const a = screen.getByRole('link', { name: 'Voir le site' })
    expect(a.getAttribute('href')).toBe('https://example.org/x')
    expect(a.getAttribute('target')).toBe('_blank')
    expect(a.getAttribute('rel')).toBe('noopener noreferrer')
    expect(api.get).not.toHaveBeenCalled()
  })

  it('refuse un lien non http(s) : bouton inerte', () => {
    view(doc({ type: 'button', attrs: { label: 'Piège', href: 'javascript:alert(1)' } }))
    expect(screen.queryByRole('link')).toBeNull()
    expect(screen.getByTestId('doc-button-inert').textContent).toBe('Piège')
  })

  it('document accessible : lien vers /docs/{id}', async () => {
    vi.mocked(api.get).mockResolvedValue({ data: {} })
    view(doc({ type: 'button', attrs: { label: 'Ouvrir', documentId: DOC_ID } }))
    const a = await screen.findByRole('link', { name: 'Ouvrir' })
    expect(a.getAttribute('href')).toBe(`/docs/${DOC_ID}`)
    expect(api.get).toHaveBeenCalledWith(`/api/v1/documents/${DOC_ID}`)
  })

  it('document inaccessible (403/404) : bouton désactivé « Document inaccessible »', async () => {
    vi.mocked(api.get).mockRejectedValue({ response: { status: 403 } })
    view(doc({ type: 'button', attrs: { label: 'Ouvrir', documentId: DOC_ID } }))
    const btn = (await screen.findByTestId('doc-button-denied')) as HTMLButtonElement
    expect(btn.disabled).toBe(true)
    expect(btn.textContent).toBe(DOCUMENT_INACCESSIBLE_LABEL)
    expect(screen.queryByRole('link')).toBeNull()
  })
})

describe('TipTapReadView — vidéo', () => {
  it('lit la vidéo via l’URL d’objet de la pièce jointe', async () => {
    vi.mocked(attachmentObjectUrl).mockResolvedValue('blob:http://localhost/vid')
    view(doc({ type: 'video', attrs: { id: 'att-v1', filename: 'demo.mp4', mediaType: 'video/mp4', sizeBytes: 1000 } }))
    const video = (await screen.findByTestId('attachment-video')) as HTMLVideoElement
    expect(video.tagName).toBe('VIDEO')
    expect(video.getAttribute('src')).toBe('blob:http://localhost/vid')
    expect(video.hasAttribute('controls')).toBe(true)
    expect(attachmentObjectUrl).toHaveBeenCalledWith('att-v1')
  })

  it('indique une vidéo indisponible si le chargement échoue', async () => {
    vi.mocked(attachmentObjectUrl).mockRejectedValue(new Error('403'))
    view(doc({ type: 'video', attrs: { id: 'att-v2', filename: 'secret.mp4' } }))
    const note = await screen.findByTestId('attachment-video-error')
    expect(note.textContent).toContain(VIDEO_UNAVAILABLE_LABEL)
    expect(note.textContent).toContain('secret.mp4')
  })

  it('une vidéo sans identifiant reste un bloc non pris en charge', () => {
    view(doc({ type: 'video', attrs: { filename: 'x.mp4' } }))
    expect(screen.getByTestId('unsupported-block')).toBeTruthy()
  })
})

const POLL_ID = 'a1b2c3d4-e5f6-7890-abcd-ef1234567890'

describe('TipTapReadView — sondage', () => {
  it('affiche les résultats agrégés du sondage', async () => {
    vi.mocked(api.get).mockResolvedValue({
      data: {
        question: 'Votre avis ?',
        options: ['Oui', 'Non'],
        closed: false,
        myVote: 'Oui',
        totalVotes: 10,
        results: [
          { option: 'Oui', count: 7 },
          { option: 'Non', count: 3 },
        ],
      },
    })
    view(
      doc({
        type: 'poll',
        attrs: { id: POLL_ID, question: 'Votre avis ?', options: ['Oui', 'Non'] },
      }),
    )
    expect(await screen.findByTestId('doc-poll')).toBeTruthy()
    const stats = await screen.findAllByTestId('doc-poll-stat')
    expect(stats.map((el) => el.textContent?.replace(/\u00a0/g, ' '))).toEqual(['7 (70 %)', '3 (30 %)'])
    expect(api.get).toHaveBeenCalledWith(`/api/v1/polls/${POLL_ID}`)
  })
})

describe('TipTapReadView — graphique', () => {
  it('expose un libellé accessible avec titre et séries', () => {
    const labels = ['A', 'B']
    const series = [{ name: 'S1', values: [1, 2] }]
    view(
      doc({
        type: 'chart',
        attrs: { chartType: 'bar', labels, series, title: 'Revenus' },
      }),
    )
    const aria = screen.getByTestId('doc-chart-aria')
    expect(aria.getAttribute('aria-label')).toBe(chartAccessibilityLabel('Revenus', labels, series))
  })
})

describe('TipTapReadView — aperçu de lien', () => {
  it('affiche le domaine sur la carte', () => {
    view(
      doc({
        type: 'linkPreview',
        attrs: { url: 'https://example.org/page', domain: 'example.org' },
      }),
    )
    expect(screen.getByTestId('doc-link-preview-domain').textContent).toBe('example.org')
    const link = screen.getByTestId('doc-link-preview')
    expect(link.getAttribute('href')).toBe('https://example.org/page')
  })
})
