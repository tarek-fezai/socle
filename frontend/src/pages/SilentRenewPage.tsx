// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { useEffect } from 'react'
import { handleSilentRenew } from '../lib/auth'

/** Target of oidc-client-ts silent renew iframe (PKCE). */
export function SilentRenewPage() {
  useEffect(() => {
    void handleSilentRenew().catch(() => {
      /* iframe parent observes UserManager events */
    })
  }, [])

  return (
    <main className="mx-auto max-w-lg px-6 py-20 text-center text-socle-slate">
      Renouvellement de session…
    </main>
  )
}
