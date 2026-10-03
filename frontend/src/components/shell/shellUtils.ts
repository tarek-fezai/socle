// SPDX-License-Identifier: AGPL-3.0-or-later

/** Affiche ⌘K sur Apple, Ctrl+K ailleurs. */
export function isApplePlatform(): boolean {
  if (typeof navigator === 'undefined') return false
  const p = navigator.platform || ''
  const ua = navigator.userAgent || ''
  return /Mac|iPhone|iPad|iPod/i.test(p) || /Mac OS X|iPhone|iPad|iPod/i.test(ua)
}

/**
 * `/docs/:id` (page de lecture) — porte sa propre barre haute (breadcrumb + actions) et ses
 * onglets mobiles : le shell masque alors `ShellHeader`, `MobileTopBar` et `MobileTabBar`.
 * `/docs`, `/docs/new`, `/docs/:id/edit|history|export|view` ne sont pas concernés.
 */
export function isDocumentReadPath(pathname: string): boolean {
  const m = /^\/docs\/([^/]+)\/?$/.exec(pathname)
  return Boolean(m) && m![1] !== 'new'
}

/**
 * `/docs/:id/edit` (écran Modifier, Edit.dc.html) - barre haute, onglets et barre mobile fournis
 * par la page : le shell masque aussi `ShellHeader`, `MobileTopBar` et `MobileTabBar`.
 */
export function isDocumentEditPath(pathname: string): boolean {
  const m = /^\/docs\/([^/]+)\/edit\/?$/.exec(pathname)
  return Boolean(m) && m![1] !== 'new'
}

/** Pages documentaires qui portent leur propre chrome (lecture, modification). */
export function hasOwnDocumentChrome(pathname: string): boolean {
  return isDocumentReadPath(pathname) || isDocumentEditPath(pathname)
}

/** Actions du shell exposées aux pages qui remplacent la barre mobile (via `<Outlet context>`). */
export type ShellOutletContext = {
  openMenu: () => void
  openSearch: () => void
}

export function searchShortcutLabel(): string {
  return isApplePlatform() ? '⌘K' : 'Ctrl+K'
}

const EXPANDED_KEY = 'socle.shell.expandedSpaces'

export function loadExpandedSpaces(): Set<string> {
  try {
    const raw = localStorage.getItem(EXPANDED_KEY)
    if (!raw) return new Set()
    const arr = JSON.parse(raw) as unknown
    if (!Array.isArray(arr)) return new Set()
    return new Set(arr.filter((x): x is string => typeof x === 'string'))
  } catch {
    return new Set()
  }
}

export function saveExpandedSpaces(ids: Set<string>) {
  try {
    localStorage.setItem(EXPANDED_KEY, JSON.stringify([...ids]))
  } catch {
    // ignore
  }
}

export function initialsFromName(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean)
  if (parts.length === 0) return '?'
  if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase()
  return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase()
}
