// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { useEffect, useId, useRef, useState, type KeyboardEvent } from 'react'
import { Link } from 'react-router-dom'
import { useAuth } from '../../auth/AuthProvider'
import { initialsFromName } from './shellUtils'

type Props = {
  className?: string
  /** compact = header chip ; default = sidebar row */
  variant?: 'sidebar' | 'compact'
}

export function ShellUserMenu({ className = '', variant = 'sidebar' }: Props) {
  const { me, logout } = useAuth()
  const [open, setOpen] = useState(false)
  const rootRef = useRef<HTMLDivElement>(null)
  const menuId = useId()
  const displayName = me?.displayName ?? 'Compte'
  const initials = me?.avatarInitials || initialsFromName(displayName)

  useEffect(() => {
    if (!open) return
    function onDoc(e: MouseEvent) {
      if (!rootRef.current?.contains(e.target as Node)) setOpen(false)
    }
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape') setOpen(false)
    }
    document.addEventListener('mousedown', onDoc)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onDoc)
      document.removeEventListener('keydown', onKey)
    }
  }, [open])

  function toggle() {
    setOpen((v) => !v)
  }

  function onTriggerKey(e: KeyboardEvent) {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault()
      toggle()
    }
  }

  return (
    <div className={`shell-user-menu${className ? ` ${className}` : ''}`} ref={rootRef}>
      <button
        type="button"
        className={variant === 'compact' ? 'shell-user-menu__trigger--compact' : 'shell-user'}
        data-mock-id="shell-user"
        data-testid="shell-user-menu-trigger"
        aria-haspopup="menu"
        aria-expanded={open}
        aria-controls={menuId}
        onClick={toggle}
        onKeyDown={onTriggerKey}
      >
        <span className="shell-avatar" aria-hidden>
          {initials}
        </span>
        {variant === 'sidebar' ? (
          <span className="shell-user-name">{displayName}</span>
        ) : null}
      </button>
      {open ? (
        <div
          id={menuId}
          className="shell-user-menu__panel"
          role="menu"
          data-testid="shell-user-menu"
        >
          <Link
            to="/account"
            role="menuitem"
            className="shell-user-menu__item"
            onClick={() => setOpen(false)}
          >
            Paramètres du compte
          </Link>
          <span
            role="menuitem"
            aria-disabled="true"
            className="shell-user-menu__item shell-user-menu__item--disabled"
          >
            Centre d&apos;aide
            <span className="shell-user-menu__soon">Bientôt</span>
          </span>
          <button
            type="button"
            role="menuitem"
            className="shell-user-menu__item shell-user-menu__item--logout"
            onClick={() => {
              setOpen(false)
              void logout()
            }}
          >
            Se déconnecter
          </button>
        </div>
      ) : null}
    </div>
  )
}
