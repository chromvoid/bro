/**
 * Fragments of background recording read as conversations: the shell cuts speech into pieces of
 * 5–20 seconds, a person thinks in talks. Fragments of one recording session join while the pause
 * between the end of one and the start of the next is under two minutes; another session, or a
 * longer pause, starts a new conversation. Pure grouping of what the server listed.
 */
import type { Recording } from './api'

export const CONVERSATION_GAP_MS = 2 * 60_000

export interface Conversation {
  /** The first fragment's id: it keeps the conversation open while later fragments arrive. */
  key: string
  start: number
  end: number
  /** In the order they were spoken. */
  fragments: Recording[]
  /** Fragments whose text is settled: recognized, or found to hold no speech. */
  done: number
  failed: number
  /** The recognized text so far, from the fragments' previews. */
  preview: string
}

const startOf = (item: Recording) => Date.parse(item.started_at)
const endOf = (item: Recording) => startOf(item) + item.duration_ms

/** Conversations newest first, as the list shows its fragments. */
export function conversations(
  items: Recording[],
  gap: number = CONVERSATION_GAP_MS,
): Conversation[] {
  const ordered = [...items].sort((a, b) => startOf(a) - startOf(b) || a.sequence - b.sequence)
  const open = new Map<string, Recording[]>()
  const groups: Recording[][] = []
  for (const item of ordered) {
    const current = open.get(item.session_id)
    const last = current?.at(-1)
    if (current && last && startOf(item) - endOf(last) < gap) current.push(item)
    else {
      const group = [item]
      groups.push(group)
      open.set(item.session_id, group)
    }
  }
  return groups
    .map((fragments) => ({
      key: fragments[0]!.id,
      start: startOf(fragments[0]!),
      end: Math.max(...fragments.map(endOf)),
      fragments,
      done: fragments.filter((item) => ['ready', 'empty'].includes(item.transcription.status))
        .length,
      failed: fragments.filter((item) => item.transcription.status === 'failed').length,
      preview: fragments
        .filter((item) => item.transcription.status === 'ready')
        .map((item) => item.transcription.preview?.trim() ?? '')
        .filter(Boolean)
        .join(' '),
    }))
    .sort((a, b) => b.start - a.start)
}

/** What a conversation's card says about its text: nothing once every fragment is settled. */
export function conversationStatus(
  conversation: Conversation,
): { key: 'pending' | 'failed'; done: number; failed: number; total: number } | null {
  const total = conversation.fragments.length
  if (conversation.done === total) return null
  const waiting = total - conversation.done - conversation.failed
  return {
    key: waiting ? 'pending' : 'failed',
    done: conversation.done,
    failed: conversation.failed,
    total,
  }
}
