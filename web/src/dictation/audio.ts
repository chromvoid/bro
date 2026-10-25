/**
 * Where dictation audio comes from. The browser uses the microphone through an AudioWorklet;
 * a native shell can provide its own source with the same frames (PCM16 LE mono, 16 kHz).
 */
import workletUrl from './recorder.worklet.js?url'

export type MicrophoneProblem = 'denied' | 'unavailable' | 'unsupported'

export class MicrophoneError extends Error {
  constructor(readonly problem: MicrophoneProblem) {
    super(problem)
  }
}

export interface CaptureHandlers {
  /** One frame of at most one second; the browser source sends 80 ms frames. */
  frame(pcm: ArrayBuffer): void
  /** The 120-second limit was reached; capture has stopped. */
  limit(): void
  /** The device, a call or the system took the microphone away. */
  interrupted(): void
}

export interface AudioSource {
  /** Asks for the microphone and prepares capture without sending audio yet. */
  prepare(): Promise<void>
  /** Starts delivering frames. */
  begin(handlers: CaptureHandlers): void
  /** Stops capture after delivering the last, possibly shorter, frame. */
  stop(): Promise<void>
  /** Releases the microphone; safe to call more than once. */
  release(): void
}

export class BrowserMicrophone implements AudioSource {
  private stream: MediaStream | null = null
  private context: AudioContext | null = null
  private node: AudioWorkletNode | null = null
  private input: MediaStreamAudioSourceNode | null = null
  private stopped: (() => void) | null = null

  async prepare(): Promise<void> {
    if (!navigator.mediaDevices?.getUserMedia || typeof AudioWorkletNode === 'undefined') {
      throw new MicrophoneError('unsupported')
    }
    try {
      this.stream = await navigator.mediaDevices.getUserMedia({
        audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true },
        video: false,
      })
    } catch (error) {
      const name = error instanceof DOMException ? error.name : ''
      throw new MicrophoneError(
        name === 'NotAllowedError' || name === 'SecurityError' ? 'denied' : 'unavailable',
      )
    }
    // The browser resamples the microphone to the context rate.
    this.context = new AudioContext({ sampleRate: 16000 })
    await this.context.resume()
    await this.context.audioWorklet.addModule(workletUrl)
    if (this.context.sampleRate !== 16000) throw new MicrophoneError('unsupported')
  }

  begin(handlers: CaptureHandlers): void {
    const context = this.context
    const stream = this.stream
    if (!context || !stream) throw new Error('prepare() first')
    // Without outputs the recorder is pulled by the graph on its own (Chromium and WebKit alike).
    // Routed to the destination through a muted gain instead, the output stays silent, and after
    // 30 seconds of silence Chromium renders from a timer instead of the audio device: that is
    // not real time, and a long recording loses microphone audio.
    this.node = new AudioWorkletNode(context, 'assistant-recorder', { numberOfOutputs: 0 })
    this.node.port.onmessage = ({ data }: MessageEvent) => {
      if (data.pcm) handlers.frame(data.pcm)
      if (data.limit) handlers.limit()
      if (data.stopped) this.stopped?.()
    }
    this.input = context.createMediaStreamSource(stream)
    this.input.connect(this.node)
    for (const track of stream.getAudioTracks()) track.onended = () => handlers.interrupted()
    context.onstatechange = () => {
      // Safari reports "interrupted" when a call or another app takes the audio session.
      if ((context.state as string) === 'interrupted') handlers.interrupted()
    }
  }

  stop(): Promise<void> {
    const node = this.node
    if (!node) return Promise.resolve()
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('recorder did not stop')), 2000)
      this.stopped = () => {
        clearTimeout(timer)
        resolve()
      }
      node.port.postMessage('stop')
    })
  }

  release(): void {
    for (const track of this.stream?.getTracks() ?? []) track.stop()
    this.input?.disconnect()
    this.node?.disconnect()
    if (this.context && this.context.state !== 'closed')
      void this.context.close().catch(() => undefined)
    this.stream = null
    this.context = null
    this.node = null
  }
}
