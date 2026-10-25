// @vitest-environment node
import { readFileSync } from 'node:fs'
import { runInNewContext } from 'node:vm'
import { describe, expect, it, vi } from 'vitest'

const source = readFileSync(new URL('../../../public/sw.js', import.meta.url), 'utf8')

function windowAt(path: string) {
  return {
    url: new URL(path, 'https://kaifaty.ru').href,
    focus: vi.fn().mockResolvedValue(undefined),
    postMessage: vi.fn(),
  }
}

function worker(windows: ReturnType<typeof windowAt>[] = []) {
  const handlers = new Map<string, (event: unknown) => void>()
  const openWindow = vi.fn().mockResolvedValue(undefined)
  const showNotification = vi.fn().mockResolvedValue(undefined)
  runInNewContext(source, {
    URL,
    self: {
      location: { origin: 'https://kaifaty.ru' },
      clients: { matchAll: async () => windows, openWindow },
      registration: { showNotification },
      addEventListener: (kind: string, handler: (event: unknown) => void) =>
        handlers.set(kind, handler),
    },
  })

  async function dispatch(kind: string, fields: Record<string, unknown>) {
    let waiting = Promise.resolve<unknown>(undefined)
    handlers.get(kind)!({ ...fields, waitUntil: (value: Promise<unknown>) => (waiting = value) })
    await waiting
  }

  return {
    openWindow,
    showNotification,
    click: (link: unknown) =>
      dispatch('notificationclick', {
        notification: { close: vi.fn(), data: { link } },
      }),
    push: (link: unknown) => dispatch('push', { data: { json: () => ({ link }) } }),
  }
}

describe('notification worker on a shared origin', () => {
  it('focuses an assistant screen while ignoring the dashboard, Forgejo and other origins', async () => {
    const others = [
      windowAt('/'),
      windowAt('/git/repos'),
      windowAt('https://other.example/assistant'),
    ]
    const assistant = windowAt('/circle/chat/thread?from=bookmark#last')
    const app = worker([...others, assistant])
    await app.click('/timers')
    expect(assistant.focus).toHaveBeenCalledOnce()
    expect(assistant.postMessage).toHaveBeenCalledWith({
      type: 'notification.open',
      link: '/timers',
    })
    for (const other of others) {
      expect(other.focus).not.toHaveBeenCalled()
      expect(other.postMessage).not.toHaveBeenCalled()
    }
    expect(app.openWindow).not.toHaveBeenCalled()
  })

  it('opens a new assistant window when only unrelated windows are open', async () => {
    const others = [
      windowAt('/'),
      windowAt('/git'),
      windowAt('/grafana'),
      windowAt('/assistant-other'),
    ]
    const app = worker(others)
    await app.click('/tasks/task-1')
    expect(app.openWindow).toHaveBeenCalledWith('/tasks/task-1')
    for (const other of others) expect(other.focus).not.toHaveBeenCalled()
  })

  it('keeps a valid assistant target and rejects unknown or malformed push links', async () => {
    const app = worker()
    await app.push('/tasks/task-1')
    expect(app.showNotification.mock.calls.at(-1)?.[1].data.link).toBe('/tasks/task-1')
    for (const link of [
      '/',
      '/git',
      '/grafana',
      '/assistant-other',
      '/tasks?x=1',
      '//evil',
      'https://evil',
      null,
      42,
    ]) {
      await app.push(link)
      expect(app.showNotification.mock.calls.at(-1)?.[1].data.link).toBe('/notifications')
    }
  })

  it('revalidates stored notification links before opening a window', async () => {
    const app = worker()
    for (const link of ['/', '/git', '//evil', 'https://evil', null]) {
      await app.click(link)
      expect(app.openWindow).toHaveBeenLastCalledWith('/notifications')
    }
  })
})
