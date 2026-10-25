import type { BodyCharacterId } from './api'
import { bodyAsset } from './bodyCatalog'
import type { BodyVisualState } from './bodyVisual'
import {
  createCharacterRenderer,
  previewCanvasSize,
  type CharacterRenderer,
  type CharacterRendererState,
  type View,
} from './character/renderer'
import { compositionWeights, normalizeComposition } from './character/morphology'
import { LOOKAROUND_CLIP, LOOKAROUND_LOOP_BLEND_SECONDS } from './character/lookaround'
import type { Scenario, ScenarioPhase } from './character/scenarios'

export type BodyView = View
export interface BodyViewState {
  yaw: number
  polar: number
  radius: number
}

export type BodyLayer = 'composition' | 'muscle' | 'fat' | 'water'
export type BodyRendererMode = 'webgpu' | 'webgl' | 'poster'
export type BodyScenario = 'idle' | 'auto' | Scenario
export interface BodyRendererState {
  scenario: BodyScenario
  activeScenario: Scenario | null
  queuedScenario: Scenario | null
  phase: ScenarioPhase
  progress: number
  playing: boolean
  paused: boolean
  motionSource: 'idle' | 'authored' | 'static'
}
/** Null keeps one artistic axis neutral; neither axis is a stored body measurement. */
export function bodyComposition(fat: number | null, muscle: number | null) {
  return normalizeComposition({ fat: fat ?? 0.4, muscle: muscle ?? 0.5 })
}

/** Continuous interpolation of the shared 6 × 5 authored shape grid. */
export function bodyMorphWeights(fat: number | null, muscle: number | null): number[] {
  return compositionWeights(bodyComposition(fat, muscle))
}

export function bodyCanvasSize(width: number, height: number, dpr: number) {
  return previewCanvasSize(width, height, dpr, 1.5, 900)
}

/** Unavailable measurements must not choose athletic choreography from neutral defaults. */
export function bodyScenarioSelection(scenario: BodyScenario, visual: BodyVisualState) {
  return scenario === 'auto' && (visual.fat === null || visual.muscle === null) ? 'calm' : scenario
}

export interface BodyRenderer {
  readonly mode: 'webgpu' | 'webgl'
  setLayer(layer: BodyLayer): void
  setVisual(visual: BodyVisualState): void
  setYaw(yaw: number): void
  setView(view: BodyView): void
  getViewState(): BodyViewState
  setActive(active: boolean): void
  setMotion(enabled: boolean): void
  setPaused(paused: boolean): void
  setScenario(scenario: BodyScenario): void
  setComparison(comparison: boolean): void
  resize(): void
  destroy(): void
}

/** Lazy production adapter. The isolated preview uses this exact character rendering core. */
export async function createBodyRenderer(
  canvas: HTMLCanvasElement,
  options: {
    visual: BodyVisualState
    characterId?: BodyCharacterId
    initialView?: BodyViewState
    layer: BodyLayer
    motion: boolean
    active: boolean
    signal: AbortSignal
    scenario?: BodyScenario
    comparison?: boolean
    paused?: boolean
    onState?: (state: BodyRendererState) => void
    onFailure: () => void
  },
): Promise<BodyRenderer | null> {
  if (options.signal.aborted) return null
  let visual = options.visual
  const asset = bodyAsset(visual.template, options.characterId ?? 'sculpture')
  let scenario = options.scenario ?? 'idle'
  let comparison = options.comparison ?? false
  let paused = options.paused ?? false
  let motion = options.motion
  let destroyed = false
  let failed = false
  let state: CharacterRendererState | null = null
  const publish = () => {
    if (destroyed || state === null) return
    const moving = motion && !paused && !comparison
    canvas.dataset.characterTemplate = visual.template
    canvas.dataset.characterId = asset.characterId
    canvas.dataset.characterScenario = scenario
    canvas.dataset.characterPaused = String(!moving)
    options.onState?.({
      scenario,
      activeScenario: comparison ? null : state.activeScenario,
      queuedScenario: comparison ? null : state.queuedScenario,
      phase: comparison ? 'idle' : state.scenarioPhase,
      progress: comparison ? 0 : state.scenarioProgress,
      playing: moving && (state.clipPlaying || state.playing),
      paused: !moving,
      motionSource: comparison
        ? 'static'
        : state.motionSource === 'game-idle'
          ? 'idle'
          : state.motionSource === 'authored'
            ? 'authored'
            : 'static',
    })
  }
  const core = await createCharacterRenderer(canvas, {
    assetUrl: asset.modelUrl,
    idleUrl: asset.animationUrl,
    initialView: options.initialView,
    idleClipName: LOOKAROUND_CLIP,
    idleLoopBlendSeconds: LOOKAROUND_LOOP_BLEND_SECONDS,
    initialComposition: bodyComposition(visual.fat, visual.muscle),
    compositionTransition: 650,
    active: options.active,
    externalControls: true,
    maxDpr: 1.5,
    maxBufferSize: 900,
    reducedMotion: !motion,
    signal: options.signal,
    onState(next) {
      state = next
      if (next.status === 'unavailable' && !failed) {
        failed = true
        options.onFailure()
      }
      publish()
    },
  })
  if (!core || options.signal.aborted) {
    core?.dispose()
    return null
  }
  const character: CharacterRenderer = core
  // Motion permission and the user's pause are independent. Reduced motion also
  // snaps morphology transitions; pause freezes only the animated skeleton.
  const syncMotion = () => {
    character.setReducedMotion(!motion)
    character.setMotionEnabled(motion && !paused)
    if (scenario === 'idle') character.setSourceAnimationPlaying(motion && !paused)
    publish()
  }
  const applyScenario = () => {
    const selected = bodyScenarioSelection(scenario, visual)
    if (selected === 'idle') {
      character.playSourceAnimation()
      // A failed idle download still leaves a usable model and quiet breathing.
      if (!state?.clipAvailable) character.setPose('relaxed')
    } else character.setScenario(selected)
    syncMotion()
  }
  character.setAppearance(options.layer)
  character.setComparison(comparison)
  applyScenario()
  return {
    mode: (state as CharacterRendererState | null)?.mode === 'webgpu' ? 'webgpu' : 'webgl',
    setLayer(value) {
      if (!destroyed) character.setAppearance(value)
    },
    setVisual(value) {
      if (destroyed) return
      const priorSelection = bodyScenarioSelection(scenario, visual)
      visual = value
      character.setComposition(bodyComposition(value.fat, value.muscle))
      if (bodyScenarioSelection(scenario, visual) !== priorSelection) applyScenario()
    },
    setYaw(value) {
      if (!destroyed) character.setYaw(value)
    },
    setView(value) {
      if (!destroyed) character.setView(value)
    },
    getViewState() {
      return character.getViewState()
    },
    setActive(value) {
      if (!destroyed) character.setActive(value)
    },
    setMotion(value) {
      if (destroyed) return
      motion = value
      syncMotion()
    },
    setPaused(value) {
      if (destroyed) return
      paused = value
      syncMotion()
    },
    setScenario(value) {
      if (destroyed || scenario === value) return
      scenario = value
      applyScenario()
    },
    setComparison(value) {
      if (destroyed || comparison === value) return
      comparison = value
      character.setComparison(value)
      publish()
    },
    resize() {
      if (!destroyed) character.resize()
    },
    destroy() {
      if (destroyed) return
      destroyed = true
      character.dispose()
    },
  }
}
