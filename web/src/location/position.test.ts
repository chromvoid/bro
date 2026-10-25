import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const shell = vi.hoisted(() => ({
  capabilities: null as Record<string, unknown> | null,
  replies: {} as Record<string, unknown>,
  calls: [] as Array<[string, number | undefined]>,
}))
vi.mock('@/core/shell', () => ({
  useShell: () => ({
    ready: async () => shell.capabilities,
    request: async (type: string, _payload?: unknown, timeout?: number) => {
      shell.calls.push([type, timeout])
      const reply = shell.replies[type]
      if (reply instanceof Error) throw reply
      return reply
    },
  }),
}))

import { positionSource } from './position'

const POSITION = {
  coords: { latitude: 55.73812, longitude: 37.58004, accuracy: 39.2 },
  timestamp: Date.parse('2026-10-09T18:10:00Z'),
}

function browser(state: PermissionState, outcome: 'ok' | 'denied' = 'ok') {
  const getCurrentPosition = vi.fn((resolve: PositionCallback, reject: PositionErrorCallback) =>
    outcome === 'ok'
      ? resolve(POSITION as unknown as GeolocationPosition)
      : reject({ code: 1 } as GeolocationPositionError),
  )
  vi.stubGlobal('navigator', {
    geolocation: { getCurrentPosition },
    permissions: { query: async () => ({ state }) },
  })
  return getCurrentPosition
}

describe('position source', () => {
  beforeEach(() => {
    shell.capabilities = null
    shell.replies = {}
    shell.calls = []
  })
  afterEach(() => vi.unstubAllGlobals())

  it('reads a granted browser position without prompting, with ordinary accuracy', async () => {
    const locate = browser('granted')
    const source = await positionSource()
    expect(source.kind).toBe('browser')
    expect(await source.current()).toEqual({
      latitude: 55.73812,
      longitude: 37.58004,
      accuracy_m: 40,
      captured_at: '2026-10-09T18:10:00.000Z',
    })
    expect((locate.mock.calls[0] as unknown[])[2]).toMatchObject({
      enableHighAccuracy: false,
      maximumAge: 600_000,
    })
  })

  it('does not touch the browser position before permission, and reports a refusal', async () => {
    const locate = browser('prompt', 'denied')
    const source = await positionSource()
    expect(await source.current()).toBeNull()
    expect(locate).not.toHaveBeenCalled()
    expect(await source.request()).toBe('denied')
  })

  it('uses the shell bridge when the app offers it, with time for the system dialog', async () => {
    shell.capabilities = {
      shell_api: 1,
      platform: 'ios',
      location: { background: false },
    }
    shell.replies = {
      'location.status': { permission: 'granted' },
      'location.request': { permission: 'granted' },
      'location.current': {
        latitude: 1.5,
        longitude: 2.5,
        accuracy_m: 12.2,
        captured_at: '2026-10-09T18:00:00Z',
      },
    }
    const source = await positionSource()
    expect(source.kind).toBe('shell')
    expect(await source.status()).toBe('granted')
    expect(await source.request()).toBe('granted')
    expect((await source.current())?.accuracy_m).toBe(13)
    expect(shell.calls.find(([type]) => type === 'location.request')?.[1]).toBe(60_000)
  })

  it('treats a shell without the bridge as an app to update and never falls back to the WebView', async () => {
    const locate = browser('granted')
    shell.capabilities = { shell_api: 1, platform: 'ios' }
    const source = await positionSource()
    expect(source.kind).toBe('none')
    expect(await source.status()).toBe('unavailable')
    expect(await source.current()).toBeNull()
    expect(locate).not.toHaveBeenCalled()
  })
})
