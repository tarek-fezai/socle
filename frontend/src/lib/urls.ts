// SPDX-License-Identifier: LicenseRef-Socle-Proprietary

/** Same-origin API base when `VITE_API_BASE_URL` is empty or unset (production / Vite proxy in dev). */
export function apiBaseUrl(): string {
  return (import.meta.env.VITE_API_BASE_URL ?? '').replace(/\/$/, '')
}

/** Yjs WebSocket URL: explicit env, or derived from the current page origin. */
export function yjsWebSocketUrl(): string {
  const configured = import.meta.env.VITE_YJS_WS_URL
  if (configured !== undefined && configured !== '') {
    return configured.replace(/\/$/, '')
  }
  if (typeof window === 'undefined') {
    return ''
  }
  const wsProtocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  return `${wsProtocol}//${window.location.host}/ws`
}
