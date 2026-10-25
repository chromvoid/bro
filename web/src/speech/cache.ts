import type { AudioFormat } from '@/core/http'

export interface CachedSegment extends AudioFormat {
  audio: Uint8Array
}

interface CachedAnswer {
  connection: string
  segments: CachedSegment[]
  bytes: number
  createdAt: number
}

/** Session memory only: no recordings in IndexedDB, browser caches or the write journal. */
export class SpeechCache {
  private readonly answers = new Map<string, CachedAnswer>()
  private bytes = 0

  constructor(
    readonly maxBytes = 32 * 1024 * 1024,
    private readonly maxEntries = 16,
    private readonly ttl = 30 * 60 * 1000,
    private readonly now = () => Date.now(),
  ) {}

  get(key: string, connection: string): readonly CachedSegment[] | undefined {
    const value = this.answers.get(key)
    if (!value) return undefined
    if (value.connection !== connection || this.now() - value.createdAt >= this.ttl) {
      this.remove(key)
      return undefined
    }
    this.answers.delete(key)
    this.answers.set(key, value)
    return value.segments
  }

  put(key: string, connection: string, segments: readonly CachedSegment[]): void {
    const bytes = segments.reduce((total, segment) => total + segment.audio.byteLength, 0)
    if (!segments.length || bytes <= 0 || bytes > this.maxBytes || this.maxEntries < 1) return
    this.remove(key)
    for (const [identity, value] of this.answers)
      if (this.now() - value.createdAt >= this.ttl) this.remove(identity)
    while (this.bytes + bytes > this.maxBytes || this.answers.size >= this.maxEntries)
      this.remove(this.answers.keys().next().value!)
    this.answers.set(key, {
      connection,
      segments: segments.map((segment) => ({ ...segment, audio: segment.audio.slice() })),
      bytes,
      createdAt: this.now(),
    })
    this.bytes += bytes
  }

  invalidateConnection(connection: string): void {
    for (const [key, value] of this.answers) if (value.connection === connection) this.remove(key)
  }

  clear(): void {
    this.answers.clear()
    this.bytes = 0
  }

  private remove(key: string): void {
    const previous = this.answers.get(key)
    if (previous) this.bytes -= previous.bytes
    this.answers.delete(key)
  }
}
