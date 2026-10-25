/**
 * The conversation history as the server sends it: user messages, each with the turns that
 * answer it. Only known fields are read; anything else in a result is ignored, never executed.
 */
import { z } from '@/core/zod'
import {
  ClarificationReceipt,
  historyClarification,
  type ClarificationItem,
} from './clarifications'

const Image = z.object({
  id: z.string(),
  width: z.number(),
  height: z.number(),
  url: z.string(),
  /** A small WebP for the conversation list; a server without previews sends none. */
  preview_url: z.string().nullish(),
})

const Source = z.looseObject({
  url: z.string(),
  title: z.string().nullish(),
  text: z.string().nullish(),
  relation: z.string().nullish(),
  published_at: z.string().nullish(),
  // codex_web: cited by Codex.
  provenance: z.string().nullish(),
})
export type Source = z.infer<typeof Source>
/**
 * The links a research answer cites. Another plugin's result can use the same name for something
 * else (the personal section's data sources, say): entries that are not links are skipped, so
 * such a result never makes the whole conversation unreadable.
 */
const Citations = z
  .array(z.unknown())
  .transform((entries) =>
    entries.flatMap((entry) => {
      const source = Source.safeParse(entry)
      return source.success ? [source.data] : []
    }),
  )
  .nullish()
  .catch(null)

const Revision = z.union([z.number(), z.string()]).nullable()
const Entity = z.looseObject({
  id: z.string(),
  kind: z.string(),
  revision: Revision,
  label: z.string().nullish(),
  status: z.string().nullish(),
  date: z.string().nullish(),
})
const Effect = z.looseObject({
  operation_ref: z.string(),
  kind: z.string(),
  entities: z.array(Entity),
  undoes: z.string().nullish(),
})
const Receipt = z.looseObject({
  operation_id: z.string().nullish(),
  kind: z.string().nullish(),
})
export type Effect = z.infer<typeof Effect>
export type Receipt = z.infer<typeof Receipt>
const ProposalStatus = z.enum(['open', 'resolved', 'cancelled', 'superseded', 'expired'])

const Service = z.looseObject({
  service: z.string().nullish(),
  status: z.string().nullish(),
  receipt_text: z.string().nullish(),
  message: z.string().nullish(),
  error: z.string().nullish(),
  question: z.string().nullish(),
  confirmation_pending: z.boolean().nullish(),
  proposal_id: z.string().nullish(),
  proposal_status: ProposalStatus.nullish(),
  operation_key: z.string().nullish(),
  receipts: z.array(Receipt).nullish(),
  effects: z.array(Effect).nullish(),
  sources: Citations,
  research_status: z.string().nullish(),
  limitations: z.array(z.string()).nullish(),
  account_login_ids: z.array(z.string()).nullish(),
})

export const Execution = z.object({
  effects: z.enum(['none', 'confirmed', 'unknown']),
  completion: z.enum(['completed', 'failed', 'partial']),
  safe_to_resubmit: z.boolean(),
})

const Result = z.looseObject({
  execution: Execution.nullish(),
  reply: z.string().nullish(),
  question: z.string().nullish(),
  confirmation_pending: z.boolean().nullish(),
  proposal_id: z.string().nullish(),
  proposal_status: ProposalStatus.nullish(),
  receipts: z.array(Receipt).nullish(),
  services: z.array(Service).nullish(),
  error: z.string().nullish(),
  account_login_ids: z.array(z.string()).nullish(),
})

const Turn = z.looseObject({
  id: z.string(),
  reply_message_id: z.string().nullish(),
  plugin: z.string().nullish(),
  state: z.string(),
  result: Result.nullish(),
})

export const Message = z.looseObject({
  id: z.string(),
  created_at: z.string(),
  state: z.string().nullish(),
  error: z.string().nullish(),
  reply_to_message_id: z.string().nullish(),
  transcription_id: z.string().nullish(),
  run_generation: z.string().nullish(),
  intent_version: z.number().int().nullish(),
  intent_revision: z.number().int().nonnegative().nullish(),
  can_clarify: z.boolean().nullish().catch(false),
  clarification_receipt: ClarificationReceipt.nullish().catch(null),
  stop_requested: z.boolean().nullish(),
  control_state: z.enum(['accepted', 'settled']).nullish(),
  control_action: z.enum(['stop', 'withdraw']).nullish(),
  control_execution: Execution.nullish(),
  text: z.string().nullish(),
  images: z.array(Image).nullish(),
  turns: z.array(Turn).nullish(),
})
export type Message = z.infer<typeof Message>

export const History = z.object({
  messages: z.array(Message),
  before: z.string().nullable(),
  /** Newer messages exist past this page (a window away from the newest one). */
  after: z.string().nullish(),
})
export type History = z.infer<typeof History>

export const SearchResults = z.object({
  messages: z.array(
    z.object({
      id: z.string(),
      author: z.enum(['user', 'assistant']),
      occurred_at: z.string(),
      text: z.string(),
    }),
  ),
  cursor: z.string().nullable(),
})

export { Admission } from '@/core/admission'
export const UploadedImage = Image

export type ExchangeStatus = 'pending' | 'done' | 'failed' | 'clarification'

export interface RecordedResult {
  service: string | null
  status: string | null
  text: string | null
  operationKey: string | null
  receipts: Receipt[]
  effects: Effect[]
}

export interface Reply {
  execution: z.infer<typeof Execution> | null

  turnId: string
  completed: boolean
  /** The assistant's message id: replies and confirmations point at it. */
  messageId: string | null
  text: string
  question: string | null
  /** What was actually recorded, as the services describe it. */
  receipts: string[]
  /** Receipt facts and their object identities survive the presentation projection. */
  recorded: RecordedResult[]
  committed: boolean
  /** Parts a service refused, with its reason. */
  refusals: string[]
  confirmationPending: boolean
  proposalStatus: z.infer<typeof ProposalStatus> | null
  /** The outcome is unknown; checking it runs recovery on the server, never the request again. */
  unknownOutcome: boolean
  interrupted: boolean
  responseFailed: boolean
  sources: Source[]
  researchStatus: string | null
  limitations: string[]
  accountLogins: string[]
}

export interface ExchangeImage {
  id: string
  width: number
  height: number
  /** The stored photo. */
  url: string
  /** What the conversation shows; without it, the stored photo. */
  previewUrl?: string | null
}

export interface Exchange {
  id: string
  createdAt: string
  text: string
  images: ExchangeImage[]
  replyTo: string | null
  status: ExchangeStatus
  queued: boolean
  generation?: string | null
  intentVersion?: number | null
  intentRevision?: number | null
  canClarify?: boolean
  clarification?: ClarificationItem | null
  transcriptionId?: string | null
  controlState?: 'accepted' | 'settled' | null
  controlAction?: 'stop' | 'withdraw' | null
  controlExecution?: z.infer<typeof Execution> | null
  error: string | null
  failureKind: 'response' | 'unknown' | 'request' | null
  canRestore: boolean
  replies: Reply[]
}

const PENDING = new Set(['queued', 'routing', 'processing'])
// A quick lookup that found nothing is often followed by an investigation in the same turn.
const RESEARCH_RANK: Record<string, number> = {
  ok: 4,
  conflicting_evidence: 3,
  insufficient_evidence: 2,
  not_found: 1,
}

/** The research result a reply rests on: the last success, otherwise the most informative attempt. */
function researchOf(services: z.infer<typeof Service>[]) {
  let best: z.infer<typeof Service> | undefined
  let rank = -1
  for (const service of services) {
    if (!service.research_status) continue
    const value = RESEARCH_RANK[service.research_status] ?? 0
    if (value >= rank) [best, rank] = [service, value]
  }
  return best
}

function reply(turn: z.infer<typeof Turn>): Reply {
  const result = turn.result ?? {}
  const services = result.services ?? []
  const sources = services.flatMap((service) => service.sources ?? [])
  const research = researchOf(services)
  const recorded = services
    .filter(
      (service) => service.receipt_text || service.receipts?.length || service.effects?.length,
    )
    .map((service) => ({
      service: service.service ?? null,
      status: service.status ?? null,
      text: service.receipt_text ?? null,
      operationKey: service.operation_key ?? null,
      receipts: service.receipts ?? [],
      effects: service.effects ?? [],
    }))
  // Old turns can carry receipts directly, without per-service results.
  if (!recorded.length && result.receipts?.length)
    recorded.push({
      service: turn.plugin ?? null,
      status: null,
      text: null,
      operationKey: null,
      receipts: result.receipts,
      effects: [],
    })
  const proposalStatus =
    result.proposal_status ??
    services.find((service) => service.confirmation_pending && service.proposal_status === 'open')
      ?.proposal_status ??
    services.find((service) => service.proposal_status)?.proposal_status ??
    null
  const proposalClosed = proposalStatus !== null && proposalStatus !== 'open'
  const responseFailed = !!result.error || turn.state === 'failed'
  return {
    execution: result.execution ?? null,
    turnId: turn.id,
    completed: turn.state === 'completed',
    messageId: turn.reply_message_id ?? null,
    text: (result.reply ?? '').trim(),
    question:
      responseFailed || (proposalClosed && !!result.confirmation_pending)
        ? null
        : result.question?.trim() || null,
    receipts: services.flatMap((service) => (service.receipt_text ? [service.receipt_text] : [])),
    recorded,
    committed: recorded.length > 0 || result.execution?.effects === 'confirmed',
    // Tool exceptions describe individual attempts, including attempts the agent later corrects.
    // Their raw diagnostic text stays in TurnDetails; only domain refusals are user-facing here.
    refusals: services.flatMap((service) =>
      service.status === 'rejected' && !service.error && service.message ? [service.message] : [],
    ),
    confirmationPending: !!result.confirmation_pending && !proposalClosed,
    proposalStatus,
    unknownOutcome:
      result.execution?.effects === 'unknown' ||
      result.error === 'outcome_pending' ||
      services.some((service) => service.status === 'unknown'),
    interrupted: result.error === 'harness_interrupted',
    responseFailed,
    sources: [...new Map(sources.map((source) => [source.url, source])).values()],
    researchStatus: research?.research_status ?? null,
    limitations: research?.limitations ?? [],
    accountLogins: [
      ...(result.account_login_ids ?? []),
      ...services.flatMap((service) => service.account_login_ids ?? []),
    ],
  }
}

export function toExchange(message: Message): Exchange {
  const state = message.state ?? 'completed'
  const replies = (message.turns ?? []).map(reply)
  const failed = state === 'failed' || replies.some((item) => item.responseFailed)
  const committed = replies.some((item) => item.committed)
  const unknown = message.error === 'outcome_pending' || replies.some((item) => item.unknownOutcome)
  return {
    id: message.id,
    createdAt: message.created_at,
    text: message.text ?? '',
    images: (message.images ?? []).map((image) => ({
      id: image.id,
      width: image.width,
      height: image.height,
      url: image.url,
      previewUrl: image.preview_url ?? null,
    })),
    replyTo: message.reply_to_message_id ?? null,
    queued: state === 'queued',
    generation: message.run_generation ?? null,
    intentVersion: message.intent_version ?? null,
    intentRevision: message.intent_revision ?? null,
    canClarify: message.can_clarify === true,
    clarification: historyClarification({
      id: message.id,
      text: message.text ?? '',
      clarificationReceipt: message.clarification_receipt,
    }),
    transcriptionId: message.transcription_id ?? null,
    controlState: message.control_state ?? null,
    controlAction: message.control_action ?? null,
    controlExecution: message.control_execution ?? null,
    // A job in state `clarification` is a queued message moved into a running request: it is
    // done as a message of its own, and its answer comes in the request it clarifies.
    status: PENDING.has(state)
      ? 'pending'
      : failed
        ? 'failed'
        : replies.some((item) => item.question)
          ? 'clarification'
          : 'done',
    error:
      message.error ?? message.turns?.find((turn) => turn.result?.error)?.result?.error ?? null,
    failureKind: failed ? (unknown ? 'unknown' : committed ? 'response' : 'request') : null,
    canRestore:
      (message.control_state === 'settled' &&
        message.control_execution?.effects === 'none' &&
        message.control_execution.safe_to_resubmit &&
        !committed &&
        !unknown) ||
      (failed &&
        !committed &&
        !unknown &&
        replies.length > 0 &&
        replies.every(
          (item) => item.execution?.effects === 'none' && item.execution.safe_to_resubmit,
        )),
    replies,
  }
}
