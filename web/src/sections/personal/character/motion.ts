import { ease, normalizeComposition, type Composition } from './morphology'

export type MotionStyle = 'calm' | 'athletic' | 'stage'
export type PoseWeights = [number, number, number]
export interface MotionSample {
  pose: PoseWeights
  chestExpansion: number
  chestPitch: number
  torsoRoll: number
  torsoYaw: number
  shoulderLift: number
  armSway: number
}
const smoothRange = (value: number, min: number, max: number) => ease((value - min) / (max - min))
export function motionCharacter(value: Composition) {
  const { fat, muscle } = normalizeComposition(value)
  const stage = smoothRange(muscle, 0.55, 0.95) * (1 - 0.65 * smoothRange(fat, 0.25, 0.9))
  return {
    stage,
    style: (stage > 0.62 ? 'stage' : stage > 0.18 ? 'athletic' : 'calm') as MotionStyle,
    breathPeriod: 5.5 - muscle * 1.1 + fat * 0.5,
    breathDepth: 0.009 + muscle * 0.003 + fat * 0.002,
    sway: 0.0028 + muscle * 0.001,
  }
}

/** Bounded active time; paused and background time never becomes animation time. */
export function advanceMotionTime(elapsed: number, deltaSeconds: number, enabled: boolean): number {
  return (
    elapsed +
    (enabled && Number.isFinite(deltaSeconds) ? Math.max(0, Math.min(0.1, deltaSeconds)) : 0)
  )
}
export function advanceBreathPhase(
  phase: number,
  deltaSeconds: number,
  composition: Composition,
  enabled: boolean,
): number {
  const dt = advanceMotionTime(0, deltaSeconds, enabled)
  return phase + (dt * Math.PI * 2) / motionCharacter(composition).breathPeriod
}
/** All offsets are absolute samples from the freshly blended pose, never integrated rotations. */
export function sampleMotion(
  elapsed: number,
  composition: Composition,
  breathPhase = (elapsed * Math.PI * 2) / 5,
): MotionSample {
  const character = motionCharacter(composition)
  const breath = Math.sin(breathPhase)
  return {
    pose: [1, 0, 0],
    chestExpansion: breath * character.breathDepth,
    chestPitch: breath * 0.0035,
    torsoRoll: Math.sin(elapsed * 0.46) * character.sway,
    torsoYaw: Math.sin(elapsed * 0.29) * (0.004 + character.stage * 0.005),
    shoulderLift: breath * 0.0035 + Math.sin(elapsed * 0.7) * 0.002,
    armSway: Math.sin(elapsed * 0.48 + 0.4) * 0.007,
  }
}
