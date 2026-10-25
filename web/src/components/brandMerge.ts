import { MERGE_MS } from './brandMood'

/**
 * The thinking dots flowing into ")": each piece's outline moves point by point from its dot to its
 * third of the smile in MERGE_MS, eased by cubic-bezier(0.4, 0, 0.2, 1) like the Android and iOS
 * splashes. A script moves the points, not SMIL: WebKit's page process died as an `<animate>` of
 * `d` ended (2026-10-08, docs/agent-workflow.md). Used by the launch screen and BrandMark.
 */
export interface MergePiece {
  path: Element
  /** The dot, an M/L polyline from moods.json `merge`. */
  from: string
  /** Its third of the smile, drawn from as many points. */
  to: string
}

/** The coordinates of an M/L polyline outline, x and y in turn. */
export function coordinates(outline: string): number[] {
  return outline
    .replaceAll('M', ' ')
    .replaceAll('L', ' ')
    .replaceAll('Z', ' ')
    .split(' ')
    .filter(Boolean)
    .map(Number)
}

/** The outline a `progress` of the way from `from` to `to`, two coordinate lists of one length. */
export function between(from: readonly number[], to: readonly number[], progress: number): string {
  const coordinate = (at: number) =>
    Math.round((from[at]! + (to[at]! - from[at]!) * progress) * 100) / 100
  let outline = ''
  for (let index = 0; index < from.length; index += 2)
    outline += `${index ? 'L' : 'M'}${coordinate(index)} ${coordinate(index + 1)}`
  return `${outline}Z`
}

const bezier = (first: number, second: number, t: number) =>
  3 * first * t * (1 - t) ** 2 + 3 * second * t ** 2 * (1 - t) + t ** 3

/** cubic-bezier(0.4, 0, 0.2, 1) at a share of the time from 0 to 1. */
export function ease(time: number): number {
  if (time <= 0) return 0
  if (time >= 1) return 1
  // The curve's time grows with its parameter, so halving finds the parameter for this time.
  let low = 0
  let high = 1
  for (let step = 0; step < 24; step++) {
    const middle = (low + high) / 2
    if (bezier(0.4, 0.2, middle) < time) low = middle
    else high = middle
  }
  return bezier(0, 1, (low + high) / 2)
}

/**
 * Draws the dots at once, then moves them each frame; the returned stop() leaves every piece at
 * its third of the smile and ends the frames. The page's own clock unless a test passes one.
 */
export function playMerge(
  pieces: readonly MergePiece[],
  {
    frame = (step: () => void) => void requestAnimationFrame(step),
    now = () => performance.now(),
  }: { frame?: (step: () => void) => void; now?: () => number } = {},
): () => void {
  const outlines = pieces.map((piece) => ({
    ...piece,
    start: coordinates(piece.from),
    end: coordinates(piece.to),
  }))
  // The ends are the outlines as given, exactly.
  const outline = (piece: (typeof outlines)[number], progress: number) => {
    if (progress <= 0) return piece.from
    if (progress >= 1) return piece.to
    return between(piece.start, piece.end, ease(progress))
  }
  const draw = (progress: number) => {
    for (const piece of outlines) piece.path.setAttribute('d', outline(piece, progress))
  }
  const started = now()
  let playing = true
  const step = () => {
    if (!playing) return
    const progress = Math.min(1, (now() - started) / MERGE_MS)
    draw(progress)
    if (progress < 1) frame(step)
    else playing = false
  }
  draw(0)
  frame(step)
  return () => {
    if (!playing) return
    playing = false
    draw(1)
  }
}
