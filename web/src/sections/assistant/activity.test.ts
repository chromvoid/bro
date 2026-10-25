import { describe, expect, it, vi } from 'vitest'
import {
  Activity as ActivitySchema,
  ActivityStream,
  publicPreview,
  stepLabel,
  type Activity,
  type ActivityConnection,
} from './activity'

const snapshot = (overrides: Partial<Activity> = {}): Activity => ({
  message_id: 'message',
  turn_id: 'turn',
  run_id: 'run',
  seq: 1,
  status: 'active',
  phase: 'generating',
  preview_text: 'Черновой ответ',
  source: 'assistant',
  started_at: '2026-10-02T12:00:00Z',
  updated_at: '2026-10-02T12:00:01Z',
  elapsed_ms: 1000,
  model_call: 1,
  attempt: 1,
  steps: [],
  usage: null,
  ...overrides,
})
const frame = (value: unknown) => `event: activity\ndata: ${JSON.stringify(value)}\n\n`
const response = (...values: unknown[]) =>
  new Response(values.map(frame).join(''), { headers: { 'Content-Type': 'text/event-stream' } })

function setup(responses: Response[]) {
  const snapshots: Activity[] = []
  const connections: ActivityConnection[] = []
  const terminal = vi.fn()
  const fetcher = vi.fn<typeof fetch>(
    async () => responses.shift() ?? new Response('', { status: 503 }),
  )
  const wait = vi.fn<(ms: number, signal: AbortSignal) => Promise<void>>(async () => {})
  const stream = new ActivityStream({
    messageId: 'message',
    onSnapshot: (value) => snapshots.push(value),
    onConnection: (value) => connections.push(value),
    onTerminal: terminal,
    fetcher,
    wait,
  })
  return { stream, snapshots, connections, terminal, fetcher, wait }
}

describe('read-only activity snapshots', () => {
  it('accepts the server text bound with astral Unicode characters', () => {
    const confirmed = [{ id: 'operation', service: 'nutrition', text: '🍎'.repeat(16_384) }]
    expect(ActivitySchema.parse(snapshot({ confirmed })).confirmed).toEqual(confirmed)
  })

  it('accepts changed durable confirmations between model events with the same sequence', async () => {
    const receipt = { id: 'operation', service: 'nutrition', text: 'Записано: йогурт — 180 ккал' }
    const h = setup([
      response(
        snapshot({ confirmed: [] }),
        snapshot({ confirmed: [receipt] }),
        snapshot({ confirmed: [receipt] }),
        snapshot({ confirmed: [] }),
        snapshot({ status: 'completed', seq: 2, confirmed: [] }),
      ),
    ])
    await h.stream.start()
    expect(h.snapshots.map((value) => value.confirmed)).toEqual([[], [receipt], [], []])
  })

  it('reconnects with a full snapshot, ignores replay, and replaces text instead of appending', async () => {
    const h = setup([
      response(snapshot({ seq: 2, preview_text: 'Первый' })),
      response(
        snapshot({ seq: 1, preview_text: 'Старый' }),
        snapshot({ seq: 2, preview_text: 'Первый' }),
        snapshot({ seq: 2, preview_text: 'Недопустимый повтор' }),
        snapshot({ seq: 3, preview_text: 'Полный ответ' }),
        snapshot({ seq: 4, status: 'completed', preview_text: '' }),
      ),
    ])
    await h.stream.start()
    expect(h.snapshots.map((value) => value.preview_text)).toEqual([
      'Первый',
      'Первый',
      'Полный ответ',
      '',
    ])
    expect(h.terminal).toHaveBeenCalledExactlyOnceWith('completed')
    expect(h.fetcher).toHaveBeenCalledTimes(2)
    expect(h.fetcher).toHaveBeenCalledWith(
      '/v1/conversation/messages/message/activity',
      expect.objectContaining({ credentials: 'same-origin', cache: 'no-store' }),
    )
    // No admission/recovery endpoints, last-event cursor, or writes participate in live text.
    expect(h.fetcher.mock.calls.every((args) => args[1]?.method === undefined)).toBe(true)
  })

  it('ignores another message, a retired run, and events after terminal completion', async () => {
    const h = setup([
      response(
        snapshot({ message_id: 'other' }),
        snapshot({ run_id: 'run-a' }),
        snapshot({ run_id: 'run-b', preview_text: 'Новый шаг' }),
        snapshot({ run_id: 'run-a', seq: 99 }),
        snapshot({ run_id: 'run-b', seq: 2, status: 'failed', preview_text: '' }),
        snapshot({ run_id: 'run-b', seq: 3 }),
      ),
    ])
    await h.stream.start()
    expect(h.snapshots.map((value) => [value.run_id, value.seq])).toEqual([
      ['run-a', 1],
      ['run-b', 1],
      ['run-b', 2],
    ])
    expect(h.terminal).toHaveBeenCalledExactlyOnceWith('failed')
  })

  it('reads again after a temporary unavailable snapshot, without replaying a request', async () => {
    const h = setup([
      response(snapshot({ status: 'unavailable', run_id: null, seq: 0 })),
      response(snapshot(), snapshot({ status: 'completed', seq: 2 })),
    ])
    await h.stream.start()
    expect(h.fetcher).toHaveBeenCalledTimes(2)
    expect(h.terminal).toHaveBeenCalledExactlyOnceWith('completed')
  })

  it('bounds reconnect attempts and never retries denied or missing messages', async () => {
    const h = setup([])
    await h.stream.start()
    expect(h.fetcher).toHaveBeenCalledTimes(6)
    expect(h.wait.mock.calls.map((args) => args[0])).toEqual([1000, 2000, 4000, 8000, 16000])
    expect(h.connections.at(-1)).toBe('offline')
    for (const status of [401, 403, 404]) {
      const denied = setup([new Response('', { status })])
      await denied.stream.start()
      expect(denied.fetcher).toHaveBeenCalledTimes(1)
      expect(denied.snapshots).toEqual([])
    }
  })

  it('does not deliver an awaited response after disposal', async () => {
    let resolve!: (response: Response) => void
    const onSnapshot = vi.fn()
    const stream = new ActivityStream({
      messageId: 'message',
      onSnapshot,
      onConnection: vi.fn(),
      onTerminal: vi.fn(),
      fetcher: () =>
        new Promise((done) => {
          resolve = done
        }),
    })
    const running = stream.start()
    stream.stop()
    resolve(response(snapshot()))
    await running
    expect(onSnapshot).not.toHaveBeenCalled()
  })

  it('times out a blackholed body and reconnects by GET to a complete snapshot', async () => {
    const cancelled = vi.fn()
    const body = new ReadableStream<Uint8Array>({
      start(writer) {
        writer.enqueue(new TextEncoder().encode(frame(snapshot())))
      },
      cancel: cancelled,
    })
    const replies = [
      new Response(body),
      response(snapshot({ seq: 2, status: 'completed', preview_text: '' })),
    ]
    const connection = vi.fn()
    const terminal = vi.fn()
    const stream = new ActivityStream({
      messageId: 'message',
      idleMs: 15,
      fetcher: async () => replies.shift()!,
      wait: async () => {},
      onSnapshot: vi.fn(),
      onConnection: connection,
      onTerminal: terminal,
    })
    await stream.start()
    expect(cancelled).toHaveBeenCalledOnce()
    expect(connection).toHaveBeenCalledWith('reconnecting')
    expect(terminal).toHaveBeenCalledExactlyOnceWith('completed')
  })

  it('does not report headers-only reconnect as live, then accepts its equal-sequence full snapshot', async () => {
    let writer!: ReadableStreamDefaultController<Uint8Array>
    const second = new Response(
      new ReadableStream<Uint8Array>({
        start(value) {
          writer = value
        },
      }),
    )
    const h = setup([response(snapshot()), second])
    const running = h.stream.start()
    await new Promise((resolve) => setTimeout(resolve, 5))
    expect(h.connections.at(-1)).toBe('reconnecting')
    expect(h.connections.filter((state) => state === 'connected')).toHaveLength(1)
    writer.enqueue(
      new TextEncoder().encode(
        frame(snapshot()) + frame(snapshot({ seq: 2, status: 'completed', preview_text: '' })),
      ),
    )
    await running
    expect(h.snapshots.map((value) => value.seq)).toEqual([1, 1, 2])
  })

  it('shows only active public assistant text and translates observed tool names', () => {
    expect(publicPreview(snapshot())).toBe('Черновой ответ')
    expect(publicPreview(snapshot({ source: 'compaction' }))).toBe('')
    expect(publicPreview(snapshot({ status: 'failed' }))).toBe('')
    expect(
      stepLabel({
        id: 'x',
        kind: 'tool',
        label: 'nutrition.search',
        status: 'completed',
        started_at: '',
        duration_ms: 5,
      }),
    ).toBe('assistant.progress.searchingFood')
    expect(
      stepLabel({
        id: 'x',
        kind: 'tool',
        label: 'internal.secret',
        status: 'running',
        started_at: '',
        duration_ms: null,
      }),
    ).toBe('assistant.activity.tool')
  })
})

it('cancels every rejected activity response body before reconnecting', async () => {
  const bodies = [429, 500, 503].map((status) => {
    const cancel = vi.fn()
    return { cancel, response: new Response(new ReadableStream({ cancel }), { status }) }
  })
  const replies = [
    ...bodies.map((body) => body.response),
    response(snapshot({ status: 'completed' })),
  ]
  const h = setup(replies)
  await h.stream.start()
  bodies.forEach((body) => expect(body.cancel).toHaveBeenCalledOnce())
  expect(h.terminal).toHaveBeenCalledOnce()
})
