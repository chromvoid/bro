/** Browser delivery state is separate from the server's current execution state. */
import { isKnownUnadmitted, type Submission } from '@/core/journal'
import type { MessageBody } from './delivery'
import { storedAdmission } from '@/core/admission'

export interface PendingSend {
  key: string
  text: string
  body: MessageBody
  state: Submission['state']
  status: 'sending' | 'delivered' | 'uncertain' | 'rejected'
  failure: Submission['failure']
  diagnostic?: Submission['diagnostic']
  /** Submission time; null for a stored record whose time is unreadable. */
  createdAt: string | null
  messageId: string | null
}

export function admittedId(entry: Submission): string | null {
  return storedAdmission(entry.response, entry.key)?.message_id ?? null
}

export function isAdmitted(entry: Submission): boolean {
  return entry.state === 'accepted' || entry.state === 'resolved'
}

export function pendingSend(entry: Submission, sending = false): PendingSend {
  const body = bodyOf(entry)
  const timestamp = entry.createdAt
  return {
    key: entry.key,
    text: body.text ?? '',
    body,
    state: entry.state,
    status:
      isAdmitted(entry) && admittedId(entry)
        ? 'delivered'
        : isKnownUnadmitted(entry)
          ? 'rejected'
          : sending
            ? 'sending'
            : 'uncertain',
    failure: entry.failure,
    diagnostic: entry.diagnostic,
    createdAt:
      Number.isFinite(timestamp) && Number.isFinite(new Date(timestamp).getTime())
        ? new Date(timestamp).toISOString()
        : null,
    messageId: admittedId(entry),
  }
}

function bodyOf(entry: Submission): MessageBody {
  try {
    const body: unknown = JSON.parse(entry.body)
    if (!body || typeof body !== 'object' || Array.isArray(body)) return {}
    return Object.fromEntries(
      Object.entries(body).filter(
        ([key, value]) =>
          ['text', 'reply_to_message_id', 'transcription_id', 'image_id'].includes(key) &&
          typeof value === 'string',
      ),
    )
  } catch {
    return {}
  }
}
