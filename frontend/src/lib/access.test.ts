// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, expect, it } from 'vitest'
import { accessErrorMessage, sourceLabel } from './access'

describe('access helpers', () => {
  it('labels sources for direct vs inherited', () => {
    expect(sourceLabel('direct')).toBe('Direct')
    expect(sourceLabel('group')).toBe('Via groupe')
    expect(sourceLabel('inherited')).toBe('Hérité')
  })

  it('maps 503 to clear non-optimistic message', () => {
    const msg = accessErrorMessage({
      response: { status: 503, data: { message: 'Journal indisponible' } },
    })
    expect(msg).toContain('Journal indisponible')
  })

  it('maps bare 503 without body', () => {
    const msg = accessErrorMessage({ response: { status: 503 } })
    expect(msg).toMatch(/aucun changement n'a été appliqué/i)
  })
})
