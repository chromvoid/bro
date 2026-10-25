import { describe, expect, it } from 'vitest'
import marks from '@/assets/bro/moods.json'
import { between, coordinates, ease, playMerge } from './brandMerge'
import { MERGE_MS } from './brandMood'

function path(d: string) {
  const element = document.createElementNS('http://www.w3.org/2000/svg', 'path')
  element.setAttribute('d', d)
  return element
}

/** A clock and a frame queue the test moves by hand. */
function clock() {
  let time = 1000
  let steps: Array<() => void> = []
  return {
    now: () => time,
    frame: (step: () => void) => void steps.push(step),
    pending: () => steps.length,
    tick(ms: number) {
      time += ms
      const due = steps
      steps = []
      for (const step of due) step()
    },
  }
}

describe('the merge of the thinking dots into ")"', () => {
  it('eases like cubic-bezier(0.4, 0, 0.2, 1): fast out, slow in', () => {
    expect(ease(-1)).toBe(0)
    expect(ease(0)).toBe(0)
    // Reference values solved independently with Newton's method.
    expect(ease(0.25)).toBeCloseTo(0.236587, 5)
    expect(ease(0.5)).toBeCloseTo(0.775561, 5)
    expect(ease(0.75)).toBeCloseTo(0.959368, 5)
    expect(ease(1)).toBe(1)
    expect(ease(2)).toBe(1)
  })

  it('moves every point of a piece between its dot and its third of the smile', () => {
    for (const piece of marks.merge) {
      const from = coordinates(piece.from)
      const to = coordinates(piece.to)
      expect(from).toHaveLength(to.length)
      expect(coordinates(between(from, to, 0))).toEqual(from)
      expect(coordinates(between(from, to, 1))).toEqual(to)
      const half = coordinates(between(from, to, 0.5))
      half.forEach((value, index) => expect(value).toBeCloseTo((from[index]! + to[index]!) / 2, 1))
    }
  })

  it('draws the dots at once, moves them each frame and ends on the smile exactly', () => {
    const time = clock()
    const pieces = marks.merge.map((piece) => ({ path: path('M0 0Z'), ...piece }))
    playMerge(pieces, time)
    pieces.forEach((piece) => expect(piece.path.getAttribute('d')).toBe(piece.from))

    time.tick(MERGE_MS / 2)
    pieces.forEach((piece) => {
      const drawn = piece.path.getAttribute('d')!
      expect(drawn).not.toBe(piece.from)
      expect(drawn).not.toBe(piece.to)
    })

    time.tick(MERGE_MS / 2)
    pieces.forEach((piece) => expect(piece.path.getAttribute('d')).toBe(piece.to))
    expect(time.pending()).toBe(0)
  })

  it('stopping leaves the smile and ends the frames', () => {
    const time = clock()
    const pieces = marks.merge.map((piece) => ({ path: path(piece.from), ...piece }))
    const stop = playMerge(pieces, time)
    time.tick(MERGE_MS / 4)
    stop()
    pieces.forEach((piece) => expect(piece.path.getAttribute('d')).toBe(piece.to))
    time.tick(MERGE_MS / 4)
    expect(time.pending()).toBe(0)
    pieces.forEach((piece) => expect(piece.path.getAttribute('d')).toBe(piece.to))
  })
})
