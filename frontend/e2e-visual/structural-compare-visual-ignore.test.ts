// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
/**
 * Prouve que data-visual-ignore exclut les badges du texte structural
 * sans masquer un renommage de libellé voisin.
 */
import { describe, expect, it } from 'vitest'
import { JSDOM } from 'jsdom'

/** Miroir de la logique spacedText / visibleText de structural-compare.mjs */
function visibleTextFromHtml(html: string): string {
  const dom = new JSDOM(`<!DOCTYPE html><html><body>${html}</body></html>`)
  const el = dom.window.document.body.firstElementChild
  if (!el) return ''
  const normText = (t: string | null) => (t || '').replace(/\s+/g, ' ').trim()
  const parts: string[] = []
  const walk = (node: Node) => {
    if (node.nodeType === dom.window.Node.TEXT_NODE) {
      const t = normText(node.textContent)
      if (t) parts.push(t)
      return
    }
    if (node.nodeType !== dom.window.Node.ELEMENT_NODE) return
    const element = node as Element
    const tag = element.tagName
    if (tag === 'SCRIPT' || tag === 'STYLE' || tag === 'SVG') return
    if (element.hasAttribute('data-visual-ignore')) return
    for (const c of element.childNodes) walk(c)
  }
  walk(el)
  return parts.join(' ')
}

describe('data-visual-ignore in structural text', () => {
  it('exclut le badge Bientôt mais conserve le libellé', () => {
    const html = `<nav data-mock-id="admin-subnav">
      <span class="admin-nav-item">Membres &amp; équipes<span class="admin-nav-soon" data-visual-ignore>Bientôt</span></span>
      <a class="admin-nav-item">Tags</a>
    </nav>`
    expect(visibleTextFromHtml(html)).toBe('Membres & équipes Tags')
  })

  it('détecte toujours un libellé renommé (pas une exclusion en masse)', () => {
    const maquette = `<nav><a>Personnalisation de marque</a><a>Tags</a></nav>`
    const appRenamed = `<nav><a>Personnalisation</a><a>Tags</a></nav>`
    expect(visibleTextFromHtml(maquette)).not.toBe(visibleTextFromHtml(appRenamed))
    expect(visibleTextFromHtml(appRenamed)).toContain('Personnalisation')
    expect(visibleTextFromHtml(appRenamed)).not.toContain('Personnalisation de marque')
  })
})
