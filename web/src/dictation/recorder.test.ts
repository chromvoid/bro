// @vitest-environment node
import { readFileSync } from 'node:fs'
import { runInNewContext } from 'node:vm'
import { describe, expect, it } from 'vitest'

describe('recorder worklet', () => {
  it('emits 80 ms PCM16 frames in order and flushes the rest before stopping', () => {
    const emitted: Array<{ pcm?: ArrayBuffer; stopped?: boolean; limit?: boolean }> = []
    let Processor!: new () => {
      process(inputs: Float32Array[][]): boolean
      port: { onmessage(event: { data: unknown }): void }
    }
    class AudioWorkletProcessor {
      port = { postMessage: (value: (typeof emitted)[number]) => emitted.push(value) }
    }
    runInNewContext(readFileSync(new URL('./recorder.worklet.js', import.meta.url), 'utf8'), {
      AudioWorkletProcessor,
      registerProcessor: (_: string, value: typeof Processor) => (Processor = value),
      ArrayBuffer,
      DataView,
      Math,
    })
    const recorder = new Processor()
    for (let block = 0; block < 11; block++) recorder.process([[new Float32Array(128).fill(0.5)]])
    expect(emitted).toHaveLength(1)
    expect(emitted[0]!.pcm!.byteLength).toBe(2560)
    expect(new DataView(emitted[0]!.pcm!).getInt16(0, true)).toBe(16384)
    recorder.port.onmessage({ data: 'stop' })
    expect(emitted[1]!.pcm!.byteLength).toBe(256)
    expect(emitted[2]).toEqual({ stopped: true })
    recorder.process([[new Float32Array(128)]])
    expect(emitted).toHaveLength(3)
  })
})
