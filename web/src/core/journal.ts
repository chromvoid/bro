/**
 * A write-ahead journal for writes whose response can be lost, deliberately without a send
 * queue: the exact request is stored before the first network call, an unknown outcome is
 * resolved by lookup, and only an explicit user action sends the same key and bytes again.
 */
import {
  ApiError,
  NetworkError,
  writeAttempt,
  type Api,
  type RequestDiagnostic,
  type WriteAttempt,
} from './http'
import { admission, CONVERSATION_MESSAGES } from './admission'

export type SubmissionState =
  'prepared' | 'sending' | 'accepted' | 'resolved' | 'uncertain' | 'rejected'

export interface Submission {
  key: string
  method: 'POST' | 'PUT' | 'DELETE'
  endpoint: string
  /** Full lookup path by key; null where the server offers none (resend is the only recovery). */
  lookup: string | null
  /** What a found lookup means: `accepted` where the endpoint only confirms admission. */
  found: 'resolved' | 'accepted'
  /** The request body exactly as first sent; never re-serialized. */
  body: string
  createdAt: number
  state: SubmissionState
  response: unknown
  failure: { status: number; code: string | null; detail: unknown } | null
  /** Original delivery failure, retained across read-only recovery attempts. Older entries lack it. */
  diagnostic?: RequestDiagnostic | null
  /** Sticky across retries: a later preparation failure cannot settle an earlier lost write. */
  mayHaveBeenAdmitted?: boolean
  attempt?: { number: number; disposition: WriteAttempt | 'admitted' }
  /** Caller context, e.g. the draft this write came from. */
  context: Record<string, string>
}

/** A new prepared record is persisted before even the sending marker; no fetch can precede it. */
export function isKnownUnadmitted(entry: Submission): boolean {
  return (
    entry.state === 'rejected' ||
    (entry.state === 'prepared' && entry.mayHaveBeenAdmitted === false)
  )
}

/** The request already has a durable journal owner even if recording its outcome now fails. */
export class UnpersistedOutcome extends Error {
  constructor(
    readonly submission: Submission,
    readonly reason: unknown,
  ) {
    super('Could not persist the delivery outcome')
  }
}

/** A state change; [local] is false for changes relayed from another tab. */
export interface JournalChange {
  entry: Submission
  removed: boolean
  local: boolean
}

export interface JournalStore {
  submission(key: string): Promise<Submission | undefined>
  saveSubmission(entry: Submission): Promise<void>
  deleteSubmission(key: string): Promise<void>
  submissions(): Promise<Submission[]>
}

/** Runs one task per key at a time, across tabs where the browser supports Web Locks. */
export interface KeyLock {
  run<T>(key: string, task: () => Promise<T>): Promise<T>
}

/** What a found receipt means for a write whose endpoint reports progress after admission. */
export type Classify = (response: unknown) => 'resolved' | 'accepted'

export interface SubmitRequest {
  endpoint: string
  /** Lookup path without the key, e.g. /v1/tasks/operations/by-key/ */
  lookupPrefix: string | null
  body: unknown
  method?: Submission['method']
  found?: Submission['found']
  key?: string
  context?: Record<string, string>
  /** In-memory account/lifecycle guard. It is never persisted as request data. */
  beforeSend?: () => void
}

// Refusals that happened before any write; the entry cannot have taken effect.
const REFUSED = new Set([400, 401, 403, 404, 413, 415, 422, 429])
const RECOVERABLE: ReadonlySet<SubmissionState> = new Set(['prepared', 'sending', 'uncertain'])
const SETTLED: ReadonlySet<SubmissionState> = new Set(['accepted', 'resolved', 'rejected'])

export class SubmissionJournal {
  private readonly listeners = new Set<(change: JournalChange) => void>()

  constructor(
    private readonly store: JournalStore,
    private readonly api: Api,
    private readonly lock: KeyLock,
    private readonly now: () => number = Date.now,
  ) {}

  /** Called for every state change made here or, via [relay], in another tab. */
  subscribe(listener: (change: JournalChange) => void): () => void {
    this.listeners.add(listener)
    return () => this.listeners.delete(listener)
  }

  /** Delivers a change another tab made; it is not relayed again. */
  relay(change: Omit<JournalChange, 'local'>): void {
    this.emit({ ...change, local: false })
  }

  private emit(change: JournalChange): void {
    for (const listener of this.listeners) listener(change)
  }

  submit(request: SubmitRequest): Promise<Submission> {
    const key = request.key ?? crypto.randomUUID()
    const entry: Submission = {
      key,
      method: request.method ?? 'POST',
      endpoint: request.endpoint,
      lookup: request.lookupPrefix === null ? null : request.lookupPrefix + encodeURIComponent(key),
      found: request.found ?? 'resolved',
      body: JSON.stringify(request.body),
      createdAt: this.now(),
      state: 'prepared',
      response: null,
      failure: null,
      mayHaveBeenAdmitted: false,
      context: request.context ?? {},
    }
    return this.lock.run(key, async () => {
      const existing = await this.store.submission(key)
      if (existing) throw new Error('Submission key already exists; use retry')
      await this.save(entry)
      return this.transmit(entry, request.beforeSend)
    })
  }

  pending(): Promise<Submission[]> {
    return this.store.submissions()
  }

  /**
   * Lookup only: never sends the write. A missing receipt leaves the entry uncertain. With
   * [classify], an admitted write that was still running is looked up again and the receipt
   * decides whether it has finished (plugin operations run after admission).
   */
  check(key: string, classify?: Classify, signal?: AbortSignal): Promise<Submission> {
    return this.lock.run(key, async () => {
      signal?.throwIfAborted()
      const entry = await this.owned(key)
      signal?.throwIfAborted()
      if (isKnownUnadmitted(entry)) return entry
      if (entry.lookup === null) return entry
      if (entry.state === 'resolved' && classify) {
        try {
          if (classify(entry.response) === 'resolved') return entry
        } catch {
          // HTTP success alone cannot validate a malformed or foreign receipt.
        }
        return this.resolve(entry, classify, signal)
      }
      if (RECOVERABLE.has(entry.state) || (entry.state === 'accepted' && classify))
        return this.resolve(entry, classify, signal)
      return entry
    })
  }

  /** After a restart: a write interrupted mid-flight is uncertain, then checked by lookup. */
  async recover(): Promise<Submission[]> {
    const result: Submission[] = []
    for (const { key } of await this.store.submissions()) {
      const recovered = await this.lock.run(key, async () => {
        // Another tab can acknowledge an entry after this recovery took its snapshot.
        const entry = await this.store.submission(key)
        if (!entry || !RECOVERABLE.has(entry.state) || isKnownUnadmitted(entry)) return entry
        const uncertain = await this.save({ ...entry, state: 'uncertain' })
        if (uncertain.lookup === null) return uncertain
        try {
          return await this.resolve(uncertain)
        } catch {
          return uncertain
        }
      })
      if (recovered) result.push(recovered)
    }
    return result
  }

  /** Only a deliberate user action calls this: look up first, then resend the same request. */
  retry(key: string, beforeSend?: () => void, expectedUser?: string): Promise<Submission> {
    return this.lock.run(key, async () => {
      beforeSend?.()
      let entry = await this.owned(key)
      beforeSend?.()
      if (!RECOVERABLE.has(entry.state))
        throw new Error(`Cannot resend: the write is ${entry.state}`)
      if (expectedUser && !entry.context.expectedUser)
        entry = await this.save({ ...entry, context: { ...entry.context, expectedUser } })
      if (entry.lookup !== null) {
        const checked = await this.resolve(entry)
        beforeSend?.()
        if (checked.state !== 'uncertain') return checked
      }
      return this.transmit(entry, beforeSend)
    })
  }

  /** The user chose not to learn the outcome of an unresolved write; it leaves the journal. */
  abandon(key: string): Promise<void> {
    return this.lock.run(key, async () => {
      const entry = await this.owned(key)
      if (entry.state !== 'uncertain')
        throw new Error(`Cannot abandon: the write is ${entry.state}`)
      await this.store.deleteSubmission(key)
      this.emit({ entry, removed: true, local: true })
    })
  }

  /** The user has seen the outcome; the entry leaves the journal. */
  acknowledge(key: string): Promise<void> {
    return this.lock.run(key, async () => {
      const entry = await this.store.submission(key)
      // The same proven receipt may be acknowledged by recovery and its original sender.
      if (!entry) return
      if (!SETTLED.has(entry.state) && !isKnownUnadmitted(entry))
        throw new Error(`Cannot dismiss: the write is ${entry.state}`)
      await this.store.deleteSubmission(key)
      this.emit({ entry, removed: true, local: true })
    })
  }

  private async owned(key: string): Promise<Submission> {
    const entry = await this.store.submission(key)
    if (!entry) throw new Error('Unknown submission')
    return entry
  }

  private async transmit(entry: Submission, beforeSend?: () => void): Promise<Submission> {
    const priorUnknown = entry.mayHaveBeenAdmitted ?? RECOVERABLE.has(entry.state)
    const sending = await this.save({
      ...entry,
      state: 'sending',
      failure: null,
      diagnostic: null,
      // If the page dies during an attempt, no later client may assume it never left.
      mayHaveBeenAdmitted: true,
      attempt: { number: (entry.attempt?.number ?? 0) + 1, disposition: 'unknown' },
    })
    let admitted: Submission | undefined
    try {
      const reply = await this.api.send(sending.method, sending.endpoint, sending.body, {
        key: sending.key,
        beforeSend,
        expectedUser: sending.context.expectedUser,
      })
      admitted = {
        ...sending,
        state: reply.status === 202 ? 'accepted' : 'resolved',
        response: this.receipt(sending, reply.data),
        attempt: { number: sending.attempt!.number, disposition: 'admitted' },
      }
      return await this.save(admitted)
    } catch (error) {
      if (admitted) throw new UnpersistedOutcome(admitted, error)
      const outcome = await this.failed(sending, error, priorUnknown)
      try {
        await this.save(outcome)
      } catch {
        throw new UnpersistedOutcome(outcome, error)
      }
      if (outcome.state === 'resolved' || outcome.state === 'accepted') return outcome
      throw error
    }
  }

  private async failed(
    entry: Submission,
    error: unknown,
    priorUnknown: boolean,
  ): Promise<Submission> {
    const disposition = writeAttempt(error)
    entry = { ...entry, attempt: { number: entry.attempt!.number, disposition } }
    if (error instanceof ApiError || error instanceof NetworkError)
      entry = { ...entry, diagnostic: error.diagnostic ?? null }
    if (disposition !== 'unknown')
      return {
        ...entry,
        state: priorUnknown ? 'uncertain' : 'rejected',
        mayHaveBeenAdmitted: priorUnknown,
        failure:
          error instanceof ApiError
            ? { status: error.status, code: error.code, detail: error.detail }
            : null,
      }
    if (!(error instanceof ApiError)) return { ...entry, state: 'uncertain' }
    const failure = { status: error.status, code: error.code, detail: error.detail }
    // Chat readiness is checked before admission. Its typed refusal does not need a second
    // network request to establish that the original composer envelope still belongs here.
    if ((error.status === 409 || error.status === 503) && error.code === 'chat_unavailable')
      return this.refused(entry, failure, priorUnknown)
    // A keyed 409 may mean this key was accepted earlier; the receipt decides.
    if (error.status === 409 && entry.lookup !== null) {
      try {
        const response = await this.api.get(entry.lookup)
        return {
          ...entry,
          state: entry.found,
          response: this.receipt(entry, response),
          mayHaveBeenAdmitted: true,
        }
      } catch (lookup) {
        const missing = lookup instanceof ApiError && lookup.status === 404
        return missing
          ? this.refused(entry, failure, priorUnknown)
          : { ...entry, state: 'uncertain', failure }
      }
    }
    const refused = error.status === 409 || REFUSED.has(error.status)
    return refused
      ? this.refused(entry, failure, priorUnknown)
      : { ...entry, state: 'uncertain', failure }
  }

  private refused(
    entry: Submission,
    failure: Submission['failure'],
    priorUnknown: boolean,
  ): Submission {
    return {
      ...entry,
      state: priorUnknown ? 'uncertain' : 'rejected',
      failure,
      mayHaveBeenAdmitted: priorUnknown,
      attempt: { number: entry.attempt!.number, disposition: 'refused' },
    }
  }

  private receipt(entry: Submission, response: unknown): unknown {
    return entry.endpoint === CONVERSATION_MESSAGES ? admission(response, entry.key) : response
  }

  private async resolve(
    entry: Submission,
    classify?: Classify,
    signal?: AbortSignal,
  ): Promise<Submission> {
    try {
      signal?.throwIfAborted()
      const response = this.receipt(entry, await this.api.get(entry.lookup!, signal))
      signal?.throwIfAborted()
      const state = classify ? classify(response) : entry.found
      return await this.save({
        ...entry,
        state,
        response,
        failure: null,
        mayHaveBeenAdmitted: true,
      })
    } catch (error) {
      signal?.throwIfAborted()
      // A later missing lookup cannot revoke a durable admission or make it safe to resend.
      // Only a write without a receipt can still be "not found yet".
      if (error instanceof ApiError && error.status === 404) {
        return this.save({
          ...entry,
          state: entry.state === 'accepted' ? 'accepted' : 'uncertain',
          failure: { status: error.status, code: error.code, detail: error.detail },
        })
      }
      throw error
    }
  }

  private async save(entry: Submission): Promise<Submission> {
    await this.store.saveSubmission(entry)
    this.emit({ entry, removed: false, local: true })
    return entry
  }
}

/** Serializes per key inside this page only (tests, browsers without Web Locks). */
export class LocalKeyLock implements KeyLock {
  private readonly tails = new Map<string, Promise<unknown>>()

  run<T>(key: string, task: () => Promise<T>): Promise<T> {
    const previous = this.tails.get(key) ?? Promise.resolve()
    const next = previous.then(task, task)
    const tail = next.catch(() => undefined)
    this.tails.set(key, tail)
    void tail.then(() => {
      if (this.tails.get(key) === tail) this.tails.delete(key)
    })
    return next
  }
}

/** Serializes per key across every tab of this origin. */
export class BrowserKeyLock implements KeyLock {
  constructor(private readonly namespace: string) {}

  run<T>(key: string, task: () => Promise<T>): Promise<T> {
    return navigator.locks.request(`${this.namespace}:${key}`, task)
  }
}

export function keyLock(namespace: string): KeyLock {
  return typeof navigator !== 'undefined' && typeof navigator.locks?.request === 'function'
    ? new BrowserKeyLock(namespace)
    : new LocalKeyLock()
}
