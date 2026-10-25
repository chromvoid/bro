/** Schedule a small decoded window; provider/network reading never waits for playback. */
export class PcmPlayback {
  private readonly queue: Array<{ audio: Uint8Array; offset: number }> = []
  private readonly sources = new Set<AudioBufferSourceNode>()
  private queuedBytes = 0
  private nextStart = 0
  private finished = false
  private closed = false
  private resolve!: () => void
  private reject!: (error: unknown) => void
  private readonly complete: Promise<void>
  private readonly abort: () => void

  constructor(
    private readonly context: AudioContext,
    private readonly sampleRate: number,
    private readonly signal: AbortSignal,
    private readonly active: (playing: boolean) => void,
    private readonly failed: (error: unknown) => void = () => undefined,
  ) {
    this.complete = new Promise((resolve, reject) => {
      this.resolve = resolve
      this.reject = reject
    })
    // Cancellation may arrive while the HTTP reader is still unwinding.
    void this.complete.catch(() => undefined)
    this.abort = () => this.cancel(signal.reason)
    signal.addEventListener('abort', this.abort, { once: true })
    if (signal.aborted) this.abort()
  }

  write(audio: Uint8Array): void {
    this.signal.throwIfAborted()
    if (this.closed || this.finished || audio.byteLength % 2) throw new Error('Invalid PCM audio')
    this.queuedBytes += audio.byteLength
    if (this.queuedBytes > 10 * 1024 * 1024) throw new Error('PCM queue exceeded')
    this.queue.push({ audio, offset: 0 })
    this.pump()
  }

  finish(): Promise<void> {
    this.finished = true
    this.pump()
    return this.complete
  }

  cancel(error: unknown = new DOMException('Playback stopped', 'AbortError')): void {
    if (this.closed) return
    this.closed = true
    this.signal.removeEventListener('abort', this.abort)
    this.queue.length = 0
    this.queuedBytes = 0
    for (const source of this.sources) {
      source.onended = null
      try {
        source.stop()
      } catch {
        /* Already ended. */
      }
      source.disconnect()
    }
    this.sources.clear()
    this.reject(error)
  }

  private pump(): void {
    if (this.closed) return
    try {
      // Six 2048-frame buffers are roughly half a second at 24 kHz.
      while (this.sources.size < 6 && this.queuedBytes >= (this.finished ? 2 : 256)) {
        const frames = Math.min(2048, this.queuedBytes / 2)
        const buffer = this.context.createBuffer(1, frames, this.sampleRate)
        const samples = buffer.getChannelData(0)
        let written = 0
        while (written < frames) {
          const first = this.queue[0]!
          const count = Math.min(frames - written, (first.audio.byteLength - first.offset) / 2)
          const view = new DataView(
            first.audio.buffer,
            first.audio.byteOffset + first.offset,
            count * 2,
          )
          for (let index = 0; index < count; index++)
            samples[written + index] = view.getInt16(index * 2, true) / 32768
          first.offset += count * 2
          written += count
          this.queuedBytes -= count * 2
          if (first.offset === first.audio.byteLength) this.queue.shift()
        }
        const source = this.context.createBufferSource()
        source.buffer = buffer
        source.connect(this.context.destination)
        this.sources.add(source)
        source.onended = () => {
          source.onended = null
          source.disconnect()
          this.sources.delete(source)
          // Event callbacks must not throw into the page; finish() observes the rejection.
          try {
            this.pump()
          } catch {
            /* pump() already cancelled the queue. */
          }
        }
        const start = Math.max(this.nextStart, this.context.currentTime + 0.025)
        this.nextStart = start + frames / this.sampleRate
        source.start(start)
      }
      if (!this.sources.size && !this.queuedBytes && this.finished) {
        this.closed = true
        this.signal.removeEventListener('abort', this.abort)
        this.resolve()
      } else this.active(this.sources.size > 0)
    } catch (error) {
      this.cancel(error)
      this.failed(error)
      throw error
    }
  }
}
