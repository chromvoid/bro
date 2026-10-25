import { describe, expect, it } from 'vitest'
import {
  Bone,
  BufferGeometry,
  Euler,
  Group,
  Mesh,
  MeshBasicMaterial,
  PropertyBinding,
  Quaternion,
  Vector3,
} from 'three/webgpu'
import { syntheticBodyAsset } from './assetFixture'
import { createPoseCorrections, correctiveActivation } from './corrections'
import { compositionWeights, SHAPE_NAMES } from './morphology'

describe('additive joint corrections', () => {
  it('activates signed local bend relative to rest, independent of quaternion sign', () => {
    const correction = syntheticBodyAsset().pose_correctives[0]!
    const rest = new Quaternion().setFromAxisAngle(new Vector3(1, 0, 0), 0.6)
    const bend = new Quaternion().setFromAxisAngle(new Vector3(...correction.axis), 0.8)
    const rotation = rest.clone().multiply(bend)
    const weight = correctiveActivation(rotation, rest, correction)
    expect(weight).toBeGreaterThan(0)
    expect(weight).toBeLessThan(1)
    expect(correctiveActivation(rest, rest, correction)).toBe(0)
    expect(
      correctiveActivation(
        new Quaternion(-rotation.x, -rotation.y, -rotation.z, -rotation.w),
        rest,
        correction,
      ),
    ).toBeCloseTo(weight)
    expect(correctiveActivation(rest.clone().multiply(bend.invert()), rest, correction)).toBe(0)
  })

  it('activates open shoulders and flexed elbows for the authored local pose rotations', () => {
    const rest = new Quaternion().setFromEuler(new Euler(-0.4, 0.2, 0.7))
    const corrections = syntheticBodyAsset().pose_correctives
    // These are the exporter upperarm01/lowerarm01 XYZ rotations in degrees.
    // The shoulder01 parent also moves, but cannot replace the upperarm joint driver.
    const poses = {
      relaxed: { upper: [0, 0, -20], lower: [-30, -40, 0] },
      open: { upper: [0, 0, 40], lower: [-32, 0, 0] },
      flex: { upper: [0, -45, 0], lower: [75, 0, 0] },
    }
    const weights = (pose: keyof typeof poses) =>
      corrections.map((correction, index) => {
        const side = index % 2 === 0 ? 1 : -1
        const [x, y, z] = index < 2 ? poses[pose].upper : poses[pose].lower
        const bend = new Quaternion().setFromEuler(
          new Euler((x! * Math.PI) / 180, (y! * side * Math.PI) / 180, (z! * side * Math.PI) / 180),
        )
        return correctiveActivation(rest.clone().multiply(bend), rest, correction)
      })
    expect(weights('relaxed')).toEqual([0, 0, 0, 0])
    const open = weights('open')
    expect(open[0]).toBeGreaterThan(0)
    expect(open[0]).toBeLessThan(1)
    expect(open[1]).toBeCloseTo(open[0]!)
    expect(open.slice(2)).toEqual([0, 0])
    const flex = weights('flex')
    expect(flex.slice(0, 2)).toEqual([0, 0])
    expect(flex[2]).toBeGreaterThan(0)
    expect(flex[2]).toBeLessThan(1)
    expect(flex[3]).toBeCloseTo(flex[2]!)
  })

  it('changes only correction indices and never accumulates or moves the bones', () => {
    const asset = syntheticBodyAsset()
    const root = new Group()
    const body = new Mesh(new BufferGeometry(), new MeshBasicMaterial())
    const cloth = new Mesh(new BufferGeometry(), new MeshBasicMaterial())
    const names = [...SHAPE_NAMES, ...asset.pose_correctives.map(({ name }) => name)]
    body.morphTargetDictionary = Object.fromEntries(names.map((name, i) => [name, i]))
    body.morphTargetInfluences = [...compositionWeights({ fat: 0.3, muscle: 0.7 }), 0, 0, 0, 0]
    cloth.morphTargetDictionary = Object.fromEntries(SHAPE_NAMES.map((name, i) => [name, i]))
    cloth.morphTargetInfluences = [...body.morphTargetInfluences.slice(0, 30)]
    root.add(body, cloth)
    const bones = asset.pose_correctives.map((correction) => {
      const bone = new Bone()
      bone.name = PropertyBinding.sanitizeNodeName(correction.bone)
      root.add(bone)
      return bone
    })
    const driver = createPoseCorrections(root, [body, cloth], asset)
    bones.forEach((bone, i) => {
      const correction = asset.pose_correctives[i]!
      bone.quaternion.setFromAxisAngle(
        new Vector3(...correction.axis),
        correction.sign * correction.end_radians,
      )
    })
    const bodyWeights = [...body.morphTargetInfluences.slice(0, 30)]
    const rotations = bones.map((bone) => bone.quaternion.clone())
    for (let i = 0; i < 100; i++) driver.apply({ fat: 0.3, muscle: 0.7 })
    expect(body.morphTargetInfluences.slice(0, 30)).toEqual(bodyWeights)
    body.morphTargetInfluences.slice(30).forEach((weight) => expect(weight).toBeCloseTo(1))
    expect(bodyWeights.reduce((sum, value) => sum + value, 0)).toBeCloseTo(1)
    expect(cloth.morphTargetInfluences).toEqual(bodyWeights)
    bones.forEach((bone, i) => expect(bone.quaternion.toArray()).toEqual(rotations[i]!.toArray()))
    driver.apply({ fat: 1, muscle: 0 })
    expect(body.morphTargetInfluences.slice(0, 30)).toEqual(bodyWeights)
    body.morphTargetInfluences.slice(30).forEach((weight) => expect(weight).toBeCloseTo(1))
    bones.forEach((bone) => bone.quaternion.identity())
    driver.apply({ fat: 1, muscle: 0 })
    expect(body.morphTargetInfluences.slice(30)).toEqual([0, 0, 0, 0])
  })
})
