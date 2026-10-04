// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { ChangeSummaryPopover } from './ChangeSummaryPopover'

describe('ChangeSummaryPopover', () => {
  it('valide avec résumé saisi et Ctrl+Entrée', () => {
    const onConfirm = vi.fn()
    render(
      <ChangeSummaryPopover
        label="Enregistrer la version"
        buttonClassName="edit-ghost"
        testId="edit-save-version"
        onConfirm={onConfirm}
      />,
    )
    fireEvent.click(screen.getByTestId('edit-save-version'))
    const input = screen.getByTestId('edit-save-version-input')
    fireEvent.change(input, { target: { value: 'Clarification N2' } })
    fireEvent.keyDown(input, { key: 'Enter', ctrlKey: true })
    expect(onConfirm).toHaveBeenCalledWith('Clarification N2')
  })

  it('valide avec résumé vide (auto serveur)', () => {
    const onConfirm = vi.fn()
    render(
      <ChangeSummaryPopover
        label="Envoyer en révision"
        buttonClassName="edit-cta"
        testId="edit-send-review"
        onConfirm={onConfirm}
      />,
    )
    fireEvent.click(screen.getByTestId('edit-send-review'))
    fireEvent.click(screen.getByTestId('edit-send-review-confirm'))
    expect(onConfirm).toHaveBeenCalledWith('')
  })
})
