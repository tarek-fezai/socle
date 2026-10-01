// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { DocumentEditor } from './DocumentEditor'
import { countPlaceholders } from '../lib/templates'

const withPlaceholder = {
  type: 'doc',
  content: [
    { type: 'paragraph', content: [{ type: 'text', text: 'Intro' }] },
    { type: 'placeholder', attrs: { hint: 'Décrire l’objet' } },
  ],
}

describe('DocumentEditor — zones à compléter', () => {
  it('rend une zone à compléter avec son indice', async () => {
    const { container } = render(<DocumentEditor content={withPlaceholder} onChange={() => undefined} />)
    await waitFor(() => expect(container.querySelector('.socle-placeholder')).not.toBeNull())
    expect(container.querySelector('.socle-placeholder')?.textContent).toBe('Décrire l’objet')
  })

  it('n’affiche les outils de modèle qu’avec templateTools', async () => {
    const { rerender } = render(<DocumentEditor content={withPlaceholder} onChange={() => undefined} />)
    await screen.findByRole('button', { name: 'Gras' })
    expect(screen.queryByTestId('template-tools')).toBeNull()
    rerender(<DocumentEditor content={withPlaceholder} onChange={() => undefined} templateTools />)
    expect(await screen.findByTestId('template-tools')).toBeTruthy()
    for (const v of ['{{date}}', '{{auteur}}', '{{espace}}', '{{titre}}']) {
      expect(screen.getByRole('button', { name: v })).toBeTruthy()
    }
  })

  it('insère une zone à compléter et une variable texte', async () => {
    const onChange = vi.fn()
    render(
      <DocumentEditor
        content={{ type: 'doc', content: [{ type: 'paragraph' }] }}
        onChange={onChange}
        templateTools
      />,
    )
    fireEvent.change(await screen.findByLabelText('Indice de la zone à compléter'), {
      target: { value: 'Périmètre' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Zone à compléter' }))
    await waitFor(() => expect(onChange).toHaveBeenCalled())
    let last = onChange.mock.calls.at(-1)?.[0]
    expect(countPlaceholders(last)).toBe(1)
    expect(JSON.stringify(last)).toContain('Périmètre')

    fireEvent.click(screen.getByRole('button', { name: '{{date}}' }))
    await waitFor(() => expect(JSON.stringify(onChange.mock.calls.at(-1)?.[0])).toContain('{{date}}'))
    last = onChange.mock.calls.at(-1)?.[0]
    expect(countPlaceholders(last)).toBe(1)
  })
})
