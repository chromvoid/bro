/**
 * The shell's one status line: what the app is doing or what went wrong outside the current screen
 * (a call on another device, background recording, the connection, speech). The
 * most important item shows; the rest wait in the sheet behind it.
 */
import type { Component } from 'vue'
import type { RouteLocationRaw } from 'vue-router'

export type StatusTone = 'active' | 'danger' | 'warning' | 'neutral'

export interface StatusAction {
  label: string
  run?: () => void
  to?: RouteLocationRaw
  variant?: 'default' | 'outline' | 'destructive' | 'ghost'
  disabled?: boolean
  testid?: string
}

export interface StatusItem {
  id: string
  /** Lower comes first: PRIORITY below. */
  rank: number
  icon: Component
  tone: StatusTone
  /** One short line, ~32 characters, for the status line at 360 px. */
  line: string
  /** The full explanation in the sheet. */
  detail?: string
  /** What a screen reader hears when this item comes on top; defaults to the line. */
  announce?: string
  /** One action on the line itself, for what must stop in one tap. */
  quick?: StatusAction
  actions: StatusAction[]
  /** Keeps the line on screen while scrolling: a call or a recording in progress. */
  sticky: boolean
  testid?: string
}

export const PRIORITY = {
  call: 0,
  recording: 1,
  ambientStopped: 2,
  speech: 3,
  offline: 4,
  speechProblem: 7,
  queued: 8,
} as const

export function ranked(items: Array<StatusItem | null>): StatusItem[] {
  return items.filter((item): item is StatusItem => item !== null).sort((a, b) => a.rank - b.rank)
}

export function isSticky(items: StatusItem[]): boolean {
  return items.some((item) => item.sticky)
}
