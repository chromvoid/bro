import { afterEach, describe, expect, it, vi } from 'vitest'
import { connectShell, ShellBridge, ShellError } from './shell'

function applePort(answer: (request: { id: string; type: string }) => object | null) {
  const sent: string[] = []
  const scope: {
    webkit: {
      messageHandlers: {
        AssistantShell: { postMessage(message: string): Promise<unknown> }
      }
    }
    __assistantShellEvent?: (data: unknown) => void
  } = {
    webkit: {
      messageHandlers: {
        AssistantShell: {
          async postMessage(message: string) {
            sent.push(message)
            const reply = answer(JSON.parse(message) as { id: string; type: string })
            return reply ? JSON.stringify(reply) : null
          },
        },
      },
    },
  }
  return { sent, scope, inject: (data: unknown) => scope.__assistantShellEvent?.(data) }
}

describe('shell bridge', () => {
  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  it('is absent in a browser', () => {
    expect(connectShell({})).toBeNull()
    expect(connectShell({ webkit: { messageHandlers: {} } })).toBeNull()
  })

  it('does not connect the old Android shell bridge', () => {
    const postMessage = vi.fn()
    const addEventListener = vi.fn()
    expect(connectShell({ AssistantShell: { postMessage, addEventListener } })).toBeNull()
    expect(postMessage).not.toHaveBeenCalled()
    expect(addEventListener).not.toHaveBeenCalled()
  })

  it('counts UTF-8 bytes and allows only schedules up to 128 KiB and 60 items', async () => {
    const post = vi.fn()
    const bridge = new ShellBridge(post)
    await expect(
      bridge.request('notifications.session', { text: 'я'.repeat(9_000) }),
    ).rejects.toThrow('too_large')
    await expect(
      bridge.request('notifications.schedule', { items: Array(61).fill({}) }),
    ).rejects.toThrow('too_large')
    await expect(
      bridge.request('notifications.schedule', { items: [{ body: 'я'.repeat(66_000) }] }),
    ).rejects.toThrow('too_large')
    expect(post).not.toHaveBeenCalled()
    const accepted = bridge.request('notifications.schedule', {
      items: [{ body: 'я'.repeat(9_000) }],
    })
    bridge.received(JSON.stringify({ id: '4', ok: true, result: { installed: [] } }))
    await expect(accepted).resolves.toEqual({ installed: [] })
  })

  it('asks the iOS shell for its capabilities and matches replies by id', async () => {
    const apple = applePort(({ id, type }) =>
      type === 'shell.capabilities'
        ? { id, ok: true, result: { shell_api: 1, platform: 'ios' } }
        : { id, ok: false, error: 'unknown_type' },
    )
    const bridge = connectShell(apple.scope)!
    expect(await bridge.capabilities()).toEqual({
      shell_api: 1,
      platform: 'ios',
    })
    await expect(bridge.request('unknown.command')).rejects.toThrow(new ShellError('unknown_type'))
    expect(JSON.parse(apple.sent[0]!)).toEqual({
      id: '1',
      type: 'shell.capabilities',
      payload: {},
    })
  })

  it('ignores stray and malformed messages and offers nothing to another bridge version', async () => {
    const apple = applePort(({ id }) => ({
      id,
      ok: true,
      result: { shell_api: 2, platform: 'ios' },
    }))
    const bridge = connectShell(apple.scope)!
    apple.inject('{"id":"99","ok":true,"result":1}')
    apple.inject('not json')
    apple.inject({ id: '1' })
    expect(await bridge.capabilities()).toBeNull()
  })

  it('uses the iOS reply handler', async () => {
    const postMessage = vi.fn(async (message: string) => {
      const { id } = JSON.parse(message) as { id: string }
      return JSON.stringify({
        id,
        ok: true,
        result: { shell_api: 1, platform: 'ios' },
      })
    })
    const bridge = connectShell({
      webkit: { messageHandlers: { AssistantShell: { postMessage } } },
    })!
    expect(await bridge.capabilities()).toMatchObject({ platform: 'ios' })
  })

  it('recognizes optional ambient capture without changing the bridge version', async () => {
    const apple = applePort(({ id }) => ({
      id,
      ok: true,
      result: {
        shell_api: 1,
        platform: 'ios',
        ambient_audio: true,
      },
    }))
    const bridge = connectShell(apple.scope)!
    expect(await bridge.capabilities()).toMatchObject({
      shell_api: 1,
      ambient_audio: true,
    })
  })

  it('recognizes the location bridge and its background recording', async () => {
    const apple = applePort(({ id }) => ({
      id,
      ok: true,
      result: { shell_api: 1, platform: 'ios', location: { background: false } },
    }))
    const bridge = connectShell(apple.scope)!
    expect((await bridge.capabilities())?.location).toEqual({ background: false })
  })

  it('gives up on a shell that does not answer', async () => {
    vi.useFakeTimers()
    const bridge = new ShellBridge(() => undefined, 5_000)
    const pending = bridge.request('shell.capabilities')
    vi.advanceTimersByTime(5_000)
    await expect(pending).rejects.toThrow(new ShellError('timeout'))
  })

  it('hands events from the shell to listeners and waits longer for a person', async () => {
    vi.useFakeTimers()
    const apple = applePort(() => null)
    const bridge = connectShell(apple.scope, 1_000)!
    const events: unknown[] = []
    const stop = bridge.on((event) => events.push(event))
    apple.inject('{"event":"notifications.opened","payload":{"link":"/timers"}}')
    apple.inject('{"event":"Bad Event"}')
    expect(events).toEqual([{ event: 'notifications.opened', payload: { link: '/timers' } }])
    stop()
    apple.inject('{"event":"notifications.token","payload":{}}')
    expect(events).toHaveLength(1)
    const permission = bridge.request('notifications.permission', {}, 60_000)
    vi.advanceTimersByTime(5_000)
    const id = JSON.parse(apple.sent.at(-1)!).id as string
    apple.inject(JSON.stringify({ id, ok: true, result: { granted: true } }))
    await expect(permission).resolves.toEqual({ granted: true })
  })

  it('gives the iOS shell a function to deliver its events', () => {
    const scope: Record<string, unknown> = {
      webkit: { messageHandlers: { AssistantShell: { postMessage: () => Promise.resolve(null) } } },
    }
    const bridge = connectShell(scope)!
    const events: unknown[] = []
    bridge.on((event) => events.push(event))
    ;(scope.__assistantShellEvent as (data: unknown) => void)(
      '{"event":"notifications.opened","payload":{"link":"/notifications"}}',
    )
    expect(events).toEqual([{ event: 'notifications.opened', payload: { link: '/notifications' } }])
  })
})
