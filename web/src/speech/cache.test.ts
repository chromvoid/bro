import { describe, expect, it } from 'vitest'
import { SpeechCache } from './cache'

const segment = (size = 4) => ({ contentType: 'audio/mpeg' as const, audio: new Uint8Array(size) })

describe('session speech cache', () => {
  it('owns immutable input bytes and binds entries to their connection', () => {
    const cache = new SpeechCache()
    const audio = segment()
    cache.put('key', 'one', [audio])
    audio.audio[0] = 127
    expect(cache.get('key', 'one')?.[0]?.audio[0]).toBe(0)
    expect(cache.get('key', 'other')).toBeUndefined()
  })
  it('evicts least recently used entries within both byte and entry bounds', () => {
    const cache = new SpeechCache(8, 2)
    cache.put('a', 'c', [segment()])
    cache.put('b', 'c', [segment()])
    cache.get('a', 'c')
    cache.put('c', 'c', [segment()])
    expect(cache.get('a', 'c')).toBeDefined()
    expect(cache.get('b', 'c')).toBeUndefined()
    cache.put('huge', 'c', [segment(9)])
    expect(cache.get('huge', 'c')).toBeUndefined()
    expect(cache.get('c', 'c')).toBeDefined()
  })
  it('expires complete recordings and clears on account or connection invalidation', () => {
    let now = 0
    const cache = new SpeechCache(100, 3, 10, () => now)
    cache.put('old', 'first', [segment()])
    now = 10
    expect(cache.get('old', 'first')).toBeUndefined()
    cache.put('a', 'first', [segment()])
    cache.put('b', 'second', [segment()])
    cache.invalidateConnection('first')
    expect(cache.get('a', 'first')).toBeUndefined()
    expect(cache.get('b', 'second')).toBeDefined()
    cache.clear()
    expect(cache.get('b', 'second')).toBeUndefined()
  })
})
