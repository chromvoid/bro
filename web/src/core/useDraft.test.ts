import { effectScope, nextTick, ref } from 'vue'
import { flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useDraft } from './useDraft'
const mocks = vi.hoisted(() => ({
  store: { draft: vi.fn(), saveDraft: vi.fn(), deleteDraft: vi.fn() },
}))
vi.mock('@/stores/session', () => ({ useSession: () => ({ account: { store: mocks.store } }) }))
const scopes: ReturnType<typeof effectScope>[] = []
async function setup() {
  const scope = effectScope()
  scopes.push(scope)
  const target = ref<string | null>('task:a')
  const draft = scope.run(() => useDraft(target, () => ({ text: '' })))!
  await flushPromises()
  return { draft, target }
}
beforeEach(() => {
  vi.useFakeTimers()
  mocks.store.draft.mockReset().mockResolvedValue(undefined)
  mocks.store.saveDraft.mockReset().mockResolvedValue(undefined)
  mocks.store.deleteDraft.mockReset().mockResolvedValue(undefined)
})
afterEach(() => {
  scopes.splice(0).forEach((scope) => scope.stop())
  vi.clearAllTimers()
  vi.useRealTimers()
})
describe('saved form draft lifecycle', () => {
  it('cancels the debounced old draft before clearing a successfully saved form', async () => {
    const { draft } = await setup()
    draft.value.value.text = 'already saved'
    await nextTick()
    await draft.clear()
    await vi.advanceTimersByTimeAsync(400)
    expect(mocks.store.saveDraft).not.toHaveBeenCalled()
    expect(mocks.store.deleteDraft).toHaveBeenCalledWith('task:a')
  })
  it('waits for an in-flight write before deleting so old data cannot reappear', async () => {
    let finish!: () => void
    mocks.store.saveDraft.mockImplementationOnce(
      () =>
        new Promise<void>((resolve) => {
          finish = resolve
        }),
    )
    const { draft } = await setup()
    draft.value.value.text = 'already saved'
    await nextTick()
    await vi.advanceTimersByTimeAsync(300)
    expect(mocks.store.saveDraft).toHaveBeenCalledOnce()
    const cleared = draft.clear()
    await Promise.resolve()
    expect(mocks.store.deleteDraft).not.toHaveBeenCalled()
    finish()
    await cleared
    expect(mocks.store.deleteDraft).toHaveBeenCalledWith('task:a')
  })
  it('ignores a late draft read for a task that is no longer open', async () => {
    let finish!: (value: { text: string }) => void
    mocks.store.draft.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve
        }),
    )
    const { draft, target } = await setup()
    target.value = 'task:b'
    await nextTick()
    await flushPromises()
    draft.value.value.text = 'current task'
    finish({ text: JSON.stringify({ text: 'old task' }) })
    await Promise.resolve()
    await nextTick()
    expect(draft.value.value.text).toBe('current task')
  })
  it('keeps both drafts when edits on neighbouring tasks occur within one debounce window', async () => {
    const { draft, target } = await setup()
    await flushPromises()
    draft.value.value.text = 'task A draft'
    await nextTick()
    target.value = 'task:b'
    await nextTick()
    await flushPromises()
    draft.value.value.text = 'task B draft'
    await nextTick()
    await vi.advanceTimersByTimeAsync(300)
    expect(
      mocks.store.saveDraft.mock.calls.map(([entry]) => [entry.target, JSON.parse(entry.text)]),
    ).toEqual([
      ['task:a', { text: 'task A draft' }],
      ['task:b', { text: 'task B draft' }],
    ])
  })
  it('waits for the flushed snapshot before restoring a task opened again immediately', async () => {
    const kept = new Map<string, { text: string }>()
    mocks.store.saveDraft.mockImplementation(async (entry: { target: string; text: string }) => {
      kept.set(entry.target, { text: entry.text })
    })
    mocks.store.draft.mockImplementation(async (key: string) => kept.get(key))
    const { draft, target } = await setup()
    await flushPromises()
    draft.value.value.text = 'task A draft'
    await nextTick()
    target.value = 'task:b'
    await nextTick()
    await flushPromises()
    target.value = 'task:a'
    await nextTick()
    await flushPromises()
    expect(draft.value.value.text).toBe('task A draft')
    expect(draft.restored.value).toBe(true)
  })

  it('clears the saved task without cancelling the next task draft', async () => {
    const { draft, target } = await setup()
    draft.value.value.text = 'task A saved'
    await nextTick()
    target.value = 'task:b'
    await nextTick()
    await flushPromises()
    draft.value.value.text = 'task B draft'
    draft.restored.value = true
    await nextTick()
    await draft.clear('task:a')
    expect(mocks.store.deleteDraft).toHaveBeenCalledWith('task:a')
    expect(draft.value.value.text).toBe('task B draft')
    expect(draft.restored.value).toBe(true)
    await vi.advanceTimersByTimeAsync(300)
    expect(mocks.store.saveDraft).toHaveBeenLastCalledWith(
      expect.objectContaining({
        target: 'task:b',
        text: JSON.stringify({ text: 'task B draft' }),
      }),
    )
  })
})
