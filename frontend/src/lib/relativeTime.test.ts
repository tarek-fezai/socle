// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { describe, expect, it } from 'vitest'
import { formatFrInteger, formatRelativeFr, formatShortDateFr } from './relativeTime'

describe('relativeTime', () => {
  const now = Date.parse('2026-09-10T12:00:00.000Z')

  const ago = (ms: number) => formatRelativeFr(new Date(now - ms).toISOString(), now)
  const MIN = 60_000
  const HOUR = 60 * MIN
  const DAY = 24 * HOUR

  it('formate les relatifs FR courts de la maquette', () => {
    expect(ago(20 * MIN)).toBe('il y a 20 min')
    expect(ago(HOUR)).toBe('il y a 1h')
    expect(ago(3 * HOUR)).toBe('il y a 3h')
    expect(ago(26 * HOUR)).toBe('hier')
  })

  it('tronque aux bornes d’unité', () => {
    expect(ago(59 * 1000)).toBe('à l’instant')
    expect(ago(MIN)).toBe('il y a 1 min')
    expect(ago(59 * MIN + 59 * 1000)).toBe('il y a 59 min')
    expect(ago(HOUR + 59 * MIN)).toBe('il y a 1h')
    expect(ago(23 * HOUR + 59 * MIN)).toBe('il y a 23h')
    expect(ago(DAY)).toBe('hier')
    expect(ago(2 * DAY)).toBe('il y a 2 j')
    expect(ago(6 * DAY + 23 * HOUR)).toBe('il y a 6 j')
  })

  it('date courte au-delà d’une semaine, futur = à l’instant, ISO invalide = vide', () => {
    expect(ago(7 * DAY)).toBe(formatShortDateFr(new Date(now - 7 * DAY).toISOString()))
    expect(ago(-5 * MIN)).toBe('à l’instant')
    expect(formatRelativeFr('pas une date', now)).toBe('')
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
