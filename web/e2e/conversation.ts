import type { Page, Route } from '@playwright/test'

/**
 * A scripted conversation server in the browser: the real backend keeps the session, while
 * /v1/conversation/*, the notifications and the change stream are answered here, so no test
 * writes messages and other tests' notifications stay out of this conversation.
 */
export interface FakeMessage {
  id: string
  created_at: string
  state: string | null
  error?: string | null
  text: string
  reply_to_message_id?: string | null
  run_generation?: string | null
  can_clarify?: boolean
  intent_version?: number | null
  intent_revision?: number | null
  clarification_receipt?: Record<string, unknown>
  control_state?: 'accepted' | 'settled' | null
  images?: Array<{ id: string; width: number; height: number; url: string }>
  turns: Array<{
    id: string
    reply_message_id: string
    state: string
    result: Record<string, unknown>
  }>
}

export interface Post {
  key: string
  body: Record<string, unknown>
}

/** `queue`: the next message waits behind a running request, as the server queues it. */
type Mode = 'accept' | 'queue' | 'lose-response' | 'too-many' | 'daily-limit' | 'unavailable'

export async function fakeConversation(page: Page) {
  const messages: FakeMessage[] = []
  const posts: Post[] = []
  const admitted = new Map<
    string,
    {
      submission_key: string
      message_id: string
      admitted_at: string
      state: string
      trace_id: null
    }
  >()
  const uploads: string[] = []
  let readiness = {
    state: 'configured',
    reason: null as string | null,
    action: null as string | null,
    retry_at: null,
    checked_at: new Date().toISOString(),
  }
  await page.route('**/v1/conversation/availability', (route) => json(route, readiness))
  let mode: Mode = 'accept'
  let sequence = 1
  let clock = Date.parse('2026-09-25T09:00:00Z')
  const json = (route: Route, value: unknown, status = 200) =>
    route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(value) })

  await page.route(/\/v1\/conversation\/messages(\?.*)?$/, async (route) => {
    const request = route.request()
    if (request.method() !== 'POST') {
      const query = new URL(request.url()).searchParams
      const selected = query.get('message_id')
      if (selected)
        return json(route, {
          messages: messages.filter(
            (item) =>
              item.id === selected || item.turns.some((turn) => turn.reply_message_id === selected),
          ),
          before: null,
        })
      const position = (id: string) => messages.findIndex((item) => item.id === id)
      const around = query.get('around')
      const after = query.get('after')
      const before = query.get('before')
      // Pages of 50, newest first; around a message: 25 older, the message, 24 newer.
      const [start, end] = around
        ? (() => {
            const index = messages.findIndex(
              (item) =>
                item.id === around || item.turns.some((turn) => turn.reply_message_id === around),
            )
            return [Math.max(0, index - 25), Math.min(messages.length, index + 25)]
          })()
        : after
          ? [position(after) + 1, Math.min(messages.length, position(after) + 51)]
          : (() => {
              const last = before ? position(before) : messages.length
              return [Math.max(0, last - 50), last]
            })()
      const visible = messages.slice(start, end)
      return json(route, {
        messages: visible,
        before: start > 0 && visible.length ? visible[0]!.id : null,
        after: end < messages.length && visible.length ? visible.at(-1)!.id : null,
      })
    }
    const key = (await request.headerValue('idempotency-key')) ?? ''
    const body = request.postDataJSON() as Record<string, unknown>
    posts.push({ key, body })
    if (mode === 'unavailable') {
      mode = 'accept'
      readiness = {
        ...readiness,
        state: 'blocked',
        reason: 'auth_required',
        action: 'open_accounts',
      }
      return json(route, { error: 'chat_unavailable', availability: readiness }, 409)
    }
    if (mode === 'too-many' || mode === 'daily-limit') {
      const body = mode === 'too-many' ? 'Очередь ассистента заполнена' : 'Дневной лимит исчерпан'
      mode = 'accept'
      return route.fulfill({ status: 429, contentType: 'text/plain', body })
    }
    let receipt = admitted.get(key)
    if (!receipt) {
      // A queued message can move into the running request, whose contract names it by UUID.
      const id = mode === 'queue' ? crypto.randomUUID() : `m${messages.length + 1}`
      clock += 60_000
      messages.push({
        id,
        created_at: new Date(clock).toISOString(),
        state: mode === 'queue' ? 'queued' : 'processing',
        run_generation: mode === 'queue' ? crypto.randomUUID() : null,
        text: String(body.text ?? ''),
        reply_to_message_id: (body.reply_to_message_id as string) ?? null,
        images: body.image_id
          ? [
              {
                id: String(body.image_id),
                width: 4,
                height: 3,
                url: '/v1/conversation/images/' + body.image_id,
              },
            ]
          : [],
        turns: [],
      })
      receipt = {
        submission_key: key,
        message_id: id,
        admitted_at: new Date(clock).toISOString(),
        state: 'queued',
        trace_id: null,
      }
      admitted.set(key, receipt)
      if (mode === 'queue') mode = 'accept'
    }
    if (mode === 'lose-response') {
      mode = 'accept'
      // The server admitted the message, but the answer never reaches the client.
      return route.abort('failed')
    }
    return json(route, { ...receipt, trace_id: null }, 202)
  })
  await page.route(/\/v1\/conversation\/submissions\/by-key\/.+/, (route) => {
    const key = decodeURIComponent(new URL(route.request().url()).pathname.split('/').pop()!)
    const receipt = admitted.get(key)
    return receipt ? json(route, receipt) : json(route, { error: 'not_found' }, 404)
  })
  await page.route(/\/v1\/conversation\/images$/, async (route) => {
    uploads.push(route.request().headers()['content-type'] ?? '')
    return json(
      route,
      { id: 'img-1', width: 4, height: 3, url: '/v1/conversation/images/img-1' },
      201,
    )
  })
  await page.route(/\/v1\/conversation\/images\/.+/, (route) =>
    route.fulfill({
      status: 200,
      contentType: 'image/svg+xml',
      body: '<svg xmlns="http://www.w3.org/2000/svg" width="4" height="3"/>',
    }),
  )
  await page.route(/\/v1\/conversation\/turns\/.+/, (route) =>
    json(route, { id: 't', state: 'completed', result: { services: [] } }),
  )
  await page.route(/\/v1\/conversation\/search\?.*/, (route) => {
    const query = new URL(route.request().url()).searchParams.get('query') ?? ''
    const hits = messages
      .flatMap((message) => [
        { id: message.id, author: 'user', occurred_at: message.created_at, text: message.text },
        ...message.turns.map((turn) => ({
          id: turn.reply_message_id,
          author: 'assistant',
          occurred_at: message.created_at,
          text: String(turn.result.reply ?? turn.result.question ?? ''),
        })),
      ])
      .filter((message) => message.text.toLowerCase().includes(query.toLowerCase()))
    return json(route, {
      messages: hits.map((hit) => ({
        id: hit.id,
        author: hit.author,
        occurred_at: hit.occurred_at,
        text: hit.text,
        topic_ids: [],
      })),
      cursor: null,
      timezone: 'UTC',
    })
  })
  await page.route(/\/v1\/notifications(\?.*)?$/, (route) =>
    json(route, { notifications: [], unread: 0, before: null }),
  )
  // Each stream connection delivers one change, which makes the page refetch the history.
  await page.route(/\/v1\/me\/events(\?.*)?$/, (route) =>
    route.fulfill({
      status: 200,
      contentType: 'text/event-stream',
      body: `id: ${sequence++}\nevent: assistant.changed\ndata: {"topic":"assistant.changed"}\n\n`,
    }),
  )

  return {
    availability(state: string, reason: string | null = null, action: string | null = null) {
      readiness = { ...readiness, state, reason, action }
    },
    messages,
    posts,
    uploads,
    next(value: Mode) {
      mode = value
    },
    /** Completes a message with an assistant reply. */
    answer(id: string, result: Record<string, unknown>) {
      const message = messages.find((item) => item.id === id)!
      message.state = result.error ? 'failed' : 'completed'
      message.error = typeof result.error === 'string' ? result.error : null
      message.turns.push({
        id: `t-${id}`,
        reply_message_id: `r-${id}`,
        state: message.state,
        result,
      })
    },
    seed(message: Omit<FakeMessage, 'created_at'> & { created_at?: string }) {
      clock += 60_000
      messages.push({ created_at: new Date(clock).toISOString(), ...message })
    },
  }
}
