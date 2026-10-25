import { motionCharacter } from './motion'
import type { Composition } from './morphology'

export type Scenario = 'calm' | 'athletic' | 'stage'
export type ScenarioSelection = 'auto' | Scenario
export type ScenarioPhase = 'idle' | 'entry' | 'motion' | 'exit'
export const SCENARIO_DURATIONS: Record<Scenario, number> = {
  calm: 10,
  athletic: 14,
  stage: 18,
}
export const SCENARIO_NAMES: readonly Scenario[] = ['calm', 'athletic', 'stage']

export function nextAutomaticScenario(composition: Composition, last: Scenario | null): Scenario {
  const style = motionCharacter(composition).style
  const choices: Scenario[] =
    style === 'stage'
      ? ['stage', 'calm', 'athletic']
      : style === 'athletic'
        ? ['athletic', 'calm']
        : ['calm']
  const previous = last === null ? -1 : choices.indexOf(last)
  return choices[(previous + 1) % choices.length]!
}

export function scenarioPhase(scene: Scenario | null, elapsed: number): ScenarioPhase {
  if (scene === null) return 'idle'
  if (elapsed < 1.5) return 'entry'
  if (elapsed >= SCENARIO_DURATIONS[scene] - 2) return 'exit'
  return 'motion'
}

/** Deterministic scene director. Selections wait for the authored exit; no arbitrary clip seeks. */
export class ScenarioDirector {
  selection: ScenarioSelection = 'auto'
  active: Scenario | null = null
  queued: Scenario | null = null
  elapsed = 0
  rest = 3.5
  last: Scenario | null = null
  stopping = false

  select(selection: ScenarioSelection, composition: Composition) {
    this.selection = selection
    this.play(
      selection === 'auto'
        ? nextAutomaticScenario(composition, this.active ?? this.last)
        : selection,
    )
  }
  play(scene: Scenario, once = false) {
    this.queued = scene
    // A queued one-shot still runs after the active clip exits. Stopping only
    // prevents subsequent automatic starts; it never discards this request.
    this.stopping = once
    if (this.active === null) this.rest = 0
  }
  stopAfterExit() {
    this.queued = null
    this.stopping = true
  }
  resume() {
    this.stopping = false
    if (this.active === null) this.rest = Math.min(this.rest, 1.5)
  }
  cancel() {
    this.active = null
    this.queued = null
    this.elapsed = 0
    this.stopping = true
  }
  update(
    deltaSeconds: number,
    options: {
      enabled: boolean
      allowStart: boolean
      automatic: boolean
      composition: Composition
    },
  ) {
    const result: { started: Scenario | null; ended: Scenario | null } = {
      started: null,
      ended: null,
    }
    if (!options.enabled || !Number.isFinite(deltaSeconds)) return result
    const dt = Math.max(0, Math.min(0.1, deltaSeconds))
    if (this.active !== null) {
      this.elapsed = Math.min(SCENARIO_DURATIONS[this.active], this.elapsed + dt)
      if (this.elapsed >= SCENARIO_DURATIONS[this.active] - 1e-8) {
        result.ended = this.active
        this.last = this.active
        this.active = null
        this.elapsed = 0
        this.rest = this.queued
          ? 0.65
          : motionCharacter(options.composition).style === 'calm'
            ? 12
            : 6
      }
      return result
    }
    this.rest = Math.max(0, this.rest - dt)
    if (this.rest > 0 || !options.allowStart) return result
    const next =
      this.queued ??
      (options.automatic && !this.stopping
        ? this.selection === 'auto'
          ? nextAutomaticScenario(options.composition, this.last)
          : this.selection
        : null)
    if (next !== null) {
      this.active = next
      this.queued = null
      this.elapsed = 0
      result.started = next
    }
    return result
  }
}
