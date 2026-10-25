import { afterEach, describe, expect, it, vi } from 'vitest'
import { BrowserMicrophone } from './audio'

afterEach(() => vi.unstubAllGlobals())

describe('browser microphone', () => {
  it('records through an output-less worklet, with nothing routed to the destination', async () => {
    const connections: string[] = []
    const node = (name: string) => ({
      name,
      connect: vi.fn((target: { name: string }) => {
        connections.push(`${name} → ${target.name}`)
        return target
      }),
      disconnect: vi.fn(),
    })
    const recorders: unknown[][] = []
    vi.stubGlobal(
      'AudioContext',
      class {
        sampleRate = 16000
        state = 'running'
        onstatechange: (() => void) | null = null
        destination = node('destination')
        audioWorklet = { addModule: async () => undefined }
        resume = async () => undefined
        close = async () => undefined
        createMediaStreamSource = () => node('microphone')
        createGain = () => node('gain')
      },
    )
    vi.stubGlobal(
      'AudioWorkletNode',
      class {
        port = { onmessage: null }
        constructor(...options: unknown[]) {
          recorders.push(options.slice(1))
          Object.assign(this, node('recorder'))
        }
      },
    )
    const track = { stop: vi.fn(), onended: null }
    const stream = { getAudioTracks: () => [track], getTracks: () => [track] }
    vi.stubGlobal('navigator', { mediaDevices: { getUserMedia: async () => stream } })

    const microphone = new BrowserMicrophone()
    await microphone.prepare()
    microphone.begin({ frame: vi.fn(), limit: vi.fn(), interrupted: vi.fn() })

    expect(recorders).toEqual([['assistant-recorder', { numberOfOutputs: 0 }]])
    expect(connections).toEqual(['microphone → recorder'])
    microphone.release()
    expect(track.stop).toHaveBeenCalled()
  })
})
