/**
 * The shell side of `PluginHost/v1` for one installation (`/v1/plugins/installations/{id}/…`).
 * Reads go straight to the gateway. Writes are journaled with their exact bytes before sending
 * and are resent only by an explicit `retry`. Configuration is sent directly and never stored.
 * The event cursor lives in memory: a new page starts from the stream head.
 */
import { ref, shallowRef, type Ref } from 'vue'
import { SseParser } from '@/core/events'
import { ApiError, type Api } from '@/core/http'
import type { Submission, SubmissionJournal } from '@/core/journal'
import {
  isRoute,
  type InvocationOutcome,
  type PendingWrite,
  type PluginCapabilities,
  type PluginConfiguration,
  type PluginError,
  type PluginEvent,
  type PluginHost,
  type PluginLifecycle,
  type PluginRoute,
  type WriteState,
} from './api'
import {
  NETWORK,
  PluginEventWire,
  TERMINAL,
  capabilitiesOf,
  dispatchable,
  errorOf,
  localError,
  outcomeOf,
  resultOf,
  type Installation,
} from './catalog'

const KEY = /^[A-Za-z0-9-]{16,80}$/
const CURSOR = /^[A-Za-z0-9._:~-]{1,256}$/
const DENIED = new Set([401, 403, 404])
const RECOVERABLE = new Set(['prepared', 'sending', 'uncertain'])

export interface HostOptions {
  api: Api
  journal: SubmissionJournal
  installation: Installation
  route: Readonly<Ref<PluginRoute>>
  navigate: (route: PluginRoute) => void
  close: () => void
  fetcher?: typeof fetch
  firstBackoffMs?: number
  wait?: (ms: number, signal: AbortSignal) => Promise<void>
}

const rejected = (
  error: PluginError,
  status: number | null = null,
  clientKey: string | null = null,
) => ({ kind: 'rejected', error, status, clientKey }) as const satisfies InvocationOutcome
const unknown = (clientKey: string | null, error: PluginError | null, notFound = false) =>
  ({ kind: 'unknown', clientKey, notFound, error }) as const satisfies InvocationOutcome

export class PlatformPluginHost implements PluginHost {
  readonly locale = 'ru'
  readonly installationId: string
  readonly packageId: string
  readonly capabilities: Ref<PluginCapabilities>
  readonly lifecycle = ref<PluginLifecycle>('background')
  readonly pendingWrites = shallowRef<PendingWrite[]>([])
  readonly route: Readonly<Ref<PluginRoute>>
  readonly configuration: PluginConfiguration
  private readonly base: string
  private readonly invokePath: string
  private readonly lookupPrefix: string
  private readonly notFound = new Set<string>()
  private readonly unsubscribe: () => void
  private cursor: string | null = null

  constructor(private readonly options: HostOptions) {
    const { installation } = options
    this.installationId = installation.installation_id
    this.packageId = installation.package.id
    this.capabilities = ref(capabilitiesOf(installation))
    this.route = options.route
    this.base = `/v1/plugins/installations/${encodeURIComponent(this.installationId)}`
    this.invokePath = `${this.base}/invoke`
    this.lookupPrefix = `${this.base}/operations/by-key/`
    this.configuration = this.configurationPort()
    this.unsubscribe = options.journal.subscribe((change) => {
      if (change.entry.endpoint === this.invokePath)
        void this.refreshWrites().catch(() => undefined)
    })
  }

  update(installation: Installation): void {
    this.capabilities.value = capabilitiesOf(installation)
  }

  visibility(visible: boolean): void {
    if (this.lifecycle.value === 'closed') return
    this.lifecycle.value = visible ? 'foreground' : 'background'
  }

  shutdown(): void {
    this.lifecycle.value = 'closed'
    this.unsubscribe()
  }

  /** Start-up recovery: lookups only, never a send. */
  async restore(): Promise<void> {
    await this.refreshWrites()
    for (const write of this.pendingWrites.value)
      if (write.state !== 'resolved') await this.lookup(write.clientKey)
  }

  async invoke(tool: string, args: Record<string, unknown> = {}): Promise<InvocationOutcome> {
    const current = this.capabilities.value
    const descriptor = current.tools[tool]
    if (!descriptor) return rejected(localError('tool_unavailable', 'Инструмент недоступен'))
    if (this.lifecycle.value === 'closed') return rejected(localError('closed', 'Плагин закрыт'))
    if (!dispatchable(current.status))
      return rejected(localError('dependency_unavailable', current.note ?? 'Плагин недоступен'))
    const body = { tool_id: tool, tool_version: descriptor.version, arguments: args }
    if (descriptor.effect === 'read')
      return this.direct(async () => (await this.options.api.post(this.invokePath, body)).data)
    const key = crypto.randomUUID()
    try {
      const entry = await this.options.journal.submit({
        endpoint: this.invokePath,
        lookupPrefix: this.lookupPrefix,
        body,
        key,
        found: 'accepted',
        context: { tool },
      })
      return this.written(entry, false)
    } catch (error) {
      return this.failedWrite(key, error)
    } finally {
      await this.refreshWrites()
    }
  }

  async lookup(clientKey: string): Promise<InvocationOutcome> {
    if (!KEY.test(clientKey))
      return rejected(localError('invalid_arguments', 'Неверный ключ'), null, clientKey)
    const entry = await this.entry(clientKey)
    if (!entry) return this.untracked(clientKey)
    try {
      return this.written(await this.options.journal.check(clientKey, classify), true)
    } catch (error) {
      return unknown(clientKey, error instanceof ApiError ? errorOf(error) : NETWORK)
    } finally {
      await this.refreshWrites()
    }
  }

  async retry(clientKey: string): Promise<InvocationOutcome> {
    if (!KEY.test(clientKey))
      return rejected(localError('invalid_arguments', 'Неверный ключ'), null, clientKey)
    const entry = await this.entry(clientKey)
    if (!entry) return rejected(localError('not_found', 'Запрос не найден'), null, clientKey)
    if (!RECOVERABLE.has(entry.state)) return this.lookup(clientKey)
    if (this.lifecycle.value === 'closed')
      return rejected(localError('closed', 'Плагин закрыт'), null, clientKey)
    try {
      return this.written(await this.options.journal.retry(clientKey), true)
    } catch (error) {
      return this.failedWrite(clientKey, error)
    } finally {
      await this.refreshWrites()
    }
  }

  async acknowledge(clientKey: string): Promise<void> {
    const entry = await this.entry(clientKey)
    // A write still running on the server is kept: its outcome is not known yet.
    const settled = entry?.state === 'resolved' || entry?.state === 'accepted'
    if (entry && (entry.state === 'rejected' || (settled && finished(entry))))
      await this.options.journal.acknowledge(clientKey)
    this.notFound.delete(clientKey)
    await this.refreshWrites()
  }

  observe(listener: (event: PluginEvent) => void): () => void {
    const controller = new AbortController()
    void this.follow(listener, controller.signal)
    return () => controller.abort()
  }

  navigate(route: PluginRoute): void {
    if (isRoute(route)) this.options.navigate(route)
  }

  close(): void {
    this.options.close()
  }

  private configurationPort(): PluginConfiguration {
    const path = `${this.base}/configuration`
    const writable = () => this.capabilities.value.configurationWritable === true
    const precondition = (revision: number | null | undefined) =>
      revision === null || revision === undefined ? undefined : `"${revision}"`
    return {
      get: () => this.direct(() => this.options.api.get(path), true),
      put: (settings, secrets, expectedRevision, options) => {
        if (!writable())
          return Promise.resolve(rejected(localError('forbidden', 'Недостаточно прав')))
        const body: Record<string, unknown> = { public: settings }
        if (secrets) body.secret = { ...secrets }
        if (options) body.options = options
        return this.direct(
          async () =>
            (
              await this.options.api.send('PUT', path, JSON.stringify(body), {
                key: crypto.randomUUID(),
                ifMatch: precondition(expectedRevision),
              })
            ).data,
        )
      },
      delete: (expectedRevision, purge = true) => {
        if (!writable())
          return Promise.resolve(rejected(localError('forbidden', 'Недостаточно прав')))
        return this.direct(
          async () =>
            (
              await this.options.api.send('DELETE', purge ? path : `${path}?purge=0`, undefined, {
                key: crypto.randomUUID(),
                ifMatch: precondition(expectedRevision),
              })
            ).data,
        )
      },
    }
  }

  /** Unjournaled calls (reads, configuration): a lost read is simply unavailable data. */
  private async direct(call: () => Promise<unknown>, view = false): Promise<InvocationOutcome> {
    try {
      const body = await call()
      return view
        ? { kind: 'completed', data: body, receipts: [], clientKey: null }
        : outcomeOf(resultOf(body), null)
    } catch (error) {
      if (!(error instanceof ApiError)) return unknown(null, NETWORK)
      return error.status === 504
        ? unknown(null, errorOf(error))
        : rejected(errorOf(error), error.status)
    }
  }

  private written(entry: Submission, looked: boolean): InvocationOutcome {
    switch (entry.state) {
      case 'uncertain':
        if (looked) this.notFound.add(entry.key)
        return unknown(entry.key, null, looked)
      case 'accepted':
      case 'resolved':
        this.notFound.delete(entry.key)
        return outcomeOf(resultOf(entry.response), entry.key)
      default:
        return unknown(entry.key, null)
    }
  }

  private async failedWrite(key: string, error: unknown): Promise<InvocationOutcome> {
    const entry = await this.entry(key)
    if (entry?.state === 'rejected' && error instanceof ApiError) {
      // Refused before admission: nothing happened, so nothing stays in the journal.
      await this.options.journal.acknowledge(key).catch(() => undefined)
      return rejected(errorOf(error), error.status, key)
    }
    return unknown(key, error instanceof ApiError ? errorOf(error) : NETWORK)
  }

  /** A key this device did not journal here: lookup only. */
  private async untracked(key: string): Promise<InvocationOutcome> {
    try {
      return outcomeOf(resultOf(await this.options.api.get(this.lookupPrefix + key)), key)
    } catch (error) {
      if (!(error instanceof ApiError)) return unknown(key, NETWORK)
      return unknown(key, errorOf(error), error.status === 404)
    }
  }

  private async entry(key: string): Promise<Submission | undefined> {
    const all = await this.options.journal.pending()
    return all.find((entry) => entry.key === key && entry.endpoint === this.invokePath)
  }

  private async refreshWrites(): Promise<void> {
    const all = await this.options.journal.pending()
    this.pendingWrites.value = all
      .filter((entry) => entry.endpoint === this.invokePath && entry.state !== 'rejected')
      .sort((a, b) => a.createdAt - b.createdAt)
      .map((entry) => {
        const body = parse(entry.body)
        const state = entry.state as WriteState
        return {
          clientKey: entry.key,
          tool: typeof body.tool_id === 'string' ? body.tool_id : '',
          arguments: isRecord(body.arguments) ? body.arguments : {},
          state,
          createdAt: entry.createdAt,
          notFound: this.notFound.has(entry.key),
          admitted: state === 'accepted' || state === 'resolved',
        }
      })
  }

  private async follow(listener: (event: PluginEvent) => void, signal: AbortSignal) {
    const fetcher = this.options.fetcher ?? ((input, init) => fetch(input, init))
    const wait = this.options.wait ?? delay
    const first = this.options.firstBackoffMs ?? 1000
    const seen: string[] = []
    let backoff = first
    while (!signal.aborted && this.lifecycle.value !== 'closed') {
      try {
        const response = await fetcher(`${this.base}/events`, {
          headers: this.cursor ? { 'Last-Event-ID': this.cursor } : {},
          credentials: 'same-origin',
          cache: 'no-store',
          signal,
        })
        if (DENIED.has(response.status)) {
          await response.body?.cancel()
          // The ordinary HTTP path owns session expiry; a 401 here only asks it to confirm.
          if (response.status === 401) await this.options.api.get('/v1/me').catch(() => undefined)
          listener({ kind: 'denied', status: response.status })
          return
        }
        if (!response.ok || !response.body) throw new Error(`HTTP ${response.status}`)
        backoff = first
        await this.read(response.body, listener, seen)
      } catch {
        if (signal.aborted) return
      }
      // The server closes a stream after a while; reconnecting continues from the cursor.
      await wait(backoff, signal).catch(() => undefined)
      backoff = Math.min(backoff * 2, 30_000)
    }
  }

  private async read(
    body: ReadableStream<Uint8Array>,
    listener: (event: PluginEvent) => void,
    seen: string[],
  ): Promise<void> {
    const parser = new SseParser()
    const decoder = new TextDecoder()
    const reader = body.getReader()
    for (;;) {
      const { value, done } = await reader.read()
      if (done) return
      for (const frame of parser.push(decoder.decode(value, { stream: true }))) {
        const event = this.decode(frame.event, frame.data, seen)
        if (event) listener(event)
      }
    }
  }

  private decode(name: string, data: string, seen: string[]): PluginEvent | null {
    const body = parse(data)
    if (name === 'stream.ready' || name === 'resync_required') {
      const next = body.cursor
      if (typeof next !== 'string' || !CURSOR.test(next)) return null
      this.cursor = next
      return { kind: name === 'stream.ready' ? 'ready' : 'resync', cursor: next }
    }
    const parsed = PluginEventWire.safeParse(body)
    if (!parsed.success) return null
    const event = parsed.data
    if (event.installation_id !== this.installationId || event.type !== name) return null
    this.cursor = event.cursor
    if (seen.includes(event.event_id)) return null
    seen.push(event.event_id)
    if (seen.length > 256) seen.shift()
    return {
      kind: 'delivered',
      eventId: event.event_id,
      type: event.type,
      occurredAt: event.occurred_at,
      cursor: event.cursor,
      payload: event.payload,
      operationKey: event.operation_key ?? null,
    }
  }
}

/** A found receipt of a plugin write: finished, or admitted and still running. */
function classify(response: unknown): 'resolved' | 'accepted' {
  return TERMINAL.has(resultOf(response)?.state ?? '') ? 'resolved' : 'accepted'
}

function finished(entry: Submission): boolean {
  return TERMINAL.has(resultOf(entry.response)?.state ?? '')
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function parse(text: string): Record<string, unknown> {
  try {
    const value: unknown = JSON.parse(text)
    return isRecord(value) ? value : {}
  } catch {
    return {}
  }
}

function delay(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(resolve, ms)
    signal.addEventListener(
      'abort',
      () => {
        clearTimeout(timer)
        reject(signal.reason)
      },
      { once: true },
    )
  })
}
