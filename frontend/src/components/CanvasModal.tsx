// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { ReactNode } from 'react'
import { createPortal } from 'react-dom'

/**
 * Modale calée sur le canvas 1440×900 (CreateSpace / InviteOrgMember).
 * Portail sur `document.body` : aucun ancêtre `position: fixed`.
 * Le dialogue enfant se centre avec `left: 50%` + `translateX(-50%)`.
 */
export function CanvasModal({ onClose, children }: { onClose: () => void; children: ReactNode }) {
  return createPortal(
    <div
      style={{
        position: 'absolute',
        left: 0,
        top: 0,
        width: 1440,
        height: 900,
        zIndex: 50,
      }}
    >
      <div
        style={{
          position: 'relative',
          width: 1440,
          height: 900,
          overflow: 'hidden',
        }}
      >
        <button
          type="button"
          className="absolute inset-0 bg-[rgba(14,14,16,0.45)]"
          aria-label="Fermer"
          onClick={onClose}
        />
        {children}
      </div>
    </div>,
    document.body,
  )
}
