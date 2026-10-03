// SPDX-License-Identifier: AGPL-3.0-or-later
import { beforeAll, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { DocumentEditor } from '../DocumentEditor'
import { SOON_TITLE } from './EditToolbar'

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
  'Souligné',
  'Couleur et surlignage',
  'Liste de tâches',
  'Diminuer le retrait',
  'Augmenter le retrait',
  'Aligner à gauche',
  'Centrer',
  'Justifier',
  'Insérer un lien',
  'Insérer une image',
  'Insérer un diagramme draw.io',
  'Insérer un tableau',
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
    render(<DocumentEditor variant="document" content={doc} onChange={() => undefined} />)
    fireEvent.click(await screen.findByTestId('edit-insert-btn'))
    const panel = await screen.findByTestId('edit-insert-panel')
    expect(panel.textContent).toContain('Vidéo')
    const soonItems = panel.querySelectorAll('[data-soon="true"]')
    expect(soonItems.length).toBeGreaterThan(5)
    for (const el of soonItems) expect(el.getAttribute('aria-disabled')).toBe('true')
  })
})
