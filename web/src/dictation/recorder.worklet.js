// Runs on the audio thread. The AudioContext already resamples the microphone to 16 kHz;
// this converts to PCM16 little-endian mono and posts 80 ms frames (1280 samples, 2560 bytes).
// Samples go straight into the frame's buffer: the audio thread allocates once per frame.
const RATE = 16000
const FRAME = 1280
const LIMIT = RATE * 120

class Recorder extends AudioWorkletProcessor {
  constructor() {
    super()
    this.active = true
    this.frame = new DataView(new ArrayBuffer(FRAME * 2))
    this.filled = 0
    this.count = 0
    this.port.onmessage = ({ data }) => {
      if (data === 'stop') {
        this.active = false
        this.flush()
        this.port.postMessage({ stopped: true })
      }
    }
  }

  flush() {
    if (!this.filled) return
    const pcm =
      this.filled === FRAME ? this.frame.buffer : this.frame.buffer.slice(0, this.filled * 2)
    this.port.postMessage({ pcm }, [pcm])
    this.frame = new DataView(new ArrayBuffer(FRAME * 2))
    this.filled = 0
  }

  process(inputs) {
    const channels = inputs[0]
    if (this.active && channels?.length) {
      const length = channels[0].length
      for (let index = 0; index < length && this.count < LIMIT; index++) {
        let mono = 0
        for (let channel = 0; channel < channels.length; channel++)
          mono += channels[channel][index] / channels.length
        const sample = Math.max(-1, Math.min(1, mono))
        this.frame.setInt16(
          this.filled * 2,
          Math.round(sample * (sample < 0 ? 32768 : 32767)),
          true,
        )
        this.filled++
        this.count++
        if (this.filled === FRAME) this.flush()
      }
      if (this.count === LIMIT) {
        this.active = false
        this.flush()
        this.port.postMessage({ limit: true })
      }
    }
    return true
  }
}

registerProcessor('assistant-recorder', Recorder)
