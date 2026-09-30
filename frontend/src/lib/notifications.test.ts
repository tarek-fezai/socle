import { describe, expect, it } from 'vitest'
import { formatNotificationMessage, notificationResourceLink } from './notifications'

describe('notifications helpers', () => {
  it('formatNotificationMessage — approval_chain_exhausted', () => {
    expect(
      formatNotificationMessage({
        id: '1',
        type: 'approval_chain_exhausted',
        payload: { document_id: 'd' },
        documentTitle: 'Charte',
        readAt: null,
        createdAt: '2026-01-01T00:00:00Z',
      }),
    ).toBe(
      'La demande d\'approbation pour « Charte » est restée sans décision après plusieurs étapes.',
    )
  })

  it('notificationResourceLink pointe vers le document', () => {
    expect(
      notificationResourceLink({
        id: '1',
        type: 'approval_chain_exhausted',
        payload: { document_id: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa' },
        documentTitle: null,
        readAt: null,
        createdAt: '2026-01-01T00:00:00Z',
      }),
    ).toBe('/docs/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa')
  })

  it('formatNotificationMessage — external_reference_first', () => {
    expect(
      formatNotificationMessage({
        id: '2',
        type: 'external_reference_first',
        payload: {
          message: 'Un document de l\'espace « B » référence désormais (transclusion) un contenu de « A ».',
          target_space_id: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
        },
        documentTitle: null,
        readAt: null,
        createdAt: '2026-01-01T00:00:00Z',
      }),
    ).toContain('référence désormais')
    expect(
      notificationResourceLink({
        id: '2',
        type: 'external_reference_first',
        payload: { target_space_id: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa' },
        documentTitle: null,
        readAt: null,
        createdAt: '2026-01-01T00:00:00Z',
      }),
    ).toBe('/spaces/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa')
  })
})
