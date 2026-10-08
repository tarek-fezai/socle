// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { useCallback, useEffect, useMemo, useState } from 'react'
import { Outlet, useLocation } from 'react-router-dom'
import { CommandPalette } from './CommandPalette'
import { MobileMenu } from './MobileMenu'
import { MobileTabBar } from './MobileTabBar'
import { MobileTopBar } from './MobileTopBar'
import { ShellHeader } from './ShellHeader'
import { ShellSidebar } from './ShellSidebar'
import {
  hasOwnDocumentChrome,
  isAccountPath,
  isAdminWorkspacePath,
  isApplePlatform,
  type ShellOutletContext,
} from './shellUtils'
import './appshell.css'

/** Shell authentifié : sidebar + header (desktop), top bar + drawer + tabs (mobile). */
export function AppShellLayout() {
  const [paletteOpen, setPaletteOpen] = useState(false)
  const [menuOpen, setMenuOpen] = useState(false)
  const { pathname } = useLocation()
  // Page de lecture : barre haute + onglets fournis par la page (Main.dc.html / MobilePage.dc.html).
  const ownChrome = hasOwnDocumentChrome(pathname)
  const adminWorkspace = isAdminWorkspacePath(pathname)
  const accountPage = isAccountPath(pathname)
  const hideShellChrome = adminWorkspace || accountPage

  const openSearch = useCallback(() => {
    setMenuOpen(false)
    setPaletteOpen(true)
  }, [])

  const closeSearch = useCallback(() => setPaletteOpen(false), [])

  const outletContext = useMemo<ShellOutletContext>(
    () => ({ openMenu: () => setMenuOpen(true), openSearch }),
    [openSearch],
  )

  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      const apple = isApplePlatform()
      const mod = apple ? e.metaKey : e.ctrlKey
      if (mod && (e.key === 'k' || e.key === 'K')) {
        e.preventDefault()
        setPaletteOpen((v) => !v)
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [])

  return (
    <div
      className={`shell-root${hideShellChrome ? ' shell-root--admin-workspace' : ''}`}
      data-mock-id="shell-root"
    >
      <div className="shell-desktop" data-mock-id="shell-frame">
        {!hideShellChrome && <ShellSidebar onOpenSearch={openSearch} />}
        <div className="shell-main-col">
          {!ownChrome && !hideShellChrome && (
            <MobileTopBar onOpenMenu={() => setMenuOpen(true)} />
          )}
          {!ownChrome && !hideShellChrome && <ShellHeader />}
          <div className="shell-outlet" data-mock-id="shell-outlet">
            <Outlet context={outletContext} />
          </div>
          {!ownChrome && !hideShellChrome && <MobileTabBar />}
        </div>
      </div>
      <MobileMenu open={menuOpen} onClose={() => setMenuOpen(false)} onOpenSearch={openSearch} />
      <CommandPalette open={paletteOpen} onClose={closeSearch} />
    </div>
  )
}
