// SPDX-License-Identifier: AGPL-3.0-or-later
import { useEffect, useRef, useState, type MouseEvent, type ReactNode } from 'react'
import type { ChainedCommands, Editor } from '@tiptap/react'
import type { UploadKind } from '../../components/attachments/useAttachmentUploads'
import {
  BUTTON_NODE_TYPE,
  DATE_NODE_TYPE,
  DEFAULT_BUTTON_HREF,
  DEFAULT_BUTTON_LABEL,
  todayIsoDate,
} from '../../components/rich-blocks/richBlockUtils'

export const SOON_TITLE = 'Bientôt disponible'

/** Envoi de pièces jointes : `enabled` faux (modèle, document inconnu) → boutons inactifs. */
export type ToolbarAttachments = {
  enabled: boolean
  pick: (kind: UploadKind) => void
}

const INK = '#43434A'
const MUTED = '#9B9BA1'

function Svg({
  size = 13,
  stroke = INK,
  strokeWidth = 2,
  round = true,
  children,
}: {
  size?: number
  stroke?: string
  strokeWidth?: number
  round?: boolean
  children: ReactNode
}) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke={stroke}
      strokeWidth={strokeWidth}
      strokeLinecap="round"
      strokeLinejoin={round ? 'round' : undefined}
      aria-hidden
    >
      {children}
    </svg>
  )
}

type ToolProps = {
  label: string
  children: ReactNode
  onClick?: () => void
  active?: boolean
  /** Fonction à venir : affichée, `aria-disabled`, info-bulle « Bientôt disponible ». */
  soon?: boolean
  /** Indisponible dans l'état courant (lecture seule, rien à annuler…). */
  disabled?: boolean
  className?: string
  mockId?: string
  pressed?: boolean
}

function Tool({ label, children, onClick, active, soon, disabled, className = '', mockId, pressed }: ToolProps) {
  const inert = Boolean(soon || disabled)
  const handle = (e: MouseEvent<HTMLButtonElement>) => {
    if (inert) {
      e.preventDefault()
      return
    }
    onClick?.()
  }
  return (
    <button
      type="button"
      className={`edit-tool${active ? ' is-active' : ''}${soon ? ' is-soon' : ''} ${className}`.trim()}
      aria-label={label}
      title={soon ? SOON_TITLE : label}
      aria-disabled={inert ? true : undefined}
      aria-pressed={pressed ?? (active !== undefined && !soon ? active : undefined)}
      data-soon={soon ? 'true' : undefined}
      data-mock-id={mockId}
      // Évite de perdre la sélection de l'éditeur au clic.
      onMouseDown={(e) => e.preventDefault()}
      onClick={handle}
    >
      {children}
    </button>
  )
}

const Sep = () => <div className="edit-tool-sep" role="separator" aria-orientation="vertical" />

/* ------------------------------------------------------------------ */
/* Style de titre                                                       */
/* ------------------------------------------------------------------ */

const BLOCK_STYLES: Array<{ label: string; level: 0 | 1 | 2 | 3 | 4 }> = [
  { label: 'Paragraphe', level: 0 },
  { label: 'Titre 1', level: 1 },
  { label: 'Titre 2', level: 2 },
  { label: 'Titre 3', level: 3 },
  { label: 'Titre 4', level: 4 },
]

export function currentBlockLabel(editor: Editor): string {
  for (const l of [1, 2, 3, 4] as const) {
    if (editor.isActive('heading', { level: l })) return `Titre ${l}`
  }
  return 'Paragraphe'
}

function useDismiss(open: boolean, close: () => void) {
  const ref = useRef<HTMLSpanElement>(null)
  useEffect(() => {
    if (!open) return
    const onDoc = (e: globalThis.MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) close()
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') close()
    }
    document.addEventListener('mousedown', onDoc)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onDoc)
      document.removeEventListener('keydown', onKey)
    }
  }, [open, close])
  return ref
}

function HeadingMenu({ editor, readOnly }: { editor: Editor; readOnly: boolean }) {
  const [open, setOpen] = useState(false)
  const ref = useDismiss(open, () => setOpen(false))
  const label = currentBlockLabel(editor)
  return (
    <span className="edit-pop-anchor" ref={ref}>
      <button
        type="button"
        className="edit-tool edit-tool--text"
        aria-label="Style de titre"
        aria-haspopup="menu"
        aria-expanded={open}
        aria-disabled={readOnly || undefined}
        data-testid="edit-heading-menu"
        onMouseDown={(e) => e.preventDefault()}
        onClick={() => !readOnly && setOpen((v) => !v)}
      >
        {label}
        <svg width="9" height="9" viewBox="0 0 24 24" fill="none" stroke={MUTED} strokeWidth="2.6" strokeLinecap="round" aria-hidden>
          <polyline points="6 9 12 15 18 9" />
        </svg>
      </button>
      {open && (
        <div className="edit-menu" role="menu" data-testid="edit-heading-options">
          {BLOCK_STYLES.map((s) => (
            <button
              key={s.label}
              type="button"
              role="menuitemradio"
              aria-checked={label === s.label}
              className={`edit-menu-item${label === s.label ? ' is-current' : ''}`}
              onMouseDown={(e) => e.preventDefault()}
              onClick={() => {
                const chain = editor.chain().focus()
                if (s.level === 0) chain.setParagraph().run()
                else chain.setHeading({ level: s.level }).run()
                setOpen(false)
              }}
            >
              {s.label}
            </button>
          ))}
        </div>
      )}
    </span>
  )
}

/* ------------------------------------------------------------------ */
/* Menu « Insérer »                                                     */
/* ------------------------------------------------------------------ */

type InsertItem = {
  id: string
  label: string
  bg: string
  icon: ReactNode
  /** Insertion directe dans l'éditeur (bloc de code, tableau, date, bouton). */
  run?: (editor: Editor) => void
  /** « Fichier joint » : ouvre le sélecteur de fichiers (envoi vers l'API). */
  pick?: UploadKind
}

/** Tableau 3×3 avec ligne d'en-tête. */
function insertTable(ed: Editor) {
  ed.chain().focus().insertTable({ rows: 3, cols: 3, withHeaderRow: true }).run()
}

/** Date du jour (modifiable ensuite via le sélecteur de date du nœud). */
function insertDate(ed: Editor) {
  ed.chain().focus().insertContent({ type: DATE_NODE_TYPE, attrs: { value: todayIsoDate() } }).run()
}

/** Bouton d'exemple : l'auteur remplace le libellé et le lien. */
function insertButton(ed: Editor) {
  ed.chain()
    .focus()
    .insertContent([
      { type: BUTTON_NODE_TYPE, attrs: { label: DEFAULT_BUTTON_LABEL, href: DEFAULT_BUTTON_HREF } },
      // Paragraphe à la suite : le curseur ne reste pas sélectionné sur le nœud atomique.
      { type: 'paragraph' },
    ])
    .run()
}

const INSERT_GROUPS: Array<{ title: string; items: InsertItem[] }> = [
  {
    title: 'Pages',
    items: [
      {
        id: 'transclusion',
        label: 'Transclure une page',
        bg: '#F0EFFC',
        icon: (
          <Svg stroke="#3730E0">
            <path d="M14 3v4a1 1 0 0 0 1 1h4" />
            <path d="M17 21H7a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h7l5 5v11a2 2 0 0 1-2 2z" />
          </Svg>
        ),
      },
    ],
  },
  {
    title: 'Médias & données',
    items: [
      {
        id: 'video',
        label: 'Vidéo',
        bg: '#FCEEEA',
        icon: (
          <Svg stroke="#B54708">
            <polygon points="23 7 16 12 23 17 23 7" />
            <rect x="1" y="5" width="15" height="14" rx="2" />
          </Svg>
        ),
        pick: 'video',
      },
      {
        id: 'link-preview',
        label: 'Aperçu de lien',
        bg: '#EEEDFD',
        icon: (
          <Svg stroke="#3730E0">
            <path d="M10 13a5 5 0 0 0 7 0l3-3a5 5 0 0 0-7-7l-1 1" />
            <path d="M14 11a5 5 0 0 0-7 0l-3 3a5 5 0 0 0 7 7l1-1" />
          </Svg>
        ),
      },
      {
        id: 'attachment',
        label: 'Fichier joint',
        bg: '#F1EFEA',
        icon: (
          <Svg stroke="#6B6862">
            <path d="M13.5 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7.5L13.5 2z" />
            <polyline points="13.5 2 13.5 7.5 19 7.5" />
          </Svg>
        ),
        pick: 'file',
      },
      {
        id: 'table',
        label: 'Tableau',
        bg: '#DCF2ED',
        icon: (
          <Svg stroke="#0D8A7C">
            <rect x="3" y="4" width="18" height="16" rx="1.5" />
            <line x1="3" y1="10" x2="21" y2="10" />
            <line x1="9" y1="4" x2="9" y2="20" />
          </Svg>
        ),
        run: (ed) => insertTable(ed),
      },
      {
        id: 'chart',
        label: 'Graphique',
        bg: '#FDF3E3',
        icon: (
          <Svg stroke="#B7791F">
            <line x1="18" y1="20" x2="18" y2="10" />
            <line x1="12" y1="20" x2="12" y2="4" />
            <line x1="6" y1="20" x2="6" y2="14" />
          </Svg>
        ),
      },
      {
        id: 'poll',
        label: 'Sondage',
        bg: '#DCF2ED',
        icon: (
          <Svg stroke="#0D8A7C">
            <path d="M22 12h-4l-3 9L9 3l-3 9H2" />
          </Svg>
        ),
      },
    ],
  },
  {
    title: 'Code & structure',
    items: [
      {
        id: 'code-block',
        label: 'Bloc de code',
        bg: '#0E0E10',
        icon: (
          <Svg stroke="#FFFFFF">
            <polyline points="16 18 22 12 16 6" />
            <polyline points="8 6 2 12 8 18" />
          </Svg>
        ),
        run: (ed) => ed.chain().focus().toggleCodeBlock().run(),
      },
      {
        id: 'schema',
        label: 'Schéma',
        bg: '#F1EFEA',
        icon: (
          <Svg stroke="#6B6862">
            <rect x="3" y="3" width="7" height="7" rx="1.5" />
            <rect x="14" y="14" width="7" height="7" rx="1.5" />
            <path d="M10 6.5h4a3 3 0 0 1 3 3V14" />
          </Svg>
        ),
      },
      {
        id: 'date',
        label: 'Date & heure',
        bg: '#F0EFFC',
        icon: (
          <Svg stroke="#3730E0">
            <rect x="3" y="4" width="18" height="18" rx="2" />
            <line x1="16" y1="2" x2="16" y2="6" />
            <line x1="8" y1="2" x2="8" y2="6" />
            <line x1="3" y1="10" x2="21" y2="10" />
          </Svg>
        ),
        run: (ed) => insertDate(ed),
      },
      {
        id: 'button',
        label: 'Bouton',
        bg: '#EEEDFD',
        icon: (
          <Svg stroke="#3730E0">
            <rect x="3" y="6" width="18" height="12" rx="2" />
            <line x1="8" y1="12" x2="16" y2="12" />
          </Svg>
        ),
        run: (ed) => insertButton(ed),
      },
    ],
  },
]

function InsertMenu({
  editor,
  readOnly,
  attachments,
}: {
  editor: Editor
  readOnly: boolean
  attachments?: ToolbarAttachments
}) {
  const [open, setOpen] = useState(false)
  const [q, setQ] = useState('')
  const ref = useDismiss(open, () => setOpen(false))
  const needle = q.trim().toLowerCase()
  return (
    <span className="edit-pop-anchor" ref={ref}>
      <button
        type="button"
        className="edit-tool edit-tool--insert"
        aria-label="Insérer un embed"
        aria-haspopup="dialog"
        aria-expanded={open}
        aria-disabled={readOnly || undefined}
        data-testid="edit-insert-btn"
        onMouseDown={(e) => e.preventDefault()}
        onClick={() => !readOnly && setOpen((v) => !v)}
      >
        <Svg size={12} stroke="#3730E0" strokeWidth={2.4} round={false}>
          <line x1="12" y1="5" x2="12" y2="19" />
          <line x1="5" y1="12" x2="19" y2="12" />
        </Svg>
        <span>Insérer</span>
      </button>
      {open && (
        <div className="edit-insert-panel" role="dialog" aria-label="Insérer un bloc" data-testid="edit-insert-panel">
          <label className="edit-insert-search">
            <Svg size={12} stroke={MUTED} round={false}>
              <circle cx="11" cy="11" r="7" />
              <line x1="21" y1="21" x2="16.65" y2="16.65" />
            </Svg>
            <input
              type="text"
              value={q}
              placeholder="Rechercher un bloc…"
              aria-label="Rechercher un bloc"
              onChange={(e) => setQ(e.target.value)}
            />
          </label>
          {INSERT_GROUPS.map((g) => {
            const items = g.items.filter((i) => !needle || i.label.toLowerCase().includes(needle))
            if (items.length === 0) return null
            return (
              <div key={g.title}>
                <div className="edit-insert-title">{g.title}</div>
                <div className="edit-insert-grid">
                  {items.map((it) => {
                    const live = Boolean(it.run || it.pick)
                    // Fichier joint : branché, mais inactif sans document porteur (modèle).
                    const unavailable = Boolean(it.pick) && !attachments?.enabled
                    return (
                      <button
                        key={it.id}
                        type="button"
                        className={`edit-insert-item${live ? '' : ' is-soon'}`}
                        aria-disabled={!live || unavailable ? true : undefined}
                        title={live ? undefined : SOON_TITLE}
                        data-soon={live ? undefined : 'true'}
                        onMouseDown={(e) => e.preventDefault()}
                        onClick={() => {
                          if (it.pick) {
                            if (unavailable) return
                            attachments?.pick(it.pick)
                            setOpen(false)
                            return
                          }
                          if (!it.run) return
                          it.run(editor)
                          setOpen(false)
                        }}
                      >
                        <span className="edit-insert-icon" style={{ background: it.bg }}>
                          {it.icon}
                        </span>
                        <span className="edit-insert-label">{it.label}</span>
                      </button>
                    )
                  })}
                </div>
              </div>
            )
          })}
        </div>
      )}
    </span>
  )
}

/* ------------------------------------------------------------------ */
/* Barre d'outils                                                       */
/* ------------------------------------------------------------------ */

/**
 * Barre d'outils de l'écran Modifier (Edit.dc.html). Actions réellement branchées : annuler /
 * rétablir, titres, gras / italique / barré, citation, effacer la mise en forme, listes, séparateur,
 * bloc de code, tableau, date, bouton, image, vidéo et fichier joint (envoi API). Le reste est affiché grisé (`aria-disabled`,
 * « Bientôt disponible »).
 */
export function EditToolbar({
  editor,
  readOnly = false,
  attachments,
}: {
  editor: Editor
  readOnly?: boolean
  attachments?: ToolbarAttachments
}) {
  const can = (fn: () => boolean) => !readOnly && fn()
  const run = (fn: (c: ChainedCommands) => ChainedCommands) => () => {
    fn(editor.chain().focus()).run()
  }
  const canUndo = can(() => editor.can().undo())
  const canRedo = can(() => editor.can().redo())
  return (
    <div className="edit-toolbar" role="toolbar" aria-label="Mise en forme" data-mock-id="edit-toolbar" data-testid="edit-toolbar">
      <Tool label="Annuler" disabled={!canUndo} onClick={run((c) => c.undo())} mockId="edit-tool-undo">
        <Svg stroke={canUndo ? INK : MUTED}>
          <path d="M3 7v6h6" />
          <path d="M3 13a9 9 0 1 0 3-7" />
        </Svg>
      </Tool>
      <Tool label="Rétablir" disabled={!canRedo} onClick={run((c) => c.redo())}>
        <Svg stroke={canRedo ? INK : MUTED}>
          <path d="M21 7v6h-6" />
          <path d="M21 13a9 9 0 1 1-3-7" />
        </Svg>
      </Tool>
      <Sep />
      <HeadingMenu editor={editor} readOnly={readOnly} />
      <Sep />
      <Tool
        label="Gras"
        active={editor.isActive('bold')}
        disabled={readOnly}
        onClick={run((c) => c.toggleBold())}
        className="edit-tool--glyph is-bold"
      >
        B
      </Tool>
      <Tool
        label="Italique"
        active={editor.isActive('italic')}
        disabled={readOnly}
        onClick={run((c) => c.toggleItalic())}
        className="edit-tool--glyph is-italic"
      >
        I
      </Tool>
      <Tool
        label="Souligné"
        active={editor.isActive('underline')}
        disabled={readOnly}
        onClick={run((c) => c.toggleUnderline())}
        className="edit-tool--glyph is-underline"
      >
        U
      </Tool>
      <Tool
        label="Barré"
        active={editor.isActive('strike')}
        disabled={readOnly}
        onClick={run((c) => c.toggleStrike())}
        className="edit-tool--glyph is-strike"
      >
        S
      </Tool>
      <Tool label="Couleur et surlignage" soon className="edit-tool--color">
        <span className="edit-color-a">A</span>
        <span className="edit-color-bar" />
      </Tool>
      <Tool
        label="Citation"
        active={editor.isActive('blockquote')}
        disabled={readOnly}
        onClick={run((c) => c.toggleBlockquote())}
        className="edit-tool--glyph"
      >
        &quot;
      </Tool>
      <Tool
        label="Effacer la mise en forme"
        disabled={readOnly}
        onClick={run((c) => c.clearNodes().unsetAllMarks())}
      >
        <Svg>
          <path d="M17 3l4 4-9.5 9.5H7L3.5 13z" />
          <path d="M12.5 6.5L17 11" />
          <line x1="4" y1="21" x2="20" y2="21" />
        </Svg>
      </Tool>
      <Sep />
      <Tool
        label="Liste à puces"
        active={editor.isActive('bulletList')}
        disabled={readOnly}
        onClick={run((c) => c.toggleBulletList())}
      >
        <Svg>
          <line x1="9" y1="6" x2="21" y2="6" />
          <line x1="9" y1="12" x2="21" y2="12" />
          <line x1="9" y1="18" x2="21" y2="18" />
          <circle cx="4" cy="6" r="1.5" fill={INK} stroke="none" />
          <circle cx="4" cy="12" r="1.5" fill={INK} stroke="none" />
          <circle cx="4" cy="18" r="1.5" fill={INK} stroke="none" />
        </Svg>
      </Tool>
      <Tool
        label="Liste numérotée"
        active={editor.isActive('orderedList')}
        disabled={readOnly}
        onClick={run((c) => c.toggleOrderedList())}
      >
        <Svg>
          <line x1="10" y1="6" x2="21" y2="6" />
          <line x1="10" y1="12" x2="21" y2="12" />
          <line x1="10" y1="18" x2="21" y2="18" />
          <path d="M4 6h1v4" />
          <path d="M4 10h2" />
          <path d="M4 14a1.5 1.5 0 0 1 3 0c0 .8-.5 1.2-1 1.7L4 18h3" />
        </Svg>
      </Tool>
      <Tool label="Liste de tâches" soon>
        <Svg>
          <rect x="3" y="5" width="6" height="6" rx="1.5" />
          <polyline points="4.5 8 5.5 9 7.5 6.5" />
          <line x1="12" y1="8" x2="21" y2="8" />
          <rect x="3" y="15" width="6" height="6" rx="1.5" />
          <line x1="12" y1="18" x2="21" y2="18" />
        </Svg>
      </Tool>
      <Tool label="Diminuer le retrait" soon>
        <Svg>
          <polyline points="7 8 3 12 7 16" />
          <line x1="21" y1="6" x2="11" y2="6" />
          <line x1="21" y1="12" x2="11" y2="12" />
          <line x1="21" y1="18" x2="11" y2="18" />
        </Svg>
      </Tool>
      <Tool label="Augmenter le retrait" soon>
        <Svg>
          <polyline points="3 8 7 12 3 16" />
          <line x1="21" y1="6" x2="11" y2="6" />
          <line x1="21" y1="12" x2="11" y2="12" />
          <line x1="21" y1="18" x2="11" y2="18" />
        </Svg>
      </Tool>
      <Sep />
      <Tool label="Aligner à gauche" soon active>
        <Svg round={false}>
          <line x1="4" y1="6" x2="20" y2="6" />
          <line x1="4" y1="12" x2="14" y2="12" />
          <line x1="4" y1="18" x2="17" y2="18" />
        </Svg>
      </Tool>
      <Tool label="Centrer" soon>
        <Svg round={false}>
          <line x1="4" y1="6" x2="20" y2="6" />
          <line x1="7" y1="12" x2="17" y2="12" />
          <line x1="5.5" y1="18" x2="18.5" y2="18" />
        </Svg>
      </Tool>
      <Tool label="Justifier" soon>
        <Svg round={false}>
          <line x1="4" y1="6" x2="20" y2="6" />
          <line x1="4" y1="12" x2="20" y2="12" />
          <line x1="4" y1="18" x2="20" y2="18" />
        </Svg>
      </Tool>
      <Sep />
      <Tool
        label="Insérer une ligne de séparation"
        disabled={readOnly}
        onClick={run((c) => c.setHorizontalRule())}
      >
        <Svg round={false}>
          <line x1="4" y1="12" x2="20" y2="12" />
        </Svg>
      </Tool>
      <Tool
        label="Insérer un lien"
        active={editor.isActive('link')}
        disabled={readOnly}
        onClick={() => {
          if (readOnly || !editor) return
          const prev = editor.getAttributes('link').href as string | undefined
          const next = window.prompt('URL du lien', prev || 'https://')
          if (next === null) return
          const href = next.trim()
          if (!href) {
            editor.chain().focus().extendMarkRange('link').unsetLink().run()
            return
          }
          editor.chain().focus().extendMarkRange('link').setLink({ href }).run()
        }}
      >
        <Svg round={false}>
          <path d="M10 13a5 5 0 0 0 7 0l3-3a5 5 0 0 0-7-7l-1 1" />
          <path d="M14 11a5 5 0 0 0-7 0l-3 3a5 5 0 0 0 7 7l1-1" />
        </Svg>
      </Tool>
      <Tool
        label="Insérer une image"
        disabled={readOnly || !attachments?.enabled}
        onClick={() => attachments?.pick('image')}
      >
        <Svg>
          <rect x="3" y="3" width="18" height="18" rx="2" />
          <circle cx="8.5" cy="8.5" r="1.5" />
          <path d="M21 15l-5-5L5 21" />
        </Svg>
      </Tool>
      <Tool label="Insérer un diagramme draw.io" soon>
        <Svg>
          <rect x="3" y="3" width="7" height="7" rx="1.5" />
          <rect x="14" y="14" width="7" height="7" rx="1.5" />
          <path d="M10 6.5h4a3 3 0 0 1 3 3V14" />
        </Svg>
      </Tool>
      <Tool label="Insérer un tableau" disabled={readOnly} onClick={() => insertTable(editor)}>
        <Svg>
          <rect x="3" y="4" width="18" height="16" rx="1.5" />
          <line x1="3" y1="10" x2="21" y2="10" />
          <line x1="9" y1="4" x2="9" y2="20" />
        </Svg>
      </Tool>
      <Tool label="Mentionner une personne" soon className="edit-tool--glyph is-semibold">
        @
      </Tool>
      <Sep />
      <InsertMenu editor={editor} readOnly={readOnly} attachments={attachments} />
      <Sep />
      <button
        type="button"
        className="edit-tool edit-tool--collapse is-soon"
        aria-label="Réduire les blocs enrichis"
        aria-disabled="true"
        title={SOON_TITLE}
        data-soon="true"
        onClick={(e) => e.preventDefault()}
      >
        <Svg>
          <path d="M17.94 17.94A10.94 10.94 0 0 1 12 20c-7 0-11-8-11-8a20.6 20.6 0 0 1 5.06-5.94M9.9 4.24A10.94 10.94 0 0 1 12 4c7 0 11 8 11 8a20.6 20.6 0 0 1-2.16 3.19" />
          <path d="M14.12 14.12a3 3 0 1 1-4.24-4.24" />
          <line x1="1" y1="1" x2="23" y2="23" />
        </Svg>
        <span>Réduire les blocs enrichis</span>
      </button>
    </div>
  )
}
