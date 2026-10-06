// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import { PlaceholderBanner } from './PlaceholderBanner'

const ph = { type: 'placeholder', attrs: { hint: 'À remplir' } }

describe('PlaceholderBanner', () => {
  it('n’affiche rien sans zone à compléter', () => {
    const { container } = render(
      <PlaceholderBanner body={{ type: 'doc', content: [{ type: 'paragraph' }] }} />,
    )
    expect(container.firstChild).toBeNull()
  })

  it('affiche « 1 zone à compléter » (singulier)', () => {
    render(<PlaceholderBanner body={{ type: 'doc', content: [ph] }} />)
    expect(screen.getByRole('status').textContent).toContain('1 zone à compléter')
  })

  it('affiche « N zones à compléter » (pluriel, zones imbriquées)', () => {
    render(
      <PlaceholderBanner
        body={{
          type: 'doc',
          content: [ph, { type: 'bulletList', content: [{ type: 'listItem', content: [ph] }] }, ph],
        }}
      />,
    )
    expect(screen.getByTestId('placeholder-banner').textContent).toContain('3 zones à compléter')
  })
})
