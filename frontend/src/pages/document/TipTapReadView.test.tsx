// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { describe, expect, it } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import type { TipTapNode } from '../../lib/documents'
import {
  TipTapReadView,
  UNSUPPORTED_BLOCK_LABEL,
  analyzeBody,
  slugifyHeading,
  stripSectionNumber,
} from './TipTapReadView'

const p = (text: string): TipTapNode => ({
  type: 'paragraph',
  content: [{ type: 'text', text }],
})
const h = (level: number, text: string): TipTapNode => ({
  type: 'heading',
  attrs: { level },
  content: [{ type: 'text', text }],
})
const doc = (...content: TipTapNode[]): TipTapNode => ({ type: 'doc', content })

function view(body: TipTapNode, props: Partial<Parameters<typeof TipTapReadView>[0]> = {}) {
  return render(
    <MemoryRouter>
      <TipTapReadView body={body} {...props} />
    </MemoryRouter>,
  )
}

describe('TipTapReadView — blocs non pris en charge', () => {
  it('remplace un bloc inconnu par le libellé neutre, sans JSON brut', () => {
    const { container } = view(
      doc(p('Avant'), {
        type: 'drawio',
        attrs: { xml: '<mxfile secret="1"/>', diagramId: 'abc-123' },
      }),
    )
    const blocks = screen.getAllByTestId('unsupported-block')
    expect(blocks).toHaveLength(1)
    expect(within(blocks[0]!).getByText(UNSUPPORTED_BLOCK_LABEL)).toBeTruthy()
    expect(UNSUPPORTED_BLOCK_LABEL).toBe('Bloc non pris en charge dans cette version')
    const text = container.textContent ?? ''
    expect(text).not.toContain('{')
    expect(text).not.toContain('"type"')
    expect(text).not.toContain('mxfile')
    expect(text).not.toContain('abc-123')
    expect(text).toContain('Avant')
  })

  it('traite captures et extensions futures de la même façon', () => {
    view(doc({ type: 'screenshot', attrs: { src: 'x' } }, { type: 'futureWidget', content: [p('caché')] }))
    expect(screen.getAllByTestId('unsupported-block')).toHaveLength(2)
    expect(screen.queryByText('caché')).toBeNull()
  })

  it('garde la légende textuelle d’une figure non supportée (numérotée)', () => {
    view(
      doc({
        type: 'drawio',
        attrs: { caption: 'Circuit d’approbation à deux niveaux' },
      }),
    )
    expect(screen.getByTestId('unsupported-block')).toBeTruthy()
    expect(screen.getByText(UNSUPPORTED_BLOCK_LABEL)).toBeTruthy()
    expect(screen.getByText('Fig. 1 — Circuit d’approbation à deux niveaux')).toBeTruthy()
  })

  it('image sans URL sûre → bloc non supporté', () => {
    view(doc({ type: 'image', attrs: { src: 'javascript:alert(1)' } }))
    expect(screen.getByTestId('unsupported-block')).toBeTruthy()
    expect(document.querySelector('img')).toBeNull()
  })
})

describe('TipTapReadView — titres, sommaire, tableaux', () => {
  it('numérote les sections et pose des ids stables pour le sommaire', () => {
    const body = doc(
      p('Chapeau'),
      h(2, 'Principe du moindre privilège'),
      h(2, 'Rôles et périmètres'),
      h(3, 'Sous-section'),
      h(2, 'Rôles et périmètres'),
    )
    const a = analyzeBody(body)
    expect(a.headings.map((x) => x.id)).toEqual([
      'principe-du-moindre-privilege',
      'roles-et-perimetres',
      'sous-section',
      'roles-et-perimetres-2',
    ])
    expect(a.headings.map((x) => x.number)).toEqual([1, 2, null, 3])

    view(body, { analysis: a })
    const first = document.getElementById('principe-du-moindre-privilege')!
    expect(first.tagName).toBe('H2')
    expect(first.textContent).toBe('1. Principe du moindre privilège')
    expect(document.getElementById('sous-section')!.tagName).toBe('H3')
    expect(document.getElementById('sous-section')!.textContent).toBe('Sous-section')
  })

  it('ne double pas un numéro saisi à la main', () => {
    view(doc(h(2, '1. Principe du moindre privilège')))
    expect(screen.getByRole('heading', { level: 2 }).textContent).toBe('1. Principe du moindre privilège')
    expect(stripSectionNumber('2) Rôles')).toBe('Rôles')
    expect(slugifyHeading('Dé-provisioning des comptes')).toBe('de-provisioning-des-comptes')
  })

  it('réserve l’id documents-lies de la page', () => {
    const a = analyzeBody(doc(h(2, 'Documents liés')))
    expect(a.headings[0]!.id).toBe('documents-lies-2')
  })

  it('rend un tableau avec en-têtes', () => {
    view(
      doc({
        type: 'table',
        content: [
          {
            type: 'tableRow',
            content: [
              { type: 'tableHeader', content: [p('Rôle')] },
              { type: 'tableHeader', content: [p('Revue')] },
            ],
          },
          {
            type: 'tableRow',
            content: [
              { type: 'tableCell', content: [p('Administrateur système')] },
              { type: 'tableCell', content: [p('Trimestrielle')] },
            ],
          },
        ],
      }),
    )
    const table = screen.getByRole('table')
    expect(within(table).getAllByRole('columnheader').map((c) => c.textContent)).toEqual(['Rôle', 'Revue'])
    expect(within(table).getByText('Trimestrielle')).toBeTruthy()
  })

  it('numérote les légendes de figures légendées', () => {
    view(
      doc(
        {
          type: 'figure',
          content: [p('Schéma'), { type: 'figcaption', content: [{ type: 'text', text: 'Premier schéma' }] }],
        },
        { type: 'image', attrs: { src: 'https://example.com/a.png', alt: 'a', caption: 'Seconde figure' } },
      ),
    )
    expect(screen.getByText('Fig. 1 — Premier schéma')).toBeTruthy()
    expect(screen.getByText('Fig. 2 — Seconde figure')).toBeTruthy()
  })
})

describe('TipTapReadView — transclusion, marques, commentaires', () => {
  it('affiche la source résolue avec lien, sans fuite quand inaccessible', () => {
    view(
      doc(
        {
          type: 'transclusion',
          attrs: { accessible: true, documentId: 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', title: 'Procédure cible' },
          content: [doc(h(2, 'Titre source'), p('Contenu autorisé'))],
        },
        { type: 'transclusion', attrs: { accessible: false, deniedReason: 'forbidden', title: 'Secret' } },
      ),
    )
    expect(screen.getByTestId('transclusion-ok')).toBeTruthy()
    expect(screen.getByText('Contenu autorisé')).toBeTruthy()
    expect(screen.getByRole('link', { name: /Ouvrir la source/i }).getAttribute('href')).toBe(
      '/docs/bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',
    )
    expect(screen.getByTestId('transclusion-denied').textContent).toBe('Contenu non accessible')
    expect(screen.queryByText('Secret')).toBeNull()
    // Titres de la source : ni numéro ni id (absents du sommaire de la page).
    expect(screen.getByText('Titre source').textContent).toBe('Titre source')
  })

  it('applique gras / lien sûr et neutralise les liens dangereux', () => {
    const { container } = view(
      doc({
        type: 'paragraph',
        content: [
          { type: 'text', text: 'Important', marks: [{ type: 'bold' }] },
          { type: 'text', text: ' ' },
          { type: 'text', text: 'ok', marks: [{ type: 'link', attrs: { href: 'https://example.com' } }] },
          { type: 'text', text: ' ' },
          { type: 'text', text: 'ko', marks: [{ type: 'link', attrs: { href: 'javascript:alert(1)' } }] },
          { type: 'text', text: '<img src=x onerror=alert(1)>' },
        ],
      }),
    )
    expect(container.querySelector('strong')?.textContent).toBe('Important')
    const links = [...container.querySelectorAll('a')]
    expect(links).toHaveLength(1)
    expect(links[0]!.getAttribute('href')).toBe('https://example.com')
    expect(container.querySelector('img')).toBeNull()
    expect(container.textContent).toContain('<img src=x onerror=alert(1)>')
  })

  it('surligne les ancres de commentaires (highlightAnchorsHtml)', () => {
    const { container } = view(doc(p('Les comptes de service doivent être revus.')), {
      anchors: [{ exact: 'comptes de service', attached: true }],
    })
    const mark = container.querySelector('mark.comment-mark')
    expect(mark?.textContent).toBe('comptes de service')
  })

  it('ne surligne pas une ancre détachée', () => {
    const { container } = view(doc(p('Les comptes de service.')), {
      anchors: [{ exact: 'comptes de service', attached: false }],
    })
    expect(container.querySelector('mark')).toBeNull()
  })

  it('zone à compléter de modèle : indice, pas de JSON', () => {
    view(doc({ type: 'placeholder', attrs: { hint: 'Décrire le périmètre' } }))
    expect(screen.getByTestId('template-placeholder').textContent).toBe('Décrire le périmètre')
  })
})
