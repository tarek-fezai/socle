// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import {
  DocumentReliabilityStatus,
  FolderReliabilityRail,
} from '../components/ReliabilityDisplay'
import {
  averageReliabilityScores,
  formatReliabilityPercent,
  reliabilityStatusLabel,
  reliabilityTone,
} from '../lib/reliability'

describe('reliability helpers', () => {
  it('ne transforme jamais null en 0%', () => {
    expect(reliabilityStatusLabel(null)).toBe('Non évalué')
    expect(reliabilityStatusLabel(undefined)).toBe('Non évalué')
    expect(reliabilityTone(null)).toBe('none')
  })

  it('formate un score valide', () => {
    expect(formatReliabilityPercent(91)).toBe('91%')
    expect(formatReliabilityPercent(77.5)).toBe('77.5%')
    expect(reliabilityStatusLabel(91)).toContain('91%')
    expect(reliabilityTone(91)).toBe('success')
    expect(reliabilityTone(60)).toBe('warn')
    expect(reliabilityTone(20)).toBe('danger')
  })

  it('moyenne uniquement sur scores non-NULL', () => {
    expect(averageReliabilityScores([90, null, 80, undefined])).toBe(85)
    expect(averageReliabilityScores([null, undefined])).toBeNull()
    expect(averageReliabilityScores([])).toBeNull()
  })
})

describe('DocumentReliabilityStatus', () => {
  it('document valide avec score → pourcentage + date de calcul', () => {
    render(
      <DocumentReliabilityStatus
        score={87.5}
        computedAt="2026-09-28T10:00:00Z"
        status="valide"
        createdAt="2026-01-15T00:00:00Z"
      />,
    )
    expect(screen.getByTestId('doc-reliability-status').textContent).toMatch(/87\.5%/)
    expect(screen.getByTestId('reliability-computed-at').textContent).toMatch(/Calculé le/)
    expect(screen.queryByTestId('reliability-unevaluated')).toBeNull()
  })

  it('document brouillon / score NULL → Non évalué, jamais 0%', () => {
    render(
      <DocumentReliabilityStatus score={null} status="brouillon" createdAt="2026-01-15T00:00:00Z" />,
    )
    expect(screen.getByTestId('reliability-unevaluated').textContent).toBe('Non évalué')
    expect(screen.getByTestId('doc-reliability-status').textContent).not.toMatch(/0%/)
  })

  it('en_revue et archive → Non évalué', () => {
    const { rerender } = render(
      <DocumentReliabilityStatus score={null} status="en_revue" />,
    )
    expect(screen.getByText('Non évalué')).toBeTruthy()
    rerender(<DocumentReliabilityStatus score={undefined} status="archive" />)
    expect(screen.getByText('Non évalué')).toBeTruthy()
    expect(screen.queryByText(/0%/)).toBeNull()
  })
})

describe('FolderReliabilityRail', () => {
  it('moyenne des scores mixtes (ignore NULL)', () => {
    render(<FolderReliabilityRail scores={[90, null, 70]} caption="2 validés · 1 en revue" />)
    expect(screen.getByTestId('folder-reliability-rail').textContent).toMatch(/80%/)
    expect(screen.getByTestId('folder-reliability-rail').textContent).toMatch(/moyenne du dossier/)
    expect(screen.queryByTestId('reliability-unevaluated')).toBeNull()
  })

  it('aucun score → Non évalué, pas de NaN%', () => {
    render(<FolderReliabilityRail scores={[null, undefined, null]} />)
    expect(screen.getByTestId('reliability-unevaluated').textContent).toBe('Non évalué')
    const text = screen.getByTestId('folder-reliability-rail').textContent ?? ''
    expect(text).not.toMatch(/NaN/)
    expect(text).not.toMatch(/0%/)
  })
})
