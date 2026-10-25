import {
  AnimationMixer,
  Bone,
  LoopOnce,
  PropertyBinding,
  type AnimationClip,
  type Object3D,
} from 'three/webgpu'

/** Source time is independent of body shape, rendering, and the authored scenario director. */
export class MocapTimeline {
  elapsed = 0
  playing = false

  constructor(
    readonly duration: number,
    readonly loop = false,
  ) {
    if (!Number.isFinite(duration) || duration <= 0) throw new Error('Invalid mocap duration')
  }

  play() {
    if (this.elapsed >= this.duration) this.elapsed = 0
    this.playing = true
  }

  pause() {
    this.playing = false
  }

  seek(seconds: number) {
    if (!Number.isFinite(seconds)) return
    this.elapsed = Math.min(this.duration, Math.max(0, seconds))
    this.pause()
  }

  advance(delta: number, visibleAndEnabled: boolean) {
    if (!this.playing || !visibleAndEnabled || !Number.isFinite(delta)) return
    const next = this.elapsed + Math.min(0.1, Math.max(0, delta))
    this.elapsed = this.loop ? next % this.duration : Math.min(this.duration, next)
    if (!this.loop && this.elapsed >= this.duration) this.pause()
  }
}

/** Sample exported full-body source animation without adding synthetic offsets. */
export function createMocapPlayer(
  root: Object3D,
  clips: AnimationClip[],
  name = 'cmu_flexing',
  options: { loopBlendSeconds?: number } = {},
) {
  const clip = clips.find((candidate) => candidate.name === name)
  if (!clip || !clip.tracks.length || !Number.isFinite(clip.duration) || clip.duration <= 0)
    throw new Error('Missing mocap clip')
  const blend = options.loopBlendSeconds ?? 0
  if (!Number.isFinite(blend) || blend < 0 || blend >= clip.duration / 2)
    throw new Error('Invalid mocap loop blend')
  const duration = clip.duration - blend
  const blendStart = duration - blend
  const nodes = new Set<Object3D>()
  for (const track of clip.tracks) {
    const parsed = PropertyBinding.parseTrackName(track.name)
    const node = root.getObjectByName(parsed.nodeName)
    let containsBones = false
    node?.traverse((child) => {
      if (child instanceof Bone) containsBones = true
    })
    // Armature ancestors may carry root motion; model placement and morphology stay independent.
    if (
      !node ||
      node === root ||
      !containsBones ||
      parsed.objectName ||
      parsed.propertyIndex !== undefined ||
      !['position', 'quaternion', 'scale'].includes(parsed.propertyName)
    )
      throw new Error(`Invalid skeleton-only mocap track: ${track.name}`)
    nodes.add(node)
  }
  root.traverse((node) => {
    if (node instanceof Bone) nodes.add(node)
  })
  const capture = () =>
    [...nodes].map((node) => ({
      node,
      position: node.position.clone(),
      quaternion: node.quaternion.clone(),
      scale: node.scale.clone(),
    }))
  const rest = capture()
  const clean = capture()
  const source = capture()
  const tail = blend > 0 ? capture() : []
  const restore = (pose: typeof rest) => {
    for (const value of pose) {
      value.node.position.copy(value.position)
      value.node.quaternion.copy(value.quaternion)
      value.node.scale.copy(value.scale)
    }
  }
  const save = (pose: typeof rest) => {
    for (const value of pose) {
      value.position.copy(value.node.position)
      value.quaternion.copy(value.node.quaternion)
      value.scale.copy(value.node.scale)
    }
  }
  const mixer = new AnimationMixer(root)
  const action = mixer.clipAction(clip)
  action.setLoop(LoopOnce, 1)
  action.clampWhenFinished = true
  action.play()
  action.paused = true
  let previous = -1
  let previousSource = -1
  const sampleSource = (time: number) => {
    // PropertyMixer may skip unchanged bindings. Restore its last raw pose,
    // independently of the final blended pose or another scenario's changes.
    restore(source)
    if (time === previousSource) return
    action.time = time
    mixer.update(0)
    save(source)
    previousSource = time
  }
  return {
    duration,
    sourceDuration: clip.duration,
    reset() {
      restore(rest)
    },
    sample(seconds: number) {
      const time = Number.isFinite(seconds) ? Math.max(0, Math.min(duration, seconds)) : 0
      restore(clean)
      if (time === previous) return
      sampleSource(time + blend)
      if (blend > 0 && time > blendStart) {
        save(tail)
        sampleSource(time - blendStart)
        const progress = (time - blendStart) / blend
        const weight = progress * progress * (3 - 2 * progress)
        for (const value of tail) {
          value.node.position.lerpVectors(value.position, value.node.position, weight)
          value.node.quaternion.slerp(value.quaternion, 1 - weight)
          value.node.scale.lerpVectors(value.scale, value.node.scale, weight)
        }
      }
      // The loop begins at source B. Its final B seconds mix source [D−B, D]
      // with [0, B], meeting that same first pose without extending the clip.
      save(clean)
      previous = time
    },
    dispose() {
      mixer.stopAllAction()
      mixer.uncacheRoot(root)
    },
  }
}
