// SPDX-License-Identifier: AGPL-3.0-or-later

/** Affiche ⌘K sur Apple, Ctrl+K ailleurs. */
export function isApplePlatform(): boolean {
  if (typeof navigator === 'undefined') return false
  const p = navigator.platform || ''
  const ua = navigator.userAgent || ''
  return /Mac|iPhone|iPad|iPod/i.test(p) || /Mac OS X|iPhone|iPad|iPod/i.test(ua)
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
