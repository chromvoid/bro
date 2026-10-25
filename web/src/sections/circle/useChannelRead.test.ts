import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h, ref, useTemplateRef, type Ref } from 'vue'
import { useChannelRead } from './useChannelRead'

const mocks = vi.hoisted(() => ({
  post: vi.fn(),
  visible: null as unknown as Ref<DocumentVisibilityState>,
}))
vi.mock('@/core/client', () => ({ api: { post: mocks.post } }))
vi.mock('@vueuse/core', () => ({ useDocumentVisibility: () => mocks.visible }))

type Observed = {
  callback: IntersectionObserverCallback
  element: Element | null
  disconnect: () => void
}
let observers: Observed[]

beforeEach(() => {
  mocks.post.mockReset().mockResolvedValue({ data: { read: true } })
  mocks.visible = ref('visible')
  observers = []
  vi.stubGlobal(
    'IntersectionObserver',
    class {
      entry: Observed
      constructor(callback: IntersectionObserverCallback) {
        this.entry = { callback, element: null, disconnect: vi.fn() }
        observers.push(this.entry)
      }
      observe(element: Element) {
        this.entry.element = element
      }
      disconnect() {
        this.entry.disconnect()
      }
    },
  )
})
afterEach(() => vi.unstubAllGlobals())

function intersect(observer = observers.at(-1)!, visible = true) {
  observer.callback(
    [{ target: observer.element, isIntersecting: visible } as IntersectionObserverEntry],
    {} as IntersectionObserver,
  )
}

function reader() {
  const channel = ref<string | null>('channel-a')
  const latest = ref<string | null>('message-a')
  const enabled = ref(true)
  const onRead = vi.fn()
  const wrapper = mount(
    defineComponent({
      setup() {
        const marker = useTemplateRef<HTMLElement>('marker')
        const root = useTemplateRef<HTMLElement>('root')
        useChannelRead(channel, latest, marker, { root, enabled, onRead })
        return () =>
          h('div', { ref: 'root' }, [
            latest.value ? h('span', { key: latest.value, ref: 'marker' }) : null,
          ])
      },
    }),
  )
  return { wrapper, channel, latest, enabled, onRead }
}

describe('displayed channel read marker', () => {
  it('acknowledges the exact fetched message only after its rendered marker is visible', async () => {
    const view = reader()
    await flushPromises()
    expect(mocks.post).not.toHaveBeenCalled()
    intersect(undefined, false)
    expect(mocks.post).not.toHaveBeenCalled()
    intersect()
    await flushPromises()
    expect(mocks.post).toHaveBeenCalledExactlyOnceWith('/v1/channels/channel-a/read', {
      through_message_id: 'message-a',
    })
    expect(view.onRead).toHaveBeenCalledOnce()
    intersect()
    await flushPromises()
    expect(mocks.post).toHaveBeenCalledTimes(1)
    view.wrapper.unmount()
  })

  it('does not mark a hidden document or hidden discussion tab, then observes anew', async () => {
    mocks.visible.value = 'hidden'
    const view = reader()
    await flushPromises()
    expect(observers).toHaveLength(0)
    view.enabled.value = false
    mocks.visible.value = 'visible'
    await flushPromises()
    expect(observers).toHaveLength(0)
    view.enabled.value = true
    await flushPromises()
    const beforeHide = observers.at(-1)!
    mocks.visible.value = 'hidden'
    // Even before Vue cleans up the old observer, visibility suppresses this callback.
    intersect(beforeHide)
    await flushPromises()
    expect(mocks.post).not.toHaveBeenCalled()
    expect(beforeHide.disconnect).toHaveBeenCalled()
    mocks.visible.value = 'visible'
    await flushPromises()
    intersect()
    await flushPromises()
    expect(mocks.post).toHaveBeenCalledOnce()
    view.wrapper.unmount()
  })

  it('does not acknowledge a newer message arriving between display and the request', async () => {
    let resolve!: () => void
    mocks.post.mockImplementationOnce(() => new Promise<void>((done) => (resolve = done)))
    const view = reader()
    await flushPromises()
    const old = observers.at(-1)!
    intersect(old)
    expect(mocks.post).toHaveBeenLastCalledWith('/v1/channels/channel-a/read', {
      through_message_id: 'message-a',
    })
    view.latest.value = 'message-b'
    await flushPromises()
    intersect(old)
    resolve()
    await flushPromises()
    expect(mocks.post).toHaveBeenCalledTimes(1)
    intersect()
    await flushPromises()
    expect(mocks.post).toHaveBeenLastCalledWith('/v1/channels/channel-a/read', {
      through_message_id: 'message-b',
    })
    view.wrapper.unmount()
  })

  it('ignores stale callbacks after changing channel or unmounting', async () => {
    const view = reader()
    await flushPromises()
    const previous = observers.at(-1)!
    view.channel.value = 'channel-b'
    view.latest.value = null
    await flushPromises()
    intersect(previous)
    expect(mocks.post).not.toHaveBeenCalled()
    view.latest.value = 'message-b'
    await flushPromises()
    const current = observers.at(-1)!
    intersect(current)
    await flushPromises()
    expect(mocks.post).toHaveBeenLastCalledWith('/v1/channels/channel-b/read', {
      through_message_id: 'message-b',
    })
    view.wrapper.unmount()
    intersect(current)
    await flushPromises()
    expect(mocks.post).toHaveBeenCalledTimes(1)
  })

  it('allows a later visibility event to retry a refused acknowledgement', async () => {
    mocks.post.mockRejectedValueOnce(new Error('Network unavailable'))
    const view = reader()
    await flushPromises()
    intersect()
    await flushPromises()
    expect(view.onRead).not.toHaveBeenCalled()
    intersect()
    await flushPromises()
    expect(mocks.post).toHaveBeenCalledTimes(2)
    expect(view.onRead).toHaveBeenCalledOnce()
    view.wrapper.unmount()
  })
})
