import { describe, expect, it } from 'vitest'
import type { Recording } from './api'
import { conversationStatus, conversations } from './conversations'

let next = 0
function fragment(
  start: string,
  seconds: number,
  fields: {
    session?: string
    status?: Recording['transcription']['status']
    preview?: string
  } = {},
): Recording {
  next += 1
  return {
    id: `00000000-0000-4000-8000-${String(next).padStart(12, '0')}`,
    session_id: fields.session ?? 'session-a',
    sequence: next,
    sha256: 'a'.repeat(64),
    stored: true,
    format: 'ogg-opus',
    bytes: 1000,
    duration_ms: seconds * 1000,
    expires_at: null,
    started_at: start,
    received_at: start,
    state: 'stored',
    transcription: {
      status: fields.status ?? 'ready',
      preview: fields.preview ?? null,
      error_code: null,
      updated_at: null,
      retry_may_repeat: false,
    },
  }
}

describe('conversations', () => {
  it('joins a session’s fragments while pauses stay under two minutes', () => {
    const a = fragment('2026-10-06T09:00:00Z', 20, { preview: 'Привет.' })
    const b = fragment('2026-10-06T09:01:30Z', 15, { preview: 'Как дела?' })
    const c = fragment('2026-10-06T09:05:00Z', 10)
    const result = conversations([c, b, a])
    expect(result.map((talk) => talk.fragments.map((item) => item.id))).toEqual([
      [c.id],
      [a.id, b.id],
    ])
    expect(result[1]).toMatchObject({
      key: a.id,
      start: Date.parse('2026-10-06T09:00:00Z'),
      end: Date.parse('2026-10-06T09:01:45Z'),
      preview: 'Привет. Как дела?',
    })
  })

  it('keeps another session apart even when it overlaps in time', () => {
    const a = fragment('2026-10-06T09:00:00Z', 20)
    const other = fragment('2026-10-06T09:00:10Z', 20, { session: 'session-b' })
    const b = fragment('2026-10-06T09:00:30Z', 20)
    const result = conversations([a, other, b])
    expect(result.map((talk) => talk.fragments.length).sort()).toEqual([1, 2])
  })

  it('says how far recognition got only until every fragment settles', () => {
    const [talk] = conversations([
      fragment('2026-10-06T09:00:00Z', 10),
      fragment('2026-10-06T09:00:20Z', 10, { status: 'processing' }),
      fragment('2026-10-06T09:00:40Z', 10, { status: 'failed' }),
    ])
    expect(conversationStatus(talk!)).toEqual({ key: 'pending', done: 1, failed: 1, total: 3 })
    const [failed] = conversations([
      fragment('2026-10-06T10:00:00Z', 10, { status: 'empty' }),
      fragment('2026-10-06T10:00:20Z', 10, { status: 'failed' }),
    ])
    expect(conversationStatus(failed!)).toEqual({ key: 'failed', done: 1, failed: 1, total: 2 })
    const [settled] = conversations([fragment('2026-10-06T11:00:00Z', 10, { status: 'empty' })])
    expect(conversationStatus(settled!)).toBeNull()
  })
})
