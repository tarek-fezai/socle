import { describe, expect, it } from 'vitest'
import { exportAuditCsv, formatAuditMetadataSummary, type AuditEvent } from './audit'

const base: AuditEvent = {
  id: 1,
  actorId: null,
  actorIsSystem: true,
  actorDisplayName: null,
  actorEmail: null,
  action: 'approval.escalated',
  resourceType: 'document',
  resourceId: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
  metadata: JSON.stringify({ fromStepOrder: 1, toStepOrder: 2 }),
  ipAddress: null,
  createdAt: '2026-09-28T12:00:00Z',
}

describe('audit helpers', () => {
  it('formatAuditMetadataSummary couvre access.granted et document.updated', () => {
    expect(
      formatAuditMetadataSummary({
        ...base,
        action: 'access.granted',
        actorIsSystem: false,
        metadata: JSON.stringify({ relation: 'viewer', subjectId: 'u1' }),
      }),
    ).toBe('Accordé viewer → u1')

    expect(
      formatAuditMetadataSummary({
        ...base,
        action: 'document.updated',
        metadata: JSON.stringify({ status_before: 'brouillon', status_after: 'en_revue' }),
      }),
    ).toBe('Statut brouillon → en_revue')
  })

  it('exportAuditCsv inclut en-têtes et lignes', () => {
    const csv = exportAuditCsv([base])
    expect(csv.split('\n')[0]).toContain('action')
    expect(csv).toContain('approval.escalated')
  })
})
