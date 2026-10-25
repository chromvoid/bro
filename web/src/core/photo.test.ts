import { afterEach, describe, expect, it, vi } from 'vitest'
import { decode, fitWithin, PhotoError } from './photo'

describe('fitWithin', () => {
  it('limits the long side to 1600 px and keeps the aspect ratio', () => {
    expect(fitWithin(8000, 6000)).toEqual({ width: 1600, height: 1200 })
    expect(fitWithin(3024, 4032)).toEqual({ width: 1200, height: 1600 })
  })

  it('never enlarges a small photo', () => {
    expect(fitWithin(640, 480)).toEqual({ width: 640, height: 480 })
  })
})

describe('decode', () => {
  afterEach(() => vi.unstubAllGlobals())

  /** A browser whose image header says [width]×[height], or that cannot read the photo. */
  function browser(width: number, height: number, readable = true) {
    vi.stubGlobal(
      'Image',
      class {
        onload: (() => void) | null = null
        onerror: (() => void) | null = null
        naturalWidth = width
        naturalHeight = height
        set src(_: string) {
          queueMicrotask(() => (readable ? this.onload?.() : this.onerror?.()))
        }
      },
    )
    const bitmap = { width, height, close() {} }
    const decoded = vi.fn(async () => bitmap)
    vi.stubGlobal('createImageBitmap', decoded)
    return decoded
  }

  it('decodes a portrait photo once, straight at 1600 px on the long side', async () => {
    const decoded = browser(3024, 4032)
    await decode(new Blob(['photo']))
    expect(decoded).toHaveBeenCalledTimes(1)
    expect(decoded.mock.calls[0]).toEqual([
      expect.any(Blob),
      { resizeHeight: 1600, resizeQuality: 'high' },
    ])
  })

  it('keeps a small photo at its own size', async () => {
    const decoded = browser(1200, 900)
    await decode(new Blob(['photo']))
    expect(decoded.mock.calls).toEqual([[expect.any(Blob)]])
  })

  it('reports a format the browser cannot read', async () => {
    const decoded = browser(0, 0, false)
    await expect(decode(new Blob(['heic']))).rejects.toBeInstanceOf(PhotoError)
    expect(decoded).not.toHaveBeenCalled()
  })
})
