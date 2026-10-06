// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { beforeAll, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { DocumentEditor } from '../DocumentEditor'
import { SOON_TITLE } from './EditToolbar'
import { uploadAttachment } from '../../lib/attachments'

vi.mock('../../lib/attachments', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../lib/attachments')>()),
  uploadAttachment: vi.fn(),
  // Les images sont chargées via l'API : hors sujet ici.
  attachmentObjectUrl: vi.fn(() => new Promise<string>(() => undefined)),
}))

// jsdom n'implémente pas la géométrie : ProseMirror en a besoin pour faire défiler vers la sélection.
const emptyRects = () => ({ length: 0, item: () => null, [Symbol.iterator]: function* () {} }) as unknown as DOMRectList
const zeroRect = () => ({ x: 0, y: 0, top: 0, left: 0, bottom: 0, right: 0, width: 0, height: 0, toJSON: () => ({}) }) as DOMRect
beforeAll(() => {
  for (const proto of [Range.prototype, Element.prototype, Text.prototype] as Array<object>) {
    const p = proto as { getClientRects?: () => DOMRectList; getBoundingClientRect?: () => DOMRect }
    if (!p.getClientRects) p.getClientRects = emptyRects
    if (!p.getBoundingClientRect) p.getBoundingClientRect = zeroRect
  }
})

const doc = { type: 'doc', content: [{ type: 'paragraph', content: [{ type: 'text', text: 'Bonjour' }] }] }

const SOON_LABELS = [
  'Couleur et surlignage',
  'Liste de tâches',
  'Diminuer le retrait',
  'Augmenter le retrait',
  'Aligner à gauche',
  'Centrer',
  'Justifier',
  'Insérer un diagramme draw.io',
  'Mentionner une personne',
  'Réduire les blocs enrichis',
]

describe('EditToolbar', () => {
  it('affiche les fonctions manquantes désactivées avec « Bientôt disponible »', async () => {
    render(<DocumentEditor variant="document" content={doc} onChange={() => undefined} />)
    await screen.findByRole('toolbar', { name: 'Mise en forme' })
    for (const label of SOON_LABELS) {
      const btn = screen.getByRole('button', { name: label })
      expect(btn.getAttribute('aria-disabled'), label).toBe('true')
      expect(btn.getAttribute('title'), label).toBe(SOON_TITLE)
    }
    expect(SOON_TITLE).toBe('Bientôt disponible')
  })

  it('les fonctions StarterKit sont actives (gras, titres, liste, séparateur)', async () => {
    const onChange = vi.fn()
    render(<DocumentEditor variant="document" content={doc} onChange={onChange} />)
    const bold = await screen.findByRole('button', { name: 'Gras' })
    expect(bold.getAttribute('aria-disabled')).toBeNull()

    fireEvent.click(screen.getByRole('button', { name: 'Insérer une ligne de séparation' }))
    await waitFor(() => expect(JSON.stringify(onChange.mock.calls.at(-1)?.[0])).toContain('horizontalRule'))

    fireEvent.click(screen.getByRole('button', { name: 'Liste à puces' }))
    await waitFor(() => expect(JSON.stringify(onChange.mock.calls.at(-1)?.[0])).toContain('bulletList'))
  })

  it('le menu de titres change le style du bloc', async () => {
    const onChange = vi.fn()
    render(<DocumentEditor variant="document" content={doc} onChange={onChange} />)
    fireEvent.click(await screen.findByTestId('edit-heading-menu'))
    fireEvent.click(await screen.findByRole('menuitemradio', { name: /Titre 2/ }))
    await waitFor(() => expect(JSON.stringify(onChange.mock.calls.at(-1)?.[0])).toContain('"level":2'))
  })

  it('tout est désactivé en lecture seule', async () => {
    render(<DocumentEditor variant="document" content={doc} onChange={() => undefined} editable={false} />)
    const bold = await screen.findByRole('button', { name: 'Gras' })
    expect(bold.getAttribute('aria-disabled')).toBe('true')
  })

  it('le panneau « Insérer » liste les blocs à venir désactivés, sauf le bloc de code', async () => {
    render(<DocumentEditor variant="document" content={doc} onChange={() => undefined} documentId="doc-1" />)
    fireEvent.click(await screen.findByTestId('edit-insert-btn'))
    const panel = await screen.findByTestId('edit-insert-panel')
    expect(panel.textContent).toContain('Vidéo')
    const soonItems = panel.querySelectorAll('[data-soon="true"]')
    expect(soonItems.length).toBeGreaterThanOrEqual(2)
    for (const el of soonItems) expect(el.getAttribute('aria-disabled')).toBe('true')
    // Vidéo, tableau, sondage, graphique, aperçu de lien, date et bouton sont branchés.
    for (const label of ['Vidéo', 'Tableau', 'Sondage', 'Graphique', 'Aperçu de lien', 'Date & heure', 'Bouton']) {
      const item = screen.getByText(label).closest('button')!
      expect(item.getAttribute('data-soon'), label).toBeNull()
      expect(item.getAttribute('aria-disabled'), label).toBeNull()
    }
    // « Fichier joint » n'est plus « bientôt » : branché sur l'envoi de pièces jointes.
    const attachment = screen.getByText('Fichier joint').closest('button')!
    expect(attachment.getAttribute('data-soon')).toBeNull()
    expect(attachment.getAttribute('aria-disabled')).toBeNull()
  })
})

describe('EditToolbar — blocs enrichis', () => {
  it('« Insérer un tableau » crée un tableau 3×3 avec ligne d’en-tête', async () => {
    const onChange = vi.fn()
    render(<DocumentEditor variant="document" content={doc} onChange={onChange} />)
    fireEvent.click(await screen.findByRole('button', { name: 'Insérer un tableau' }))
    await waitFor(() => {
      const json = onChange.mock.calls.at(-1)?.[0] as { content: Array<{ type: string; content?: Array<{ content: Array<{ type: string }> }> }> }
      const table = json.content.find((n) => n.type === 'table')
      expect(table?.content).toHaveLength(3)
      expect(table?.content?.[0].content.map((c) => c.type)).toEqual(['tableHeader', 'tableHeader', 'tableHeader'])
      expect(table?.content?.[1].content.map((c) => c.type)).toEqual(['tableCell', 'tableCell', 'tableCell'])
    })
  })

  it('le menu Insérer ajoute une date du jour et un bouton', async () => {
    const onChange = vi.fn()
    render(<DocumentEditor variant="document" content={doc} onChange={onChange} />)
    fireEvent.click(await screen.findByTestId('edit-insert-btn'))
    fireEvent.click(screen.getByText('Date & heure'))
    await waitFor(() => {
      const json = JSON.stringify(onChange.mock.calls.at(-1)?.[0])
      expect(json).toContain('"type":"date"')
      expect(json).toMatch(/"value":"\d{4}-\d{2}-\d{2}"/)
    })
    fireEvent.click(screen.getByTestId('edit-insert-btn'))
    fireEvent.click(screen.getByText('Bouton'))
    await waitFor(() => {
      const json = JSON.stringify(onChange.mock.calls.at(-1)?.[0])
      expect(json).toContain('"type":"button"')
      expect(json).toContain('"label":"Action"')
      expect(json).toContain('"href":"https://example.org"')
    })
  })

  it('le menu Insérer ajoute sondage, graphique et aperçu de lien', async () => {
    const onChange = vi.fn()
    const prompt = vi.spyOn(window, 'prompt').mockReturnValue('https://example.org')
    render(<DocumentEditor variant="document" content={doc} onChange={onChange} />)
    fireEvent.click(await screen.findByTestId('edit-insert-btn'))
    fireEvent.click(screen.getByText('Sondage'))
    await waitFor(() => {
      const json = JSON.stringify(onChange.mock.calls.at(-1)?.[0])
      expect(json).toContain('"type":"poll"')
      expect(json).toContain('"question":"Votre avis ?"')
      expect(json).toContain('"Oui"')
      expect(json).toContain('"Non"')
    })
    fireEvent.click(screen.getByTestId('edit-insert-btn'))
    fireEvent.click(screen.getByText('Graphique'))
    await waitFor(() => {
      const json = JSON.stringify(onChange.mock.calls.at(-1)?.[0])
      expect(json).toContain('"type":"chart"')
      expect(json).toContain('"chartType":"bar"')
      expect(json).toContain('Janvier')
    })
    fireEvent.click(screen.getByTestId('edit-insert-btn'))
    fireEvent.click(screen.getByText('Aperçu de lien'))
    await waitFor(() => {
      const json = JSON.stringify(onChange.mock.calls.at(-1)?.[0])
      expect(json).toContain('"type":"linkPreview"')
      expect(json).toContain('"url":"https://example.org"')
      expect(json).toContain('"domain":"example.org"')
    })
    prompt.mockRestore()
  })
})

describe('EditToolbar — pièces jointes', () => {
  const info = {
    id: 'att-1',
    filename: 'schema.png',
    mediaType: 'image/png',
    sizeBytes: 2048,
    width: 640,
    height: 480,
  }

  it('image et fichier joint ne sont plus « bientôt » ; inactifs sans document', async () => {
    const { unmount } = render(
      <DocumentEditor variant="document" content={doc} onChange={() => undefined} documentId="doc-1" />,
    )
    const image = await screen.findByRole('button', { name: 'Insérer une image' })
    expect(image.getAttribute('aria-disabled')).toBeNull()
    expect(image.getAttribute('data-soon')).toBeNull()
    expect(image.getAttribute('title')).not.toBe(SOON_TITLE)
    unmount()

    render(<DocumentEditor variant="document" content={doc} onChange={() => undefined} />)
    const inert = await screen.findByRole('button', { name: 'Insérer une image' })
    expect(inert.getAttribute('aria-disabled')).toBe('true')
    expect(inert.getAttribute('data-soon')).toBeNull()
  })

  it('envoie l’image choisie puis insère un bloc image avec ses attributs', async () => {
    vi.mocked(uploadAttachment).mockResolvedValueOnce(info)
    const onChange = vi.fn()
    render(<DocumentEditor variant="document" content={doc} onChange={onChange} documentId="doc-1" />)
    const input = (await screen.findByTestId('edit-image-input')) as HTMLInputElement
    const click = vi.spyOn(input, 'click')
    fireEvent.click(screen.getByRole('button', { name: 'Insérer une image' }))
    expect(click).toHaveBeenCalled()

    const file = new File(['x'], 'schema.png', { type: 'image/png' })
    fireEvent.change(input, { target: { files: [file] } })
    await waitFor(() => expect(uploadAttachment).toHaveBeenCalledWith('doc-1', file, expect.anything()))
    await waitFor(() => {
      const json = JSON.stringify(onChange.mock.calls.at(-1)?.[0])
      expect(json).toContain('"type":"image"')
      expect(json).toContain('"id":"att-1"')
      expect(json).toContain('"filename":"schema.png"')
      expect(json).toContain('"width":640')
      expect(json).toContain('"sizeBytes":2048')
    })
  })

  it('« Fichier joint » insère un bloc attachment', async () => {
    vi.mocked(uploadAttachment).mockResolvedValueOnce({
      id: 'att-2',
      filename: 'rapport.pdf',
      mediaType: 'application/pdf',
      sizeBytes: 12_345,
    })
    const onChange = vi.fn()
    render(<DocumentEditor variant="document" content={doc} onChange={onChange} documentId="doc-1" />)
    fireEvent.click(await screen.findByTestId('edit-insert-btn'))
    fireEvent.click(screen.getByText('Fichier joint'))
    const input = screen.getByTestId('edit-file-input')
    fireEvent.change(input, { target: { files: [new File(['x'], 'rapport.pdf', { type: 'application/pdf' })] } })
    await waitFor(() => {
      const json = JSON.stringify(onChange.mock.calls.at(-1)?.[0])
      expect(json).toContain('"type":"attachment"')
      expect(json).toContain('"id":"att-2"')
      expect(json).toContain('"mediaType":"application/pdf"')
    })
    expect(await screen.findByTestId('attachment-file')).toBeTruthy()
  })

  it('« Vidéo » envoie un mp4 puis insère un bloc video', async () => {
    vi.mocked(uploadAttachment).mockResolvedValueOnce({
      id: 'att-3',
      filename: 'demo.mp4',
      mediaType: 'video/mp4',
      sizeBytes: 5000,
    })
    const onChange = vi.fn()
    render(<DocumentEditor variant="document" content={doc} onChange={onChange} documentId="doc-1" />)
    const input = (await screen.findByTestId('edit-video-input')) as HTMLInputElement
    expect(input.getAttribute('accept')).toBe('video/mp4,video/webm')
    const click = vi.spyOn(input, 'click')
    fireEvent.click(screen.getByTestId('edit-insert-btn'))
    fireEvent.click(screen.getByText('Vidéo'))
    expect(click).toHaveBeenCalled()
    fireEvent.change(input, { target: { files: [new File(['x'], 'demo.mp4', { type: 'video/mp4' })] } })
    await waitFor(() => {
      const json = JSON.stringify(onChange.mock.calls.at(-1)?.[0])
      expect(json).toContain('"type":"video"')
      expect(json).toContain('"id":"att-3"')
    })
  })

  it('refuse une vidéo hors mp4 / webm sans appeler l’API', async () => {
    vi.mocked(uploadAttachment).mockClear()
    render(<DocumentEditor variant="document" content={doc} onChange={() => undefined} documentId="doc-1" />)
    const input = await screen.findByTestId('edit-video-input')
    fireEvent.change(input, { target: { files: [new File(['x'], 'film.avi', { type: 'video/x-msvideo' })] } })
    const alert = await screen.findByRole('alert')
    expect(alert.textContent).toBe('Seules les vidéos MP4 et WebM sont acceptées.')
    expect(uploadAttachment).not.toHaveBeenCalled()
  })

  it('affiche l’erreur réelle du serveur quand l’envoi échoue', async () => {
    vi.mocked(uploadAttachment).mockRejectedValueOnce({
      response: { status: 413, data: { status: 413, code: 'attachment_too_large', detail: 'Fichier trop volumineux (max. 25 Mo).' } },
    })
    render(<DocumentEditor variant="document" content={doc} onChange={() => undefined} documentId="doc-1" />)
    const input = await screen.findByTestId('edit-file-input')
    fireEvent.change(input, { target: { files: [new File(['x'], 'gros.zip', { type: 'application/zip' })] } })
    const alert = await screen.findByRole('alert')
    expect(alert.textContent).toBe('Fichier trop volumineux (max. 25 Mo).')
    fireEvent.click(screen.getByRole('button', { name: 'Fermer' }))
    await waitFor(() => expect(screen.queryByTestId('edit-uploads')).toBeNull())
  })
})
