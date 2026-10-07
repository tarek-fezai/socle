// SPDX-License-Identifier: LicenseRef-Socle-Proprietary

/** Console compte IdP (Keycloak : issuer + /account). */
export function idpAccountConsoleUrl(issuer: string | undefined | null): string | null {
  const base = issuer?.trim()
  if (!base) return null
  try {
    const url = new URL(base.endsWith('/') ? base : `${base}/`)
    if (!url.protocol.startsWith('http')) return null
    url.pathname = url.pathname.replace(/\/?$/, '/account')
    return url.toString()
  } catch {
    return null
  }
}
