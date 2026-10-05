// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { describe, expect, it } from 'vitest'
import { formatFrInteger, formatRelativeFr, formatShortDateFr } from './relativeTime'

describe('relativeTime', () => {
  const now = Date.parse('2026-09-10T12:00:00.000Z')

  it('formate les relatifs FR courts', () => {
    expect(formatRelativeFr(new Date(now - 20 * 60_000).toISOString(), now)).toBe('il y a 20 min')
    expect(formatRelativeFr(new Date(now - 60 * 60_000).toISOString(), now)).toBe('il y a 1h')
    expect(formatRelativeFr(new Date(now - 26 * 60 * 60_000).toISOString(), now)).toBe('hier')
  })

  it('formate les entiers FR', () => {
    const s = formatFrInteger(4302)
    expect(s.replace(/\s/g, ' ')).toMatch(/4.?302/)
  })

  it('formate une date courte FR', () => {
    const s = formatShortDateFr('2026-08-30T10:00:00.000Z')
    expect(s).toMatch(/30/)
    expect(s).toMatch(/2026/)
  })
})
