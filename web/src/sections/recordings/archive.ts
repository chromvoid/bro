/** Personal audio and text live only in this screen's memory, never a shared or durable cache. */
import { computed, ref, shallowRef } from 'vue'
import { ApiError, writeAttempt } from '@/core/http'
import type { DateRange, Recording, RecordingsClient, Transcript, Usage } from './api'

const pending = (status: string) => status === 'pending' || status === 'processing'
export class RecordingsArchive {
  readonly items = ref<Recording[]>([])
  readonly usage = ref<Usage | null>(null)
  readonly next = ref<string | null>(null)
  readonly loaded = ref(false)
  readonly loading = ref(false)
  readonly loadingMore = ref(false)
  readonly error = shallowRef<unknown>(null)
  readonly usageError = shallowRef<unknown>(null)
  readonly selected = ref<string | null>(null)
  readonly transcript = ref<Transcript | null>(null)
  readonly detailLoading = ref(false)
  readonly detailError = shallowRef<unknown>(null)
  readonly busy = ref(false)
  readonly mutationError = shallowRef<unknown>(null)
  readonly mutationUnknown = ref(false)
  readonly pollError = shallowRef<unknown>(null)
  readonly hasPending = computed(() =>
    this.items.value.some((item) => pending(item.transcription.status)),
  )
  private owner: string | null = null
  private range: DateRange = {}
  private generation = 0
  private accountController = new AbortController()
  private listController?: AbortController
  private detailController?: AbortController
  private pollController?: AbortController
  private pollOffset = 0
  constructor(private readonly client: RecordingsClient) {}

  account(user: string | null, range: DateRange = {}): void {
    this.generation++
    this.accountController.abort()
    this.accountController = new AbortController()
    this.pollController = undefined
    this.owner = user
    this.range = range
    this.items.value = []
    this.usage.value = null
    this.next.value = null
    this.loaded.value = false
    this.loading.value = false
    this.loadingMore.value = false
    this.error.value = null
    this.usageError.value = null
    this.pollError.value = null
    this.selected.value = null
    this.transcript.value = null
    this.detailError.value = null
    this.detailLoading.value = false
    this.busy.value = false
    this.mutationError.value = null
    this.mutationUnknown.value = false
    this.pollOffset = 0
  }
  private current(generation: number): boolean {
    return !!this.owner && generation === this.generation
  }
  private assert(generation: number): void {
    if (!this.current(generation)) throw new DOMException('Account changed', 'AbortError')
  }
  private denied(error: unknown): boolean {
    if (!(error instanceof ApiError && [401, 403].includes(error.status))) return false
    this.generation++
    this.accountController.abort()
    this.accountController = new AbortController()
    this.items.value = []
    this.usage.value = null
    this.next.value = null
    this.selected.value = null
    this.transcript.value = null
    this.loaded.value = false
    this.loading.value = false
    this.loadingMore.value = false
    this.detailLoading.value = false
    this.busy.value = false
    this.error.value = error
    this.cancelPolling()
    return true
  }
  private controller(): AbortController {
    return new AbortController()
  }
  private signal(controller: AbortController): AbortSignal {
    return AbortSignal.any([this.accountController.signal, controller.signal])
  }
  async refresh(more = false): Promise<void> {
    if (!this.owner || (more && !this.next.value)) return
    const generation = this.generation
    this.listController?.abort()
    const controller = this.controller()
    this.listController = controller
    const signal = this.signal(controller)
    if (more) this.loadingMore.value = true
    else this.loading.value = true
    this.error.value = null
    const cursor = more ? this.next.value : null
    const [page, usage] = await Promise.allSettled([
      this.client.list(this.range, cursor, signal),
      this.client.usage(signal),
    ])
    if (!this.current(generation) || signal.aborted) return
    const accessFailure = [page, usage].find(
      (result) =>
        result.status === 'rejected' &&
        result.reason instanceof ApiError &&
        [401, 403].includes(result.reason.status),
    )
    if (accessFailure?.status === 'rejected') {
      this.denied(accessFailure.reason)
      this.loading.value = false
      this.loadingMore.value = false
      return
    }
    if (page.status === 'fulfilled') {
      const entries = more ? [...this.items.value, ...page.value.recordings] : page.value.recordings
      this.items.value = [...new Map(entries.map((item) => [item.id, item])).values()]
      this.next.value = page.value.next_cursor
      this.loaded.value = true
      if (!more) this.mutationUnknown.value = false
      if (this.selected.value && !this.items.value.some((item) => item.id === this.selected.value))
        this.select(null)
      else if (this.selected.value && !more) void this.select(this.selected.value)
    } else {
      this.error.value = page.reason
      this.denied(page.reason)
    }
    if (usage.status === 'fulfilled') {
      this.usage.value = usage.value
      this.usageError.value = null
    } else this.usageError.value = usage.reason
    this.loading.value = false
    this.loadingMore.value = false
  }
  async select(id: string | null): Promise<void> {
    if (this.busy.value && id !== this.selected.value && id !== null) return
    this.detailController?.abort()
    this.selected.value = id
    this.transcript.value = null
    this.detailError.value = null
    if (!this.mutationUnknown.value && !this.busy.value) this.mutationError.value = null
    this.detailLoading.value = false
    if (!id || !this.owner) return
    const generation = this.generation
    const controller = this.controller()
    this.detailController = controller
    const signal = this.signal(controller)
    this.detailLoading.value = true
    try {
      this.merge(await this.client.transcription(id, signal), generation, signal)
    } catch (error) {
      if (this.current(generation) && !signal.aborted && !this.denied(error))
        this.detailError.value = error
    } finally {
      if (this.current(generation) && !signal.aborted) this.detailLoading.value = false
    }
  }
  private merge(value: Transcript, generation: number, signal: AbortSignal): void {
    if (!this.current(generation) || signal.aborted) return
    const item = this.items.value.find((entry) => entry.id === value.id)
    if (!item) return
    const oldTime = item.transcription.updated_at
      ? Date.parse(item.transcription.updated_at)
      : -Infinity
    const newTime = value.updated_at ? Date.parse(value.updated_at) : -Infinity
    if (newTime < oldTime) return
    item.transcription = {
      status: value.status,
      preview: value.text?.slice(0, 200) ?? null,
      error_code: value.error_code,
      updated_at: value.updated_at,
      retry_may_repeat: value.retry_may_repeat,
    }
    if (this.selected.value === value.id) {
      this.transcript.value = value
      this.detailError.value = null
    }
  }
  async poll(): Promise<void> {
    if (!this.owner || this.pollController || !this.hasPending.value) return
    const generation = this.generation
    const controller = this.controller()
    this.pollController = controller
    const signal = this.signal(controller)
    const waiting = this.items.value.filter((item) => pending(item.transcription.status))
    const priority = waiting.find((item) => item.id === this.selected.value)
    const others = waiting.filter((item) => item !== priority)
    const offset = others.length ? this.pollOffset % others.length : 0
    const rotated = [...others.slice(offset), ...others.slice(0, offset)]
    const entries = (priority ? [priority, ...rotated] : rotated).slice(0, 8)
    const consumed = entries.length - (priority ? 1 : 0)
    this.pollOffset = others.length ? (offset + consumed) % others.length : 0
    const results = await Promise.allSettled(
      entries.map((item) => this.client.transcription(item.id, signal)),
    )
    if (this.current(generation) && !signal.aborted) {
      this.pollError.value = null
      for (const result of results) {
        if (result.status === 'fulfilled') this.merge(result.value, generation, signal)
        else if (this.denied(result.reason)) break
        else this.pollError.value = result.reason
      }
    }
    if (this.pollController === controller) this.pollController = undefined
  }
  cancelPolling(): void {
    this.pollController?.abort()
    this.pollController = undefined
  }
  async mutate(kind: 'delete' | 'retry', id: string): Promise<boolean> {
    if (!this.owner || this.busy.value || this.mutationUnknown.value) return false
    const generation = this.generation
    this.busy.value = true
    this.mutationError.value = null
    this.mutationUnknown.value = false
    const options = {
      expectedUser: this.owner,
      signal: this.accountController.signal,
      beforeSend: () => this.assert(generation),
    }
    try {
      if (kind === 'delete') {
        await this.client.remove(id, options)
        this.assert(generation)
        this.items.value = this.items.value.filter((item) => item.id !== id)
        if (this.selected.value === id) await this.select(null)
        await this.refresh()
      } else
        this.merge(await this.client.retry(id, options), generation, this.accountController.signal)
      return this.current(generation)
    } catch (error) {
      if (this.current(generation)) {
        if (this.denied(error)) return false
        this.mutationError.value = error
        this.mutationUnknown.value =
          !(error instanceof ApiError && error.status < 500) &&
          writeAttempt(error) !== 'not_dispatched'
      }
      return false
    } finally {
      if (this.current(generation)) this.busy.value = false
    }
  }
}
