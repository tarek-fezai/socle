import { describe, expect, it } from 'vitest'
import { editLockBannerText, type EditLockStatus } from './editLock'

describe('editLockBannerText', () => {
  it('affiche le holder autre que soi', () => {
    const lock: EditLockStatus = {
      active: true,
      holderUserId: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
      holderDisplayName: 'Alice',
      acquiredAt: new Date(Date.now() - 5 * 60_000).toISOString(),
      heartbeatAt: new Date().toISOString(),
      heldByCurrentUser: false,
      ttlSeconds: 45,
      heartbeatSeconds: 15,
    }
    expect(editLockBannerText(lock)).toContain('Alice')
    expect(editLockBannerText(lock)).toContain('5 min')
  })

  it('masque la bannière pour le holder courant ou lock inactif', () => {
    expect(
      editLockBannerText({
        active: true,
        holderUserId: 'x',
        holderDisplayName: 'Alice',
        acquiredAt: new Date().toISOString(),
        heartbeatAt: new Date().toISOString(),
        heldByCurrentUser: true,
        ttlSeconds: 45,
        heartbeatSeconds: 15,
      }),
    ).toBeNull()
    expect(
      editLockBannerText({
        active: false,
        holderUserId: null,
        holderDisplayName: null,
        acquiredAt: null,
        heartbeatAt: null,
        heldByCurrentUser: false,
        ttlSeconds: 45,
        heartbeatSeconds: 15,
      }),
    ).toBeNull()
  })
})
