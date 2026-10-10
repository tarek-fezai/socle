// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { AssistantHints } from './EditAssistantPanel'

describe('carte « Paragraphe long » (clavier)', () => {
  const renderCard = () => {
    const onGoToParagraph = vi.fn()
    render(
      <AssistantHints
        spaceId="s1"
        threshold={60}
        longParagraphs={[{ index: 3, wordCount: 61, excerpt: 'Les accès…' }]}
        brokenLinks={[]}
        onGoToParagraph={onGoToParagraph}
      />,
    )
    return { onGoToParagraph, card: screen.getByRole('button', { name: /aller au paragraphe \(61 mots\)/i }) }
  }

  it('est focusable, nomme le nombre de mots et garde son title', () => {
    const { card } = renderCard()
    expect(card.getAttribute('tabindex')).toBe('0')
    expect(card.getAttribute('aria-label')).toBe('Paragraphe long : aller au paragraphe (61 mots)')
    expect(card.getAttribute('title')).toBe('Aller au paragraphe (61 mots)')
    card.focus()
    expect(document.activeElement).toBe(card)
  })

  it('Entrée et Espace mènent au paragraphe ; les autres touches non', () => {
    const { card, onGoToParagraph } = renderCard()
    fireEvent.keyDown(card, { key: 'Tab' })
    fireEvent.keyDown(card, { key: 'a' })
    expect(onGoToParagraph).not.toHaveBeenCalled()
    fireEvent.keyDown(card, { key: 'Enter' })
    fireEvent.keyDown(card, { key: ' ' })
    expect(onGoToParagraph).toHaveBeenCalledTimes(2)
    expect(onGoToParagraph).toHaveBeenNthCalledWith(1, 3)
    expect(onGoToParagraph).toHaveBeenNthCalledWith(2, 3)
  })

  it('Espace n’ajoute pas de défilement de page (preventDefault)', () => {
    const { card } = renderCard()
    const notCancelled = fireEvent.keyDown(card, { key: ' ' })
    expect(notCancelled).toBe(false)
  })
})
