// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, expect, it } from 'vitest'
import {
  formatMention,
  highlightAnchorsHtml,
  mentionWarningsDisplay,
  parseMentions,
  renderCommentMarkdown,
  sanitizeCommentBody,
} from './comments'

describe('sanitizeCommentBody', () => {
  it('supprime les balises HTML et les schémas dangereux', () => {
    expect(sanitizeCommentBody('Hello <script>alert(1)</script> world')).toBe('Hello alert(1) world')
    expect(sanitizeCommentBody('voir javascript:alert(1) ici')).toBe('voir alert(1) ici')
    expect(sanitizeCommentBody('data:text/html,x reste')).toBe('text/html,x reste')
  })

  it('neutralise les liens non http(s) sans casser les mentions', () => {
    expect(sanitizeCommentBody('[x](javascript:alert(1))')).toBe('[x](#)')
    expect(sanitizeCommentBody('[ok](https://example.com/a)')).toBe('[ok](https://example.com/a)')
    const id = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'
    expect(sanitizeCommentBody(`Bonjour @[Claire](${id})`)).toBe(`Bonjour @[Claire](${id})`)
  })

  it('rejette un corps vide', () => {
    expect(() => sanitizeCommentBody('   ')).toThrow(/vide/)
    expect(() => sanitizeCommentBody('<b></b>')).toThrow(/vide/)
  })
})

describe('renderCommentMarkdown', () => {
  it('rend gras, italique, code et liens https', () => {
    const html = renderCommentMarkdown('**gras** et *ital* et `code` et [lien](https://ex.com)')
    expect(html).toContain('<strong>gras</strong>')
    expect(html).toContain('<em>ital</em>')
    expect(html).toContain('<code>code</code>')
    expect(html).toContain('href="https://ex.com"')
    expect(html).toContain('rel="noopener noreferrer"')
  })

  it('échappe le HTML injecté et refuse javascript:', () => {
    const html = renderCommentMarkdown('danger <img src=x onerror=1> [bad](javascript:alert(1))')
    expect(html).not.toContain('<img')
    expect(html).toContain('href="#"')
  })

  it('rend les mentions @[Name](uuid)', () => {
    const id = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'
    const html = renderCommentMarkdown(`Bonjour @[Claire](${id})`)
    expect(html).toContain('comment-mention')
    expect(html).toContain(`data-user-id="${id}"`)
    expect(html).toContain('@Claire')
  })
})

describe('parseMentions / formatMention', () => {
  it('parse @[Name](uuid) et @handle', () => {
    const id = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb'
    const parsed = parseMentions(`cc @[Yanis M.](${id}) et @tarek`)
    expect(parsed).toEqual([
      { kind: 'ref', name: 'Yanis M.', userId: id, raw: `@[Yanis M.](${id})`, index: 3 },
      { kind: 'handle', handle: 'tarek', raw: '@tarek', index: expect.any(Number) },
    ])
  })

  it('formatMention produit le format attendu', () => {
    expect(formatMention('Claire Dubois', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa')).toBe(
      '@[Claire Dubois](aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa)',
    )
  })
})

describe('mentionWarningsDisplay', () => {
  it('retourne une liste vide sans warnings', () => {
    expect(mentionWarningsDisplay(undefined)).toEqual([])
    expect(mentionWarningsDisplay([])).toEqual([])
  })

  it('affiche le message API ou un fallback', () => {
    expect(
      mentionWarningsDisplay([
        {
          userId: 'u1',
          displayName: 'Bob',
          message: "Bob n'a pas accès à cette page",
        },
        { userId: 'u2', displayName: 'Alice', message: '  ' },
      ]),
    ).toEqual(["Bob n'a pas accès à cette page", "Alice n'a pas accès à cette page"])
  })
})

describe('highlightAnchorsHtml', () => {
  it('entoure les ancres attachées', () => {
    const html = highlightAnchorsHtml('Alpha beta gamma', [
      { exact: 'beta', attached: true },
    ])
    expect(html).toContain('<mark class="comment-mark comment-mark-1">beta</mark>')
  })

  it('ignore les ancres détachées', () => {
    const html = highlightAnchorsHtml('Alpha beta', [{ exact: 'beta', attached: false }])
    expect(html).toBe('Alpha beta')
    expect(html).not.toContain('<mark')
  })
})
