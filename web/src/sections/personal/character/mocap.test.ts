import { describe, expect, it } from 'vitest'
import {
  AnimationClip,
  Bone,
  BufferGeometry,
  Group,
  Mesh,
  MeshBasicMaterial,
  NumberKeyframeTrack,
  Quaternion,
  QuaternionKeyframeTrack,
  Vector3,
  VectorKeyframeTrack,
} from 'three/webgpu'
import { createMocapPlayer, MocapTimeline } from './mocap'

function capturedMotion() {
  const model = new Group()
  const armature = new Group()
  armature.name = 'Armature'
  const pelvis = new Bone()
  pelvis.name = 'pelvis'
  const leg = new Bone()
  leg.name = 'leg'
  pelvis.add(leg)
  armature.add(pelvis)
  const surface = new Mesh(new BufferGeometry(), new MeshBasicMaterial())
  surface.name = 'body'
  surface.morphTargetInfluences = [0.1, 0.3, 0.6]
  model.add(armature, surface)
  const rotation = new Quaternion().setFromAxisAngle(new Vector3(0, 0, 1), 0.4)
  const clip = new AnimationClip('cmu_flexing', 2.75, [
    new VectorKeyframeTrack('Armature.position', [0, 2.75], [0, 0, 0, 0.5, 0, -0.2]),
    new VectorKeyframeTrack('pelvis.position', [0, 2.75], [0, 1, 0, 0, 0.94, 0]),
    new QuaternionKeyframeTrack('leg.quaternion', [0, 2.75], [0, 0, 0, 1, ...rotation.toArray()]),
  ])
  return { model, armature, pelvis, leg, surface, clip }
}

describe('recorded full-body motion preview', () => {
  it('preserves captured root translation, pelvis and leg motion at original clip time', () => {
    const { model, armature, pelvis, leg, surface, clip } = capturedMotion()
    const player = createMocapPlayer(model, [clip])
    expect(player.duration).toBe(2.75)
    player.sample(1.375)
    expect(armature.position.x).toBeCloseTo(0.25)
    expect(armature.position.z).toBeCloseTo(-0.1)
    expect(pelvis.position.y).toBeCloseTo(0.97)
    expect(leg.quaternion.angleTo(new Quaternion())).toBeCloseTo(0.2)
    expect(surface.morphTargetInfluences).toEqual([0.1, 0.3, 0.6])
    player.sample(100)
    expect(armature.position.x).toBeCloseTo(0.5)
    expect(pelvis.position.y).toBeCloseTo(0.94)
    expect(leg.quaternion.angleTo(new Quaternion())).toBeCloseTo(0.4)
    player.dispose()
  })

  it('restores an exact captured frame after another mode modified the rig, without drift', () => {
    const { model, armature, pelvis, leg, clip } = capturedMotion()
    const player = createMocapPlayer(model, [clip])
    player.sample(1.375)
    for (let frame = 0; frame < 100; frame++) {
      armature.position.x = 12
      pelvis.position.y = -10
      leg.quaternion.set(0, 1, 0, 0)
      player.sample(1.375)
      expect(armature.position.x).toBeCloseTo(0.25)
      expect(pelvis.position.y).toBeCloseTo(0.97)
      expect(leg.quaternion.angleTo(new Quaternion())).toBeCloseTo(0.2)
    }
    player.reset()
    expect(armature.position.x).toBe(0)
    player.sample(1.375)
    expect(armature.position.x).toBeCloseTo(0.25)
    player.sample(0)
    expect(pelvis.position.y).toBeCloseTo(1)
    player.dispose()
  })

  it('rejects missing target bones and morphology tracks instead of silently dropping them', () => {
    const { model } = capturedMotion()
    for (const track of [
      new NumberKeyframeTrack('body.morphTargetInfluences[0]', [0, 1], [0, 1]),
      new VectorKeyframeTrack('missing_bone.position', [0, 1], [0, 0, 0, 1, 1, 1]),
    ]) {
      expect(() =>
        createMocapPlayer(model, [new AnimationClip('cmu_flexing', 1, [track])]),
      ).toThrow('skeleton-only mocap')
    }
  })

  it('plays once, holds the final frame and restarts only on an explicit play', () => {
    const timeline = new MocapTimeline(2.75)
    timeline.play()
    for (let frame = 0; frame < 100; frame++) timeline.advance(0.1, true)
    expect(timeline.elapsed).toBe(2.75)
    expect(timeline.playing).toBe(false)
    timeline.advance(0.1, true)
    expect(timeline.elapsed).toBe(2.75)
    timeline.play()
    expect(timeline.elapsed).toBe(0)
    expect(timeline.playing).toBe(true)
  })

  it('pauses on scrubbing, does not advance while hidden or paused, and bounds a resumed frame', () => {
    const timeline = new MocapTimeline(15.566667)
    timeline.play()
    timeline.advance(0.04, true)
    expect(timeline.elapsed).toBe(0.04)
    timeline.advance(60, false)
    expect(timeline.elapsed).toBe(0.04)
    timeline.seek(8.23)
    expect(timeline.playing).toBe(false)
    timeline.advance(1, true)
    expect(timeline.elapsed).toBe(8.23)
    timeline.play()
    timeline.advance(60, true)
    expect(timeline.elapsed).toBeCloseTo(8.33)
    timeline.seek(-1)
    expect(timeline.elapsed).toBe(0)
    timeline.seek(100)
    expect(timeline.elapsed).toBe(timeline.duration)
  })

  it('loops an idle at source speed, preserving fractional time across the seam', () => {
    const timeline = new MocapTimeline(2.5, true)
    timeline.seek(2.45)
    timeline.play()
    timeline.advance(0.08, true)
    expect(timeline.elapsed).toBeCloseTo(0.03)
    expect(timeline.playing).toBe(true)
    timeline.advance(60, false)
    expect(timeline.elapsed).toBeCloseTo(0.03)
    timeline.pause()
    timeline.advance(0.04, true)
    expect(timeline.elapsed).toBeCloseTo(0.03)
    timeline.play()
    for (let frame = 0; frame < 600; frame++) timeline.advance(1 / 30, true)
    expect(timeline.elapsed).toBeCloseTo(0.03)
    expect(timeline.playing).toBe(true)
  })

  it('samples a closed source idle around the loop seam without changing independently edited morphology', () => {
    const { model, armature, surface } = capturedMotion()
    const clip = new AnimationClip('game_idle', 2.5, [
      new VectorKeyframeTrack('Armature.position', [0, 1.25, 2.5], [0, 0, 0, 0.02, 0, 0, 0, 0, 0]),
    ])
    const player = createMocapPlayer(model, [clip], 'game_idle')
    const timeline = new MocapTimeline(player.duration, true)
    timeline.seek(2.49)
    timeline.play()
    player.sample(timeline.elapsed)
    const before = armature.position.clone()
    surface.morphTargetInfluences = [1, 0, 0]
    timeline.advance(0.02, true)
    player.sample(timeline.elapsed)
    expect(armature.position.distanceTo(before)).toBeLessThan(1e-7)
    expect(surface.morphTargetInfluences).toEqual([1, 0, 0])
    expect(timeline.playing).toBe(true)
    player.dispose()
  })
})
