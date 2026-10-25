import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { reactive, ref, type Ref } from 'vue'
import App from './App.vue'
import { speechPlayback } from '@/speech/playback'
import type { ShellEvent } from '@/core/shell'

const context = vi.hoisted(() => ({
  route: {} as { fullPath: string },
  session: {} as {
    me: { id: string } | null
    state: string
    nativeSessionLapsed?: boolean
    load: () => Promise<void>
  },
  load: vi.fn(async () => undefined),
  router: {
    currentRoute: { value: { name: 'assistant' } },
    resolve: vi.fn(() => ({ name: 'timers' })),
    push: vi.fn(async () => undefined),
  },
  native: {
    opened: {} as Ref<{ link: string; sequence: number } | null>,
    account: vi.fn(async () => undefined),
    event: vi.fn(async () => null),
    refreshState: vi.fn(async () => 'off'),
    sync: vi.fn(async () => undefined),
  },
  shellListeners: new Set<(event: ShellEvent) => void>(),
  stopNative: vi.fn(),
  ambient: {
    account: vi.fn<(identity: unknown, options?: { preserveQueue?: boolean }) => Promise<void>>(
      async () => undefined,
    ),
    event: vi.fn(async () => undefined),
    refreshState: vi.fn(async () => undefined),
  },
}))
vi.mock('vue-router', () => ({
  useRoute: () => context.route,
  useRouter: () => context.router,
  RouterView: { template: '<div />' },
}))
vi.mock('@/stores/session', () => ({ useSession: () => context.session }))
vi.mock('@/speech/playback', () => ({ speechPlayback: { stop: vi.fn(), account: vi.fn() } }))
vi.mock('@/sections/notifications/native', () => ({ nativeNotifications: () => context.native }))
vi.mock('@/ambient/native', () => ({ nativeAmbientAudio: () => context.ambient }))
vi.mock('@/core/shell', () => ({
  useShell: () => ({
    on: (listener: (event: ShellEvent) => void) => {
      context.shellListeners.add(listener)
      return () => {
        context.shellListeners.delete(listener)
        context.stopNative()
      }
    },
  }),
}))

function render() {
  context.router.currentRoute.value.name = 'assistant'
  context.route = reactive({ fullPath: '/assistant' })
  context.session = reactive({ me: { id: 'first' }, state: 'ready', load: context.load })
  context.native.opened = ref(null)
  context.router.resolve.mockReturnValue({ name: 'timers' })
  return mount(App, { global: { stubs: { Toaster: true } } })
}

function shellEvent(event: ShellEvent) {
  for (const listener of context.shellListeners) listener(event)
}

afterEach(() => {
  vi.restoreAllMocks()
  vi.clearAllMocks()
})

describe('voice playback lifecycle', () => {
  it('keeps playback across SPA navigation and clears identity on session changes', () => {
    const wrapper = render()
    expect(speechPlayback.account).toHaveBeenCalledWith('first')
    context.route.fullPath = '/settings/speech'
    expect(speechPlayback.stop).not.toHaveBeenCalled()
    expect(speechPlayback.account).toHaveBeenCalledOnce()
    context.session.me = { id: 'second' }
    expect(speechPlayback.account).toHaveBeenLastCalledWith('second')
    context.session.state = 'signed-out'
    expect(speechPlayback.account).toHaveBeenLastCalledWith(undefined)
    context.session.me = null
    expect(speechPlayback.account).toHaveBeenLastCalledWith(undefined)
    wrapper.unmount()
  })

  it('stops on page hide and removes its listener when the app unmounts', () => {
    const wrapper = render()
    window.dispatchEvent(new Event('pagehide'))
    expect(speechPlayback.stop).toHaveBeenCalledOnce()
    wrapper.unmount()
    expect(speechPlayback.account).toHaveBeenLastCalledWith(undefined)
    window.dispatchEvent(new Event('pagehide'))
    expect(speechPlayback.stop).toHaveBeenCalledOnce()
  })

  it('does not stop playback when a browser tab becomes hidden or visible', () => {
    const wrapper = render()
    const visibility = vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('hidden')
    document.dispatchEvent(new Event('visibilitychange'))
    expect(speechPlayback.stop).not.toHaveBeenCalled()
    visibility.mockReturnValue('visible')
    document.dispatchEvent(new Event('visibilitychange'))
    expect(speechPlayback.stop).not.toHaveBeenCalled()
    expect(speechPlayback.account).toHaveBeenCalledExactlyOnceWith('first')
    wrapper.unmount()
  })

  it('clears the previous identity while session validity is unavailable', () => {
    const wrapper = render()
    context.session.state = 'unavailable'
    expect(speechPlayback.account).toHaveBeenLastCalledWith(undefined)
    context.session.state = 'ready'
    expect(speechPlayback.account).toHaveBeenLastCalledWith('first')
    wrapper.unmount()
  })
})

describe('native notification lifecycle', () => {
  it('keeps public join visits free of session and native refresh requests', async () => {
    const wrapper = render()
    context.router.currentRoute.value.name = 'join'
    shellEvent({ event: 'shell.foreground', payload: {} })
    vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible')
    document.dispatchEvent(new Event('visibilitychange'))
    await flushPromises()
    expect(context.load).not.toHaveBeenCalled()
    expect(context.native.refreshState).not.toHaveBeenCalled()
    expect(context.ambient.refreshState).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('refreshes the session on foreground and routes notification taps through the router', async () => {
    const wrapper = render()
    expect(context.native.account).toHaveBeenCalledExactlyOnceWith(context.session.me)
    shellEvent({ event: 'shell.foreground', payload: {} })
    await flushPromises()
    expect(context.load).toHaveBeenCalledOnce()
    expect(context.native.refreshState).toHaveBeenCalledOnce()
    expect(context.native.sync).toHaveBeenCalledOnce()
    expect(speechPlayback.account).toHaveBeenCalledExactlyOnceWith('first')
    context.native.opened.value = { link: '/timers', sequence: 1 }
    await flushPromises()
    expect(context.router.push).toHaveBeenCalledExactlyOnceWith('/timers')
    context.router.resolve.mockReturnValue({ name: 'not-found' })
    context.native.opened.value = { link: '/unknown', sequence: 2 }
    await flushPromises()
    expect(context.router.push).toHaveBeenCalledOnce()
    wrapper.unmount()
  })

  it('clears native identity and removes foreground and shell listeners on unmount', async () => {
    const wrapper = render()
    const opened = {
      event: 'notifications.opened',
      payload: { instance_id: 'home', user_id: 'first', device_id: 'device', link: '/timers' },
    }
    shellEvent(opened)
    expect(context.native.event).toHaveBeenCalledExactlyOnceWith(opened)
    wrapper.unmount()
    expect(context.native.account).toHaveBeenLastCalledWith(null)
    expect(context.stopNative).toHaveBeenCalledOnce()
    shellEvent({ event: 'shell.foreground', payload: {} })
    shellEvent(opened)
    vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible')
    document.dispatchEvent(new Event('visibilitychange'))
    window.dispatchEvent(new Event('pageshow'))
    await flushPromises()
    expect(context.native.event).toHaveBeenCalledOnce()
    expect(context.load).not.toHaveBeenCalled()
    expect(context.native.refreshState).not.toHaveBeenCalled()
    expect(context.native.sync).not.toHaveBeenCalled()
  })
})

describe('ambient audio lifecycle', () => {
  it('updates account lifetime and refreshes on foreground, without clearing on page hide or unmount', async () => {
    const wrapper = render()
    expect(context.ambient.account).toHaveBeenCalledExactlyOnceWith(context.session.me)
    shellEvent({ event: 'ambient.state', payload: { recording: true } })
    expect(context.ambient.event).toHaveBeenCalledWith({
      event: 'ambient.state',
      payload: { recording: true },
    })
    shellEvent({ event: 'shell.foreground', payload: {} })
    window.dispatchEvent(new Event('pageshow'))
    await flushPromises()
    expect(context.ambient.refreshState).toHaveBeenCalledTimes(2)
    window.dispatchEvent(new Event('pagehide'))
    wrapper.unmount()
    expect(context.ambient.account).toHaveBeenCalledOnce()
  })

  it('clears only when identity ends and preserves capture during a temporary network failure', () => {
    const wrapper = render()
    context.session.state = 'unavailable'
    expect(context.ambient.account).toHaveBeenCalledOnce()
    context.session.state = 'signed-out'
    expect(context.ambient.account).toHaveBeenLastCalledWith(null, { preserveQueue: false })
    wrapper.unmount()
  })

  it('preserves unsent audio throughout automatic401 transitions and while returning to the account', () => {
    const wrapper = render()
    context.session.nativeSessionLapsed = true
    context.session.me = null
    context.session.state = 'signed-out'
    const revoked = context.ambient.account.mock.calls.filter(([identity]) => identity === null)
    expect(revoked.length).toBeGreaterThan(0)
    expect(revoked.every(([, options]) => options?.preserveQueue === true)).toBe(true)
    context.session.me = { id: 'first' }
    expect(context.ambient.account).toHaveBeenLastCalledWith(null, { preserveQueue: true })
    context.session.state = 'ready'
    context.session.nativeSessionLapsed = false
    expect(context.ambient.account).toHaveBeenLastCalledWith(context.session.me)
    wrapper.unmount()
  })
})
