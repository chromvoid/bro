import { describe, expect, it } from 'vitest'
import {
  AnimationClip,
  Bone,
  BufferGeometry,
  Group,
  Mesh,
  MeshBasicMaterial,
  Quaternion,
  QuaternionKeyframeTrack,
  Vector3,
  VectorKeyframeTrack,
} from 'three/webgpu'
import { createMocapPlayer } from './mocap'

function sourceMotion(name = 'looking_around') {
  const root = new Group()
  const armature = new Group()
  armature.name = 'Armature'
  const pelvis = new Bone()
  pelvis.name = 'pelvis'
  pelvis.position.y = 1
  const head = new Bone()
  head.name = 'head'
  const hand = new Bone()
  hand.name = 'hand'
  hand.position.set(0.4, 0.5, 0)
  pelvis.add(head, hand)
  armature.add(pelvis)
  const surface = new Mesh(new BufferGeometry(), new MeshBasicMaterial())
  surface.name = 'body'
  surface.morphTargetInfluences = [0.2, 0.8]
  root.add(armature, surface)
  const turns = [0, 0.5, -0.2, 0.9, 0.4].flatMap((angle) =>
    new Quaternion().setFromAxisAngle(new Vector3(0, 1, 0), angle).toArray(),
  )
  const clip = new AnimationClip(name, 8, [
    new VectorKeyframeTrack(
      'Armature.position',
      [0, 2, 4, 6, 8],
      [0, 0, 0, 0.2, 0, 0.1, -0.1, 0, 0.3, 0.4, 0, -0.2, 0.3, 0, 0.4],
    ),
    new VectorKeyframeTrack('pelvis.position', [0, 8], [0, 1, 0, 0, 0.8, 0]),
    new QuaternionKeyframeTrack('head.quaternion', [0, 2, 4, 6, 8], turns),
    new VectorKeyframeTrack('head.scale', [0, 8], [1, 1, 1, 1.2, 0.9, 1.1]),
  ])
  return { root, armature, pelvis, head, hand, surface, clip }
}

function pose(model: ReturnType<typeof sourceMotion>) {
  return {
    root: model.armature.position.clone(),
    pelvis: model.pelvis.position.clone(),
    head: model.head.quaternion.clone(),
    scale: model.head.scale.clone(),
    hand: model.hand.position.clone(),
  }
}

function expectPose(actual: ReturnType<typeof pose>, expected: ReturnType<typeof pose>) {
  expect(actual.root.distanceTo(expected.root)).toBeLessThan(1e-6)
  expect(actual.pelvis.distanceTo(expected.pelvis)).toBeLessThan(1e-6)
  expect(actual.head.angleTo(expected.head)).toBeLessThan(1e-6)
  expect(actual.scale.distanceTo(expected.scale)).toBeLessThan(1e-6)
  expect(actual.hand.distanceTo(expected.hand)).toBeLessThan(1e-6)
}

describe('source motion loop blending', () => {
  it('preserves the source body movement and speed outside the overlap', () => {
    const model = sourceMotion()
    const reference = sourceMotion()
    const player = createMocapPlayer(model.root, [model.clip], 'looking_around', {
      loopBlendSeconds: 1,
    })
    const source = createMocapPlayer(reference.root, [reference.clip], 'looking_around')
    expect(player.duration).toBe(7)
    expect(player.sourceDuration).toBe(8)
    for (const time of [0, 1.2, 3, 4.7, 6]) {
      player.sample(time)
      source.sample(time + 1)
      expectPose(pose(model), pose(reference))
    }
    player.dispose()
    source.dispose()
  })

  it('blends real tail and head poses with smoothstep, including root motion and scale', () => {
    const model = sourceMotion()
    const reference = sourceMotion()
    const player = createMocapPlayer(model.root, [model.clip], 'looking_around', {
      loopBlendSeconds: 1,
    })
    const source = createMocapPlayer(reference.root, [reference.clip], 'looking_around')
    for (const progress of [0.25, 0.5, 0.75, 1]) {
      source.sample(7 + progress)
      const tail = pose(reference)
      source.sample(progress)
      const head = pose(reference)
      const weight = progress * progress * (3 - 2 * progress)
      const expected = {
        root: tail.root.lerp(head.root, weight),
        pelvis: tail.pelvis.lerp(head.pelvis, weight),
        head: tail.head.slerp(head.head, weight),
        scale: tail.scale.lerp(head.scale, weight),
        hand: tail.hand.lerp(head.hand, weight),
      }
      player.sample(6 + progress)
      expectPose(pose(model), expected)
    }
    player.dispose()
    source.dispose()
  })

  it('closes the loop continuously and restores frames after other scenarios overwrite the rig', () => {
    const model = sourceMotion()
    const reference = sourceMotion()
    const player = createMocapPlayer(model.root, [model.clip], 'looking_around', {
      loopBlendSeconds: 1,
    })
    const pristine = createMocapPlayer(reference.root, [reference.clip], 'looking_around', {
      loopBlendSeconds: 1,
    })
    player.sample(0)
    const beginning = pose(model)
    player.sample(7)
    expectPose(pose(model), beginning)
    player.sample(7 - 1e-5)
    expect(model.armature.position.distanceTo(beginning.root)).toBeLessThan(1e-5)
    expect(model.head.quaternion.angleTo(beginning.head)).toBeLessThan(1e-5)
    for (const time of [6.75, 6.75, 0, 0.01, 7, 0, 6.25, 2, 6.25, 7]) {
      model.armature.position.set(99, -8, 42)
      model.pelvis.position.set(5, 10, 2)
      model.head.quaternion.setFromAxisAngle(new Vector3(1, 0, 0), 2)
      model.head.scale.setScalar(9)
      model.hand.position.setScalar(-10)
      player.sample(time)
      pristine.sample(time)
      expectPose(pose(model), pose(reference))
    }
    player.reset()
    expect(model.pelvis.position.y).toBe(1)
    expect(model.head.quaternion.equals(new Quaternion())).toBe(true)
    player.sample(7)
    expectPose(pose(model), beginning)
    player.dispose()
    pristine.dispose()
  })

  it('never writes model placement or morphology while sampling, resetting or disposing', () => {
    const model = sourceMotion()
    const player = createMocapPlayer(model.root, [model.clip], 'looking_around', {
      loopBlendSeconds: 1,
    })
    model.root.position.set(4, 5, 6)
    model.surface.morphTargetInfluences = [0.75, 0.25]
    for (const time of [0, 3, 6.5, 7, 0]) player.sample(time)
    player.reset()
    player.dispose()
    expect(model.root.position.toArray()).toEqual([4, 5, 6])
    expect(model.surface.morphTargetInfluences).toEqual([0.75, 0.25])
  })

  it.each(['cmu_flexing', 'game_idle'])('leaves default and zero-blend %s unchanged', (name) => {
    const model = sourceMotion(name)
    const reference = sourceMotion(name)
    const player = createMocapPlayer(model.root, [model.clip], name)
    const zero = createMocapPlayer(reference.root, [reference.clip], name, { loopBlendSeconds: 0 })
    expect(player.duration).toBe(8)
    expect(player.sourceDuration).toBe(8)
    for (const time of [0, 0.2, 2.5, 7.9, 8, 8, -1, Number.NaN, 99]) {
      player.sample(time)
      zero.sample(time)
      expectPose(pose(model), pose(reference))
    }
    expect(model.pelvis.position.y).toBeCloseTo(0.8)
    expect(model.head.quaternion.angleTo(new Quaternion())).toBeCloseTo(0.4)
    player.dispose()
    zero.dispose()
  })

  it.each([-1, 4, 4.1, Number.NaN, Number.POSITIVE_INFINITY])(
    'rejects invalid overlap %s',
    (loopBlendSeconds) => {
      const { root, clip } = sourceMotion()
      expect(() => createMocapPlayer(root, [clip], 'looking_around', { loopBlendSeconds })).toThrow(
        'Invalid mocap loop blend',
      )
    },
  )
})
