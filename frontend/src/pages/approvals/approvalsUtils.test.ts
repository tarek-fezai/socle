// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { describe, expect, it } from 'vitest'
import {
  approveLabel,
  buildCircuit,
  circuitStepLabel,
  circuitStepLabelMobile,
  ledeTail,
  requesterInitials,
  requesterName,
} from './approvalsUtils'

const workflow = {
  steps: [
    { stepOrder: 2, approverRoleName: 'Propriétaire' },
    { stepOrder: 1, approverRoleName: 'Responsable' },
    { stepOrder: 3, approverRoleName: 'Qualité' },
  ],
}

describe('buildCircuit', () => {
  it('étapes triées : précédentes approuvées, courante en attente, suivantes à venir', () => {
    const c = buildCircuit(workflow, 2)
    expect(c.simplified).toBe(false)
    expect(c.steps.map((s) => [s.order, s.state])).toEqual([
      [1, 'done'],
      [2, 'current'],
      [3, 'todo'],
    ])
    expect(circuitStepLabel(c.steps[1]!)).toBe('N2 · Propriétaire')
    expect(circuitStepLabelMobile(c.steps[0]!)).toBe('Responsable (N1)')
  })

  it('A1 : sans circuit applicable, 1…courante sans rôle', () => {
    const c = buildCircuit(null, 2)
    expect(c.simplified).toBe(true)
    expect(c.steps.map((s) => [s.order, s.state, s.roleName])).toEqual([
      [1, 'done', null],
      [2, 'current', null],
    ])
    expect(circuitStepLabel(c.steps[0]!)).toBe('N1')
  })

  it('A1 : étape courante absente du circuit → repli simplifié', () => {
    expect(buildCircuit(workflow, 7).simplified).toBe(true)
  })
})

describe('libellés', () => {
  it('demandeur', () => {
    expect(requesterName({ requestedByDisplayName: ' Claire Dubois ' })).toBe('Claire Dubois')
    expect(requesterName({ requestedByDisplayName: null })).toBe('Un utilisateur')
    expect(requesterInitials({ requestedByDisplayName: 'Claire Dubois', requestedByInitials: 'CD' })).toBe('CD')
    expect(requesterInitials({ requestedByDisplayName: 'Claire Dubois', requestedByInitials: null })).toBe('CD')
    expect(requesterInitials({ requestedByDisplayName: null, requestedByInitials: null })).toBe('?')
  })

  it('CTA et résumé', () => {
    expect(approveLabel(13)).toBe('Approuver la révision v13')
    expect(approveLabel(null)).toBe('Approuver')
    expect(ledeTail('Le nouveau flux')).toBe(', incluant le nouveau flux')
    expect(ledeTail('  ')).toBe('')
  })
})
