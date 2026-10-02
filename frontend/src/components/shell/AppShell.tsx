// SPDX-License-Identifier: AGPL-3.0-or-later
import { useCallback, useEffect, useState } from 'react'
import { Outlet } from 'react-router-dom'
import { CommandPalette } from './CommandPalette'
import { MobileMenu } from './MobileMenu'
import { MobileTabBar } from './MobileTabBar'
import { MobileTopBar } from './MobileTopBar'
import { ShellHeader } from './ShellHeader'
import { ShellSidebar } from './ShellSidebar'
import { isApplePlatform } from './shellUtils'
import './appshell.css'

/** Shell authentifié : sidebar + header (desktop), top bar + drawer + tabs (mobile). */
export function AppShellLayout() {
  const [paletteOpen, setPaletteOpen] = useState(false)
  const [menuOpen, setMenuOpen] = useState(false)

  const openSearch = useCallback(() => {
    setMenuOpen(false)
    setPaletteOpen(true)
  }, [])

  const closeSearch = useCallback(() => setPaletteOpen(false), [])

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
    <div className="shell-root" data-mock-id="shell-root">
      <div className="shell-desktop" data-mock-id="shell-frame">
        <ShellSidebar onOpenSearch={openSearch} />
        <div className="shell-main-col">
          <MobileTopBar onOpenMenu={() => setMenuOpen(true)} />
          <ShellHeader />
          <div className="shell-outlet" data-mock-id="shell-outlet">
            <Outlet />
          </div>
          <MobileTabBar />
        </div>
      </div>
      <MobileMenu open={menuOpen} onClose={() => setMenuOpen(false)} onOpenSearch={openSearch} />
      <CommandPalette open={paletteOpen} onClose={closeSearch} />
    </div>
  )
}
