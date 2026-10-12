// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { useEffect, useId, useRef, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { api } from '../lib/api'
import { searchDirectoryUsers, type DirectoryUser } from '../lib/directory'
import { listGroups, type Group } from '../lib/groups'

export type PickerValue =
  | { kind: 'user'; id: string; label: string; initials: string }
  | { kind: 'group'; id: string; label: string; initials: string }

/**
 * Combobox ARIA personnes / équipes — aucun UUID affiché.
 */
export function PeoplePicker({
  value,
  onChange,
  disabled,
  usersOnly,
}: {
  value: PickerValue | null
  onChange: (next: PickerValue | null) => void
  disabled?: boolean
  usersOnly?: boolean
}) {
  const listId = useId()
  const inputId = useId()
  const [q, setQ] = useState('')
  const [open, setOpen] = useState(false)
  const [hi, setHi] = useState(0)
  const box = useRef<HTMLDivElement>(null)

  const users = useQuery({
    queryKey: ['directory-users', q],
    queryFn: () => searchDirectoryUsers(api, q, 8),
    enabled: q.trim().length >= 2,
  })
  const groups = useQuery({
    queryKey: ['groups'],
    queryFn: () => listGroups(api),
    enabled: !usersOnly && (open || q.trim().length >= 2),
  })

  const folded = q.trim().toLowerCase()
  const groupHits: Group[] =
    folded.length < 2
      ? []
      : (groups.data ?? []).filter((g) => g.name.toLowerCase().includes(folded)).slice(0, 8)
  const options: PickerValue[] = [
    ...(users.data ?? []).map((u: DirectoryUser) => ({
      kind: 'user' as const,
      id: u.id,
      label: u.displayName,
      initials: u.initials,
    })),
    ...(usersOnly
      ? []
      : groupHits.map((g) => ({
          kind: 'group' as const,
          id: g.id,
          label: g.name,
          initials: '⬡',
        }))),
  ]

  useEffect(() => {
    function onDoc(e: MouseEvent) {
      if (box.current && !box.current.contains(e.target as Node)) setOpen(false)
    }
    document.addEventListener('mousedown', onDoc)
    return () => document.removeEventListener('mousedown', onDoc)
  }, [])

  function choose(opt: PickerValue) {
    onChange(opt)
    setQ(opt.label)
    setOpen(false)
  }

  function onKey(e: React.KeyboardEvent) {
    if (e.key === 'ArrowDown') {
      e.preventDefault()
      setOpen(true)
      setHi((i) => Math.min(i + 1, Math.max(0, options.length - 1)))
    } else if (e.key === 'ArrowUp') {
      e.preventDefault()
      setHi((i) => Math.max(i - 1, 0))
    } else if (e.key === 'Enter' && open && options[hi]) {
      e.preventDefault()
      choose(options[hi])
    } else if (e.key === 'Escape') {
      setOpen(false)
    }
  }

  return (
    <div ref={box} className="relative min-w-[16rem] flex-1">
      <label className="flex flex-col gap-1 text-sm" htmlFor={inputId}>
        <span className="text-socle-muted">{usersOnly ? 'Personne' : 'Personne ou équipe'}</span>
        <input
          id={inputId}
          role="combobox"
          aria-expanded={open}
          aria-controls={listId}
          aria-autocomplete="list"
          disabled={disabled}
          value={value && q === value.label ? value.label : q}
          onChange={(e) => {
            setQ(e.target.value)
            onChange(null)
            setOpen(true)
            setHi(0)
          }}
          onFocus={() => setOpen(true)}
          onKeyDown={onKey}
          placeholder="Rechercher un nom…"
          className="field-input"
          autoComplete="off"
        />
      </label>
      {open && q.trim().length >= 2 && (
        <ul
          id={listId}
          role="listbox"
          className="absolute z-20 mt-1 max-h-56 w-full overflow-auto rounded-lg border border-socle-line bg-white py-1 shadow-sm"
        >
          {users.isError && (
            <li className="px-3 py-2 text-xs text-[#7C2D12]">Recherche impossible</li>
          )}
          {options.length === 0 && !users.isFetching && (
            <li className="px-3 py-2 text-xs text-socle-muted">Aucun résultat</li>
          )}
          {options.map((opt, i) => (
            <li key={`${opt.kind}-${opt.id}`} role="option" aria-selected={i === hi}>
              <button
                type="button"
                className={`flex w-full items-center gap-2 px-3 py-2 text-left text-[13px] ${
                  i === hi ? 'bg-[#F0EFFC]' : ''
                }`}
                onMouseEnter={() => setHi(i)}
                onClick={() => choose(opt)}
              >
                <span className="flex h-[22px] w-[22px] shrink-0 items-center justify-center rounded-full bg-[#EEEDFD] text-[10px] font-semibold text-[#3730E0]">
                  {opt.initials}
                </span>
                <span>
                  {opt.label}
                  <span className="ml-2 text-[11px] text-[#9B9BA1]">
                    {opt.kind === 'group' ? 'Équipe' : 'Personne'}
                  </span>
                </span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
