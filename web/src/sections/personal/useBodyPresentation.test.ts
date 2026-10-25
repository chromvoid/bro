import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { computed, defineComponent, reactive } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/core/http'
import { useBodyPresentation } from './useBodyPresentation'

const mocks = vi.hoisted(() => ({
  get: vi.fn(),
  operate: vi.fn(),
  failed: vi.fn(),
  session: null as unknown,
}))
vi.mock('@/core/client', () => ({ api: { get: mocks.get } }))
vi.mock('@/stores/session', () => ({ useSession: () => mocks.session }))
vi.mock('./usePersonal', () => ({
  usePersonal: () => ({ operate: mocks.operate, failed: mocks.failed }),
}))
let state: { me: { id: string }; canWrite: boolean; can: (scope: string) => boolean }
let queries: QueryClient
let wrapper: VueWrapper | undefined
let presentation: ReturnType<typeof useBodyPresentation>
function show() {
  wrapper = mount(
    defineComponent({
      setup() {
        presentation = useBodyPresentation()
        const character = computed(() => presentation.characterId.value)
        return () => character.value
      },
    }),
    { global: { plugins: [[VueQueryPlugin, { queryClient: queries }]] } },
  )
}
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => {
    resolve = done
  })
  return { promise, resolve }
}
beforeEach(() => {
  state = reactive({
    me: { id: 'one' },
    canWrite: true,
    can: (scope: string) => scope !== 'personal.write' || state.canWrite,
  })
  mocks.session = state
  queries = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  mocks.get.mockReset().mockResolvedValue({ character_id: 'sculpture', revision: 0 })
  mocks.operate.mockReset()
  mocks.failed.mockReset()
})
afterEach(() => {
  wrapper?.unmount()
  queries.clear()
})

describe('account body presentation', () => {
  it('waits for the saved choice before loading a model and writes using its receipt revision', async () => {
    const read = deferred<unknown>()
    mocks.get.mockReturnValueOnce(read.promise)
    show()
    expect(presentation.ready.value).toBe(false)
    expect(presentation.writable.value).toBe(false)
    read.resolve({ character_id: 'spartan', revision: 4 })
    await flushPromises()
    expect(presentation.ready.value).toBe(true)
    expect(presentation.characterId.value).toBe('spartan')
    mocks.operate.mockResolvedValueOnce({
      body_presentation: { character_id: 'sculpture', revision: 5 },
    })
    await presentation.select('sculpture')
    expect(mocks.operate).toHaveBeenCalledWith('body.presentation.set', {
      character_id: 'sculpture',
      revision: 4,
    })
    expect(presentation.characterId.value).toBe('sculpture')
    await presentation.select('sculpture')
    expect(mocks.operate).toHaveBeenCalledOnce()
  })
  it('refreshes with the personal domain and keeps cached selections owned by the account', async () => {
    show()
    await flushPromises()
    mocks.get.mockResolvedValueOnce({ character_id: 'spartan', revision: 3 })
    await queries.invalidateQueries({ queryKey: ['personal'] })
    expect(presentation.characterId.value).toBe('spartan')
    mocks.get.mockResolvedValueOnce({ character_id: 'sculpture', revision: 0 })
    state.me = { id: 'two' }
    await flushPromises()
    expect(presentation.characterId.value).toBe('sculpture')
    expect(queries.getQueryData(['personal', 'body-presentation', 'one'])).toEqual({
      character_id: 'spartan',
      revision: 3,
    })
  })
  it('does not install a late receipt into another account or overwrite a newer revision', async () => {
    show()
    await flushPromises()
    const write = deferred<unknown>()
    mocks.operate.mockReturnValueOnce(write.promise)
    const saving = presentation.select('spartan')
    expect(presentation.saving.value).toBe(true)
    await presentation.select('spartan')
    expect(mocks.operate).toHaveBeenCalledOnce()
    state.me = { id: 'two' }
    await flushPromises()
    write.resolve({ body_presentation: { character_id: 'spartan', revision: 1 } })
    await saving
    expect(presentation.characterId.value).toBe('sculpture')
    expect(queries.getQueryData(['personal', 'body-presentation', 'one'])).toEqual({
      character_id: 'sculpture',
      revision: 0,
    })
    mocks.operate.mockResolvedValueOnce({
      body_presentation: { character_id: 'spartan', revision: 1 },
    })
    queries.setQueryData(['personal', 'body-presentation', 'two'], {
      character_id: 'sculpture',
      revision: 2,
    })
    await presentation.select('spartan')
    expect(presentation.characterId.value).toBe('sculpture')
  })
  it('leaves the saved selection visible on refusal and never writes for a read-only account', async () => {
    show()
    await flushPromises()
    const conflict = new ApiError(409, 'conflict', null, null, null)
    mocks.operate.mockRejectedValueOnce(conflict)
    await presentation.select('spartan')
    expect(presentation.characterId.value).toBe('sculpture')
    expect(mocks.failed).toHaveBeenCalledWith(conflict)
    state.canWrite = false
    await presentation.select('spartan')
    expect(mocks.operate).toHaveBeenCalledOnce()
  })
  it('retains the default poster with retry when the first read fails', async () => {
    mocks.get.mockRejectedValueOnce(new Error('unavailable'))
    show()
    await flushPromises()
    expect(presentation.ready.value).toBe(true)
    expect(presentation.isError.value).toBe(true)
    expect(presentation.characterId.value).toBe('sculpture')
    expect(presentation.writable.value).toBe(false)
    expect(mocks.operate).not.toHaveBeenCalled()
  })
  it('keeps the default figure while a failed read is retried', async () => {
    mocks.get.mockRejectedValueOnce(new Error('unavailable'))
    show()
    await flushPromises()
    expect(presentation.ready.value).toBe(true)
    const retry = deferred<unknown>()
    mocks.get.mockReturnValueOnce(retry.promise)
    void queries.invalidateQueries({ queryKey: ['personal'] })
    await flushPromises()
    expect(presentation.isPending.value).toBe(true)
    expect(presentation.ready.value).toBe(true)
    expect(presentation.characterId.value).toBe('sculpture')
    retry.resolve({ character_id: 'spartan', revision: 2 })
    await flushPromises()
    expect(presentation.characterId.value).toBe('spartan')
  })
})
