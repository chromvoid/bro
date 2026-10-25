import { minuteOfDayIn } from '@/core/dates'

/**
 * A night as lanes, one row per state: a stage reads by its row as well as its colour, and an
 * empty «Бодрствование» row says the device saw no waking. Lying awake in bed has its own row.
 * Positions run 0–1000 across the episode, the width of the lane drawings' viewBox.
 */
export type Lane = 'awake' | 'in_bed' | 'rem' | 'light' | 'deep' | 'sleep' | 'unknown' | 'conflict'
export interface Part {
  start_at: string
  end_at: string
  lane: Lane
}
export interface ChartLane {
  lane: Lane
  minutes: number
  parts: (Part & { x: number; width: number })[]
}

export const WIDTH = 1000
const ORDER: Lane[] = ['awake', 'in_bed', 'rem', 'light', 'deep', 'sleep', 'unknown', 'conflict']
const STAGES: Lane[] = ['rem', 'light', 'deep']
const MINUTE = 60_000
const QUARTER = 15 * MINUTE
// Whole hours a few steps apart: the axis stays readable on a phone-wide chart.
const STEPS = [1, 2, 3, 4, 6, 12]
const MAX_TICKS = 4

export const isStage = (lane: Lane) => STAGES.includes(lane)

/** A segment of the sleep analysis: sleep goes to its stage's row, or to «Сон» without one. */
export function laneOf(state: string, stage: string | null): Lane {
  if (state === 'sleep') return STAGES.find((lane) => lane === stage) ?? 'sleep'
  if (state === 'awake') return stage === 'awake_in_bed' ? 'in_bed' : 'awake'
  return state === 'conflict' ? state : 'unknown'
}

/** A stage of a reported session; out of bed is awake. */
export function stageLane(stage: string): Lane {
  if (stage === 'sleeping') return 'sleep'
  if (stage === 'awake_in_bed') return 'in_bed'
  if (stage === 'awake' || stage === 'out_of_bed') return 'awake'
  return STAGES.find((lane) => lane === stage) ?? 'unknown'
}

const length = (part: Part) => Date.parse(part.end_at) - Date.parse(part.start_at)

/** The rows of a night in reading order, each with its parts placed across the episode. */
export function nightLanes(start_at: string, end_at: string, parts: Part[]): ChartLane[] {
  const start = Date.parse(start_at)
  const span = Math.max(Date.parse(end_at) - start, 1)
  const at = (value: string) => ((Date.parse(value) - start) / span) * WIDTH
  // No waking is a finding only when every minute of the episode is known sleep or waking.
  const known = parts
    .filter((part) => part.lane !== 'unknown' && part.lane !== 'conflict')
    .reduce((sum, part) => sum + length(part), 0)
  const slept = parts.some((part) => part.lane === 'sleep' || isStage(part.lane))
  return ORDER.filter(
    (lane) =>
      parts.some((part) => part.lane === lane) ||
      (lane === 'awake' && slept && known >= span - MINUTE),
  ).map((lane) => {
    const own = parts.filter((part) => part.lane === lane)
    return {
      lane,
      minutes: Math.round(own.reduce((sum, part) => sum + length(part), 0) / MINUTE),
      parts: own.map((part) => ({
        ...part,
        x: at(part.start_at),
        width: at(part.end_at) - at(part.start_at),
      })),
    }
  })
}

/** Where each turn falls across the episode; instants outside it are left out. */
export function turnMarks(start_at: string, end_at: string, changes: readonly string[]) {
  const start = Date.parse(start_at)
  const span = Math.max(Date.parse(end_at) - start, 1)
  return changes
    .map((at) => ({ at, x: ((Date.parse(at) - start) / span) * WIDTH }))
    .filter((mark) => mark.x >= 0 && mark.x < WIDTH)
}

/** Whole local hours inside the episode, a step apart that keeps at most four of them. */
export function hourTicks(start_at: string, end_at: string, timeZone: string) {
  const start = Date.parse(start_at)
  const end = Date.parse(end_at)
  const step = (STEPS.find((hours) => (end - start) / 3_600_000 / hours <= MAX_TICKS) ?? 24) * 60
  const ticks: { x: number; at: string }[] = []
  // Quarter hours, because some zones are offset by 30 or 45 minutes from UTC.
  for (let time = Math.ceil(start / QUARTER) * QUARTER; time < end; time += QUARTER) {
    if (time > start && minuteOfDayIn(timeZone, new Date(time)) % step === 0)
      ticks.push({ x: ((time - start) / (end - start)) * WIDTH, at: new Date(time).toISOString() })
  }
  return ticks
}
