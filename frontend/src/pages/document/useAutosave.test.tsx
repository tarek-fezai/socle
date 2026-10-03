// SPDX-License-Identifier: AGPL-3.0-or-later
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, renderHook } from '@testing-library/react'
import { useAutosave } from './useAutosave'

type V = { title: string }

describe('useAutosave', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })
  afterEach(() => {
    vi.useRealTimers()
  })

  const setup = (save: (v: V) => Promise<unknown>, enabled = true) =>
    renderHook(({ value }: { value: V }) => useAutosave<V>({ value, initial: { title: 'A' }, enabled, save, initialSavedAt: null }), {
      initialProps: { value: { title: 'A' } },
    })

  it('n’enregistre rien tant que le brouillon est identique', async () => {
    const save = vi.fn().mockResolvedValue({})
    setup(save)
    await act(async () => {
      await vi.advanceTimersByTimeAsync(3000)
    })
    expect(save).not.toHaveBeenCalled()
  })

  it('enregistre une seule fois après ~1 s (debounce) puis passe en « enregistré »', async () => {
    const save = vi.fn().mockResolvedValue({})
    const { result, rerender } = setup(save)
    rerender({ value: { title: 'AB' } })
    rerender({ value: { title: 'ABC' } })
    expect(result.current.status.kind).toBe('saving')
    await act(async () => {
      await vi.advanceTimersByTimeAsync(900)
    })
    expect(save).not.toHaveBeenCalled()
    await act(async () => {
      await vi.advanceTimersByTimeAsync(200)
    })
    expect(save).toHaveBeenCalledTimes(1)
    expect(save).toHaveBeenCalledWith({ title: 'ABC' })
    expect(result.current.status.kind).toBe('saved')
    expect(result.current.dirty).toBe(false)
  })

  it('passe en erreur sans boucler, puis « Réessayer » relance', async () => {
    const save = vi.fn().mockRejectedValueOnce(new Error('boom')).mockResolvedValue({})
    const { result, rerender } = setup(save)
    rerender({ value: { title: 'B' } })
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1100)
    })
    expect(result.current.status.kind).toBe('error')
    await act(async () => {
      await vi.advanceTimersByTimeAsync(5000)
    })
    expect(save).toHaveBeenCalledTimes(1)
    await act(async () => {
      await result.current.retry()
    })
    expect(save).toHaveBeenCalledTimes(2)
    expect(result.current.status.kind).toBe('saved')
  })

  it('n’enregistre pas en lecture seule', async () => {
    const save = vi.fn().mockResolvedValue({})
    const { rerender } = setup(save, false)
    rerender({ value: { title: 'Z' } })
    await act(async () => {
      await vi.advanceTimersByTimeAsync(3000)
    })
    expect(save).not.toHaveBeenCalled()
  })

  it('affiche l’instant renvoyé par l’enregistrement (resolveSavedAt)', async () => {
    const save = vi.fn().mockResolvedValue({ updatedAt: '2026-09-12T13:05:00Z' })
    const { result, rerender } = renderHook(
      ({ value }: { value: V }) =>
        useAutosave<V>({
          value,
          initial: { title: 'A' },
          enabled: true,
          save,
          resolveSavedAt: (r) => new Date((r as { updatedAt: string }).updatedAt),
        }),
      { initialProps: { value: { title: 'A' } } },
    )
    rerender({ value: { title: 'B' } })
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1100)
    })
    expect(result.current.status).toEqual({ kind: 'saved', at: new Date('2026-09-12T13:05:00Z') })
  })

  it('flush enregistre immédiatement', async () => {
    const save = vi.fn().mockResolvedValue({})
    const { result, rerender } = setup(save)
    rerender({ value: { title: 'Q' } })
    let ok = false
    await act(async () => {
      ok = await result.current.flush()
    })
    expect(ok).toBe(true)
    expect(save).toHaveBeenCalledTimes(1)
  })
})
