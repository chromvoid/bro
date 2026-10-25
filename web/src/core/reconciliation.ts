/** Bounded, read-only recovery of durable submissions for one account lifecycle. */
import { CONVERSATION_MESSAGES, storedAdmission } from './admission'
import { isKnownUnadmitted, type Submission, type SubmissionJournal } from './journal'

type AdmissionHandler = (entry: Submission, signal: AbortSignal) => Promise<boolean>
type Stage = 'receipt' | 'history'
const DELAYS = [1_000, 2_000, 5_000, 10_000, 30_000] as const
const RECOVERABLE = new Set<Submission['state']>(['prepared', 'sending', 'uncertain'])

interface Attempt {
  promise: Promise<void>
  resolve: () => void
  reject: (error: unknown) => void
  automatic: boolean
}

interface Job {
  key: string
  endpoint: string | null
  stage: Stage
  attempts: number
  done: boolean
  handling?: boolean
  removed?: boolean
  timer?: ReturnType<typeof setTimeout>
  attempt?: Attempt
  controller?: AbortController
}

export class SubmissionReconciler {
  private readonly handlers = new Map<string, AdmissionHandler>()
  private readonly jobs = new Map<string, Job>()
  private readonly queue: Array<{ job: Job; attempt: Attempt }> = []
  private readonly scanChanges = new Set<string>()
  private readonly unsubscribe: () => void
  private active = 0
  private closed = false
  private scanning = false

  constructor(private readonly journal: SubmissionJournal) {
    this.unsubscribe = journal.subscribe(({ entry, removed }) => {
      if (this.closed) return
      if (this.scanning) this.scanChanges.add(entry.key)
      if (removed) {
        const job = this.jobs.get(entry.key)
        if (job?.handling) {
          // A successful history handler may acknowledge its own durable receipt.
          // Keep it tracked until completion so close() can still abort the account lifecycle.
          job.removed = true
          this.clearTimer(job)
        } else {
          if (job) this.cancel(job)
          this.jobs.delete(entry.key)
        }
      } else this.observe(entry)
    })
  }

  /** Repeated online/resync signals never replenish an exhausted retry budget. */
  trigger(): void {
    if (this.closed || this.scanning) return
    this.scanning = true
    this.scanChanges.clear()
    void this.journal
      .pending()
      .then((entries) => {
        if (!this.closed)
          for (const entry of entries) {
            if (!this.scanChanges.has(entry.key)) this.observe(entry)
          }
      })
      .catch(() => undefined)
      .finally(() => {
        this.scanning = false
      })
  }

  register(endpoint: string, handler: AdmissionHandler): () => void {
    if (this.closed) return () => undefined
    for (const job of this.jobs.values()) {
      if (job.endpoint === endpoint && job.stage === 'history') {
        this.cancel(job)
        // A newly mounted consumer has not verified this receipt in its own history yet.
        job.done = false
      }
    }
    this.handlers.set(endpoint, handler)
    this.trigger()
    return () => {
      if (this.handlers.get(endpoint) !== handler) return
      this.handlers.delete(endpoint)
      for (const job of this.jobs.values()) {
        if (job.endpoint === endpoint && job.stage === 'history') this.cancel(job)
      }
    }
  }

  /** A deliberate check joins an in-flight attempt or enters the same two-task gate. */
  check(key: string): Promise<void> {
    if (this.closed) return Promise.reject(new DOMException('Reconciler closed', 'AbortError'))
    const job = this.jobs.get(key) ?? this.create(key, null, 'receipt')
    if (job.attempt) return job.attempt.promise
    this.clearTimer(job)
    job.done = false
    return this.enqueue(job, false)
  }

  close(): void {
    if (this.closed) return
    this.closed = true
    this.unsubscribe()
    for (const job of this.jobs.values()) this.cancel(job)
    this.jobs.clear()
    this.handlers.clear()
    this.queue.length = 0
  }

  private create(key: string, endpoint: string | null, stage: Stage): Job {
    const job = { key, endpoint, stage, attempts: 0, done: false }
    this.jobs.set(key, job)
    return job
  }

  private observe(entry: Submission): void {
    const stage =
      RECOVERABLE.has(entry.state) && !isKnownUnadmitted(entry) && entry.lookup !== null
        ? 'receipt'
        : (entry.state === 'accepted' || entry.state === 'resolved') &&
            this.handlers.has(entry.endpoint)
          ? 'history'
          : null
    if (!stage) {
      const job = this.jobs.get(entry.key)
      if (job) {
        this.clearTimer(job)
        job.done = true
      }
      return
    }
    const job = this.jobs.get(entry.key) ?? this.create(entry.key, entry.endpoint, stage)
    job.endpoint = entry.endpoint
    if (job.stage !== stage || stage === 'receipt') {
      job.stage = stage
      job.done = false
    }
    this.schedule(job)
  }

  private schedule(job: Job): void {
    if (this.closed || job.done || job.timer !== undefined || job.attempt) return
    if (job.stage === 'history' && job.endpoint && !this.handlers.has(job.endpoint)) return
    const delay = DELAYS[job.attempts]
    if (delay === undefined) return
    job.timer = setTimeout(() => {
      job.timer = undefined
      void this.enqueue(job, true).catch(() => undefined)
    }, delay)
  }

  private enqueue(job: Job, automatic: boolean): Promise<void> {
    let resolve!: () => void
    let reject!: (error: unknown) => void
    const promise = new Promise<void>((yes, no) => {
      resolve = yes
      reject = no
    })
    // Automatic attempts and a cancelled queued manual attempt may have no remaining caller.
    void promise.catch(() => undefined)
    const attempt = { promise, resolve, reject, automatic }
    job.attempt = attempt
    this.queue.push({ job, attempt })
    this.drain()
    return promise
  }

  private drain(): void {
    while (!this.closed && this.active < 2 && this.queue.length) {
      const { job, attempt } = this.queue.shift()!
      if (job.attempt !== attempt || job.controller || this.jobs.get(job.key) !== job) continue
      const controller = new AbortController()
      job.controller = controller
      if (attempt.automatic) job.attempts += 1
      this.active += 1
      void this.run(job, controller.signal)
        .then((done) => {
          controller.signal.throwIfAborted()
          job.done = done
          attempt.resolve()
        })
        .catch((error: unknown) => attempt.reject(error))
        .finally(() => {
          job.attempt = undefined
          job.controller = undefined
          this.active -= 1
          if (job.removed) this.jobs.delete(job.key)
          if (this.jobs.get(job.key) === job) this.schedule(job)
          this.drain()
        })
    }
  }

  private async run(job: Job, signal: AbortSignal): Promise<boolean> {
    // Journal's per-key lock waits for an original send, then rereads its durable outcome.
    let entry = await this.journal.check(job.key, undefined, signal)
    signal.throwIfAborted()
    job.endpoint = entry.endpoint
    if (isKnownUnadmitted(entry)) return true
    if (RECOVERABLE.has(entry.state)) return entry.lookup === null
    if (entry.state !== 'accepted' && entry.state !== 'resolved') return true
    const handler = this.handlers.get(entry.endpoint)
    if (!handler) return true
    job.stage = 'history'
    if (entry.endpoint === CONVERSATION_MESSAGES && !storedAdmission(entry.response, entry.key)) {
      entry = await this.journal.check(entry.key, () => 'accepted', signal)
      signal.throwIfAborted()
      if (!storedAdmission(entry.response, entry.key)) return false
    }
    job.handling = true
    let done: boolean
    try {
      done = await handler(entry, signal)
    } finally {
      job.handling = false
    }
    signal.throwIfAborted()
    return this.handlers.get(entry.endpoint) === handler && done
  }

  private clearTimer(job: Job): void {
    if (job.timer !== undefined) clearTimeout(job.timer)
    job.timer = undefined
  }

  private cancel(job: Job): void {
    this.clearTimer(job)
    job.controller?.abort()
    job.attempt?.reject(new DOMException('Reconciliation cancelled', 'AbortError'))
    if (!job.controller) job.attempt = undefined
  }
}
