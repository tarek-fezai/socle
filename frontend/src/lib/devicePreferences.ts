// SPDX-License-Identifier: LicenseRef-Socle-Proprietary

export type ColorScheme = 'light' | 'dark' | 'system'
export type FontScale = 'small' | 'normal' | 'large'

const PREFIX = 'socle.prefs.'

const KEYS = {
  colorScheme: `${PREFIX}colorScheme`,
  highContrast: `${PREFIX}highContrast`,
  fontScale: `${PREFIX}fontScale`,
  reduceMotion: `${PREFIX}reduceMotion`,
} as const

export type DevicePreferences = {
  colorScheme: ColorScheme
  highContrast: boolean
  fontScale: FontScale
  reduceMotion: boolean
}

const DEFAULTS: DevicePreferences = {
  colorScheme: 'light',
  highContrast: false,
  fontScale: 'normal',
  reduceMotion: false,
}

function readStorage(key: string): string | null {
  try {
    return localStorage.getItem(key)
  } catch {
    return null
  }
}

function writeStorage(key: string, value: string) {
  try {
    localStorage.setItem(key, value)
  } catch {
    // ignore — quota / private mode
  }
}

export function loadDevicePreferences(): DevicePreferences {
  const colorScheme = readStorage(KEYS.colorScheme)
  const fontScale = readStorage(KEYS.fontScale)
  return {
    colorScheme:
      colorScheme === 'dark' || colorScheme === 'system' || colorScheme === 'light'
        ? colorScheme
        : DEFAULTS.colorScheme,
    highContrast: readStorage(KEYS.highContrast) === '1',
    fontScale:
      fontScale === 'small' || fontScale === 'large' || fontScale === 'normal'
        ? fontScale
        : DEFAULTS.fontScale,
    reduceMotion: readStorage(KEYS.reduceMotion) === '1',
  }
}

export function saveDevicePreferences(patch: Partial<DevicePreferences>) {
  const current = loadDevicePreferences()
  const next = { ...current, ...patch }
  writeStorage(KEYS.colorScheme, next.colorScheme)
  writeStorage(KEYS.highContrast, next.highContrast ? '1' : '0')
  writeStorage(KEYS.fontScale, next.fontScale)
  writeStorage(KEYS.reduceMotion, next.reduceMotion ? '1' : '0')
  applyDevicePreferences(next)
}

export function applyDevicePreferences(prefs: DevicePreferences = loadDevicePreferences()) {
  if (typeof document === 'undefined') return
  const root = document.documentElement
  root.dataset.socleColorScheme = prefs.colorScheme
  root.dataset.socleHighContrast = prefs.highContrast ? 'true' : 'false'
  root.dataset.socleFontScale = prefs.fontScale
  root.dataset.socleReduceMotion = prefs.reduceMotion ? 'true' : 'false'
  const dark =
    prefs.colorScheme === 'dark' ||
    (prefs.colorScheme === 'system' &&
      typeof window !== 'undefined' &&
      window.matchMedia('(prefers-color-scheme: dark)').matches)
  root.classList.toggle('dark', dark)
}
