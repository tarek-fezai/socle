// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, expect, it } from 'vitest'
import type { EditLockStatus } from '../../lib/editLock'
import {
  EDITOR_NODE_TYPES,
  countWordsFromTipTap,
  findUnsupportedContent,
  formatClockFr,
  formatWordStats,
  hasUnsupportedContent,
  lockHeldByOther,
  presenceAvatars,
  readingMinutes,
  reliabilityShortLabel,
  reviewButtonState,
  reviewCadenceLabel,
  saveStatusText,
} from './documentEditUtils'

const p = (text: string) => ({ type: 'paragraph', content: [{ type: 'text', text }] })

describe('countWordsFromTipTap', () => {
  it('compte les mots des nœuds texte, pas du HTML', () => {
    const body = {
      type: 'doc',
      content: [
        { type: 'heading', attrs: { level: 2 }, content: [{ type: 'text', text: 'Titre de section' }] },
        p('Un deux trois'),
        {
          type: 'paragraph',
          content: [
            { type: 'text', text: 'gras', marks: [{ type: 'bold' }] },
            { type: 'text', text: ' et italique' },
          ],
        },
      ],
    }
    expect(countWordsFromTipTap(body)).toBe(3 + 3 + 3)
  })

  it('ignore les segments sans lettre ni chiffre et gère le corps vide', () => {
    expect(countWordsFromTipTap({ type: 'doc', content: [p('— · —')] })).toBe(0)
    expect(countWordsFromTipTap({ type: 'doc', content: [] })).toBe(0)
    expect(countWordsFromTipTap(null)).toBe(0)
    expect(countWordsFromTipTap({ type: 'doc' })).toBe(0)
  })

  it('ne fusionne pas deux paragraphes adjacents', () => {
    expect(countWordsFromTipTap({ type: 'doc', content: [p('fin'), p('début')] })).toBe(2)
  })

  it('compte les mots des listes et des sauts de ligne', () => {
    const body = {
      type: 'doc',
      content: [
        {
          type: 'bulletList',
          content: [
            { type: 'listItem', content: [p('premier point')] },
            { type: 'listItem', content: [p('second point')] },
          ],
        },
        { type: 'paragraph', content: [{ type: 'text', text: 'a' }, { type: 'hardBreak' }, { type: 'text', text: 'b' }] },
      ],
    }
    expect(countWordsFromTipTap(body)).toBe(6)
  })
})

describe('temps de lecture', () => {
  it('arrondit à la minute la plus proche, minimum 1 dès un mot', () => {
    expect(readingMinutes(0)).toBe(0)
    expect(readingMinutes(1)).toBe(1)
    expect(readingMinutes(200)).toBe(1)
    expect(readingMinutes(1240)).toBe(6)
  })

  it('formate « N mots · M min de lecture »', () => {
    const s = formatWordStats(1240).replace(/\s/g, ' ')
    expect(s).toBe('1 240 mots · 6 min de lecture')
    expect(formatWordStats(1)).toContain('1 mot ·')
    expect(formatWordStats(0)).toContain('0 mot')
  })
})

describe('états d’enregistrement', () => {
  it('affiche enregistré à HH:MM, en cours et échec', () => {
    const at = new Date(2026, 8, 12, 14, 22)
    expect(formatClockFr(at)).toBe('14:22')
    expect(saveStatusText({ kind: 'saved', at })).toBe('Brouillon enregistré à 14:22')
    expect(saveStatusText({ kind: 'saving' })).toBe('Enregistrement…')
    expect(saveStatusText({ kind: 'error' })).toBe("Échec de l'enregistrement")
    expect(saveStatusText({ kind: 'saved', at: null })).toBe('Brouillon')
  })
})

describe('bouton « Envoyer en révision »', () => {
  const ok = { canPublish: true, hasWorkflow: true, pending: false, lockedByOther: false, submitting: false }

  it('est actif quand tout est réuni', () => {
    expect(reviewButtonState(ok)).toEqual({ disabled: false, reason: null })
  })

  it('est désactivé sans droit de publication', () => {
    const s = reviewButtonState({ ...ok, canPublish: false })
    expect(s.disabled).toBe(true)
    expect(s.reason).toMatch(/pas le droit/)
  })

  it('est désactivé sans workflow applicable', () => {
    const s = reviewButtonState({ ...ok, hasWorkflow: false })
    expect(s.disabled).toBe(true)
    expect(s.reason).toMatch(/workflow/)
  })

  it('est désactivé si une demande est en cours, si un tiers détient le verrou ou pendant l’envoi', () => {
    expect(reviewButtonState({ ...ok, pending: true }).disabled).toBe(true)
    expect(reviewButtonState({ ...ok, lockedByOther: true }).disabled).toBe(true)
    expect(reviewButtonState({ ...ok, submitting: true }).disabled).toBe(true)
    expect(reviewButtonState({ ...ok, workflowLoading: true }).disabled).toBe(true)
  })
})

describe('présence et verrou', () => {
  const me = { id: 'me', displayName: 'Tarek Fezai', avatarInitials: 'TF' }
  const lock = (over: Partial<EditLockStatus>): EditLockStatus => ({
    active: true,
    holderUserId: 'me',
    holderDisplayName: 'Tarek Fezai',
    acquiredAt: '2026-09-12T12:00:00Z',
    heartbeatAt: '2026-09-12T12:00:10Z',
    heldByCurrentUser: true,
    ttlSeconds: 45,
    heartbeatSeconds: 15,
    ...over,
  })

  it('affiche seulement mon avatar quand je détiens le verrou (ou avant la réponse)', () => {
    expect(presenceAvatars(lock({}), me)).toEqual([
      { key: 'me', initials: 'TF', name: 'Tarek Fezai', self: true },
    ])
    expect(presenceAvatars(null, me)).toHaveLength(1)
    expect(lockHeldByOther(lock({}))).toBeNull()
  })

  it('affiche uniquement l’avatar du détenteur quand un autre utilisateur détient le verrou', () => {
    const other = lock({ heldByCurrentUser: false, holderUserId: 'u2', holderDisplayName: 'Camille Durand' })
    expect(presenceAvatars(other, me)).toEqual([
      { key: 'u2', initials: 'CD', name: 'Camille Durand', self: false },
    ])
    expect(lockHeldByOther(other)).toEqual({
      name: 'Camille Durand',
      initials: 'CD',
      since: '2026-09-12T12:00:00Z',
    })
  })

  it('ignore un verrou inactif', () => {
    const idle = lock({ active: false, heldByCurrentUser: false })
    expect(lockHeldByOther(idle)).toBeNull()
    expect(presenceAvatars(idle, me)[0]!.self).toBe(true)
  })
})

describe('contenu non éditable sans perte', () => {
  it('détecte les nœuds et marques hors schéma', () => {
    const body = {
      type: 'doc',
      content: [
        p('ok'),
        { type: 'drawio', attrs: { xml: '<mxfile/>' } },
        {
          type: 'paragraph',
          content: [{ type: 'text', text: 'surbrillance', marks: [{ type: 'highlight' }] }],
        },
      ],
    }
    const u = findUnsupportedContent(body)
    expect(u.nodes).toEqual(['drawio'])
    expect(u.marks).toEqual(['highlight'])
    expect(hasUnsupportedContent(u)).toBe(true)
  })

  it('accepte lien et souligné dans le schéma éditeur', () => {
    const body = {
      type: 'doc',
      content: [
        {
          type: 'paragraph',
          content: [
            { type: 'text', text: 'a', marks: [{ type: 'link', attrs: { href: 'https://example.org' } }] },
            { type: 'text', text: 'b', marks: [{ type: 'underline' }] },
          ],
        },
        { type: 'transclusion', attrs: { documentId: 'dddddddd-dddd-dddd-dddd-ddddddddddd1' } },
      ],
    }
    expect(hasUnsupportedContent(findUnsupportedContent(body))).toBe(false)
  })

  it('accepte le schéma StarterKit + zones à compléter', () => {
    const body = {
      type: 'doc',
      content: [
        p('ok'),
        { type: 'placeholder', attrs: { hint: 'Objet' } },
        { type: 'blockquote', content: [p('citation')] },
        { type: 'codeBlock', content: [{ type: 'text', text: 'x' }] },
      ],
    }
    expect(hasUnsupportedContent(findUnsupportedContent(body))).toBe(false)
  })

  it('les blocs enrichis (tableaux, date, bouton, vidéo) sont dans le schéma de l’éditeur', () => {
    for (const t of ['table', 'tableRow', 'tableCell', 'tableHeader', 'date', 'button', 'video', 'poll', 'chart', 'linkPreview']) {
      expect(EDITOR_NODE_TYPES.has(t), t).toBe(true)
    }
    const body = {
      type: 'doc',
      content: [
        {
          type: 'table',
          content: [
            {
              type: 'tableRow',
              content: [
                { type: 'tableHeader', content: [p('A')] },
                { type: 'tableCell', content: [p('B')] },
              ],
            },
          ],
        },
        { type: 'paragraph', content: [{ type: 'date', attrs: { value: '2026-10-15' } }] },
        { type: 'button', attrs: { label: 'Action', href: 'https://example.org' } },
        { type: 'video', attrs: { id: 'v1' } },
      ],
    }
    expect(hasUnsupportedContent(findUnsupportedContent(body))).toBe(false)
  })
})

describe('métadonnées', () => {
  it('libellés de fiabilité et de cadence', () => {
    expect(reliabilityShortLabel(91)).toBe('Élevée')
    expect(reliabilityShortLabel(60)).toBe('Moyenne')
    expect(reliabilityShortLabel(10)).toBe('Faible')
    expect(reliabilityShortLabel(null)).toBe('Non évaluée')
    expect(reviewCadenceLabel(181)).toBe('Semestrielle')
    expect(reviewCadenceLabel(90)).toBe('Trimestrielle')
    expect(reviewCadenceLabel(null)).toBe('—')
  })
})
