// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
/** Pastilles des maquettes CreateSpace / SpaceSettings. */
export const SPACE_SWATCHES = ['#3730E0', '#0D8A7C', '#B7791F', '#7C3AED', '#1E8E5A', '#B54708'] as const

export function initialsFromName(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean)
  if (parts.length === 0) return '?'
  if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase()
  return `${parts[0][0] ?? ''}${parts[parts.length - 1][0] ?? ''}`.toUpperCase()
}
