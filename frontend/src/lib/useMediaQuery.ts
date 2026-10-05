// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { useEffect, useState } from 'react'

function matches(query: string): boolean {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return false
  return window.matchMedia(query).matches
}

/** Suit une media query CSS (faux hors navigateur / jsdom sans `matchMedia`). */
export function useMediaQuery(query: string): boolean {
  const [value, setValue] = useState(() => matches(query))
  useEffect(() => {
    if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return
    const mql = window.matchMedia(query)
    const onChange = () => setValue(mql.matches)
    onChange()
    mql.addEventListener?.('change', onChange)
    return () => mql.removeEventListener?.('change', onChange)
  }, [query])
  return value
}

/** Même seuil que `document-page.css` (mobile ≤ 767 px). */
export const MOBILE_QUERY = '(max-width: 767px)'

export function useIsMobile(): boolean {
  return useMediaQuery(MOBILE_QUERY)
}
