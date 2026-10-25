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
import { createScenarioPlayer } from './renderer'
import { COMPOSITION_PRESETS } from './morphology'
import {
  ScenarioDirector,
  SCENARIO_DURATIONS,
  SCENARIO_NAMES,
  nextAutomaticScenario,
  scenarioPhase,
  type Scenario,
} from './scenarios'

const options = {
  enabled: true,
  allowStart: true,
  automatic: true,
  composition: COMPOSITION_PRESETS.athletic,
}
const advance = (director: ScenarioDirector, seconds: number, overrides = {}) => {
  for (let step = 0; step < Math.round(seconds * 10); step++)
    director.update(0.1, { ...options, ...overrides })
}

describe('authored scenario direction', () => {
  it('queues only the latest scene and completes the current authored exit before switching', () => {
    const director = new ScenarioDirector()
    director.select('athletic', options.composition)
    director.update(0, options)
    advance(director, 3)
    expect(director.active).toBe('athletic')
    const elapsed = director.elapsed
    director.select('calm', options.composition)
    director.select('stage', options.composition)
    expect(director.active).toBe('athletic')
    expect(director.elapsed).toBe(elapsed)
    expect(director.queued).toBe('stage')
    advance(director, 11)
    expect(director.active).toBeNull()
    expect(director.last).toBe('athletic')
    expect(director.queued).toBe('stage')
    advance(director, 0.6)
    expect(director.active).toBeNull()
    advance(director, 0.1)
    expect(director.active).toBe('stage')
    expect(director.elapsed).toBe(0)
  })

  it('runs a queued one-shot after the accepted scene and then stays at breathing idle', () => {
    const director = new ScenarioDirector()
    director.select('stage', options.composition)
    director.update(0, options)
    advance(director, 3)
    director.play('athletic', true)
    expect(director.stopping).toBe(true)
    expect(director.active).toBe('stage')
    expect(director.queued).toBe('athletic')
    advance(director, 15)
    expect(director.last).toBe('stage')
    expect(director.active).toBeNull()
    advance(director, 0.7)
    expect(director.active).toBe('athletic')
    advance(director, 14)
    expect(director.last).toBe('athletic')
    expect(director.active).toBeNull()
    expect(director.selection).toBe('stage')
    advance(director, 100)
    expect(director.active).toBeNull()
    director.select('auto', options.composition)
    director.update(0, options)
    expect(director.active).toBe('stage')
    expect(director.stopping).toBe(false)
  })

  it('lets a later repeat selection replace a pending one-shot without stopping at the old exit', () => {
    const director = new ScenarioDirector()
    director.play('calm', true)
    director.update(0, options)
    advance(director, 2)
    director.select('athletic', options.composition)
    expect(director.stopping).toBe(false)
    advance(director, 8.7)
    expect(director.active).toBe('athletic')
    director.stopAfterExit()
    expect(director.queued).toBeNull()
    advance(director, 14)
    advance(director, 50)
    expect(director.active).toBeNull()
  })

  it('preserves phase and queue during pause and delays a new scene during interaction', () => {
    const director = new ScenarioDirector()
    director.play('stage')
    advance(director, 1, { allowStart: false })
    expect(director.active).toBeNull()
    expect(director.queued).toBe('stage')
    director.update(0, options)
    advance(director, 4)
    director.play('calm')
    const snapshot = JSON.stringify(director)
    advance(director, 100, { enabled: false })
    expect(JSON.stringify(director)).toBe(snapshot)
    director.update(100, options)
    expect(director.elapsed).toBeCloseTo(4.1)
    expect(director.queued).toBe('calm')
  })

  it('keeps automatic choices varied and appropriate without restricting explicit choices', () => {
    let last: Scenario | null = null
    const order = []
    for (let i = 0; i < 6; i++) {
      const next = nextAutomaticScenario(COMPOSITION_PRESETS.athletic, last)
      expect(next).not.toBe(last)
      order.push(next)
      last = next
    }
    expect(order).toEqual(['stage', 'calm', 'athletic', 'stage', 'calm', 'athletic'])
    expect(nextAutomaticScenario(COMPOSITION_PRESETS.soft, 'calm')).toBe('calm')
    const director = new ScenarioDirector()
    director.select('stage', COMPOSITION_PRESETS.soft)
    director.update(0, {
      ...options,
      automatic: false,
      composition: COMPOSITION_PRESETS.soft,
    })
    expect(director.active).toBe('stage')
  })

  it('inserts breathing rest between calm repeats and honours safe stop until resumed', () => {
    const director = new ScenarioDirector()
    director.select('calm', COMPOSITION_PRESETS.soft)
    director.update(0, { ...options, composition: COMPOSITION_PRESETS.soft })
    advance(director, 10, { composition: COMPOSITION_PRESETS.soft })
    expect(director.rest).toBe(12)
    advance(director, 5, { composition: COMPOSITION_PRESETS.soft })
    expect(director.active).toBeNull()
    advance(director, 7.2, { composition: COMPOSITION_PRESETS.soft })
    expect(director.active).toBe('calm')
    director.stopAfterExit()
    advance(director, 11, { composition: COMPOSITION_PRESETS.soft })
    expect(director.active).toBeNull()
    advance(director, 40)
    expect(director.active).toBeNull()
    director.resume()
    advance(director, 1.6)
    expect(director.active).toBe('calm')
    expect(scenarioPhase('stage', 0)).toBe('entry')
    expect(scenarioPhase('stage', 8)).toBe('motion')
    expect(scenarioPhase('stage', 16)).toBe('exit')
  })
})

function fixture() {
  const root = new Group()
  const chest = new Bone()
  chest.name = 'chest'
  const foot = new Bone()
  foot.name = 'foot'
  foot.position.set(0.25, 0, 0.125)
  root.add(chest, foot)
  const body = new Mesh(new BufferGeometry(), new MeshBasicMaterial())
  body.morphTargetInfluences = Array.from({ length: 30 }, (_, i) => (i === 29 ? 1 : 0))
  root.add(body)
  const clips = SCENARIO_NAMES.map((name, index) => {
    const duration = SCENARIO_DURATIONS[name]
    const identity = new Quaternion()
    const q = new Quaternion().setFromAxisAngle(new Vector3(0, 0, 1), (index + 1) * 0.25)
    return new AnimationClip(`scenario_${name}`, duration, [
      new QuaternionKeyframeTrack(
        'chest.quaternion',
        [0, duration / 2, duration],
        [...identity.toArray(), ...q.toArray(), ...identity.toArray()],
      ),
      new VectorKeyframeTrack(
        'foot.position',
        [0, duration],
        [...foot.position.toArray(), ...foot.position.toArray()],
      ),
    ])
  })
  return { root, chest, foot, body, clips }
}

describe('temporal skeleton playback', () => {
  it('plays all three real timelines rather than the final held frame, with exact relaxed endpoints', () => {
    const { root, chest, foot, body, clips } = fixture()
    const player = createScenarioPlayer(root, clips)
    const morphs = [...body.morphTargetInfluences!]
    const footPosition = foot.position.clone()
    for (const [index, name] of SCENARIO_NAMES.entries()) {
      player.sample(name, 0)
      expect(chest.quaternion.angleTo(new Quaternion())).toBeCloseTo(0)
      player.sample(name, SCENARIO_DURATIONS[name] / 2)
      expect(chest.quaternion.angleTo(new Quaternion())).toBeCloseTo((index + 1) * 0.25)
      player.sample(name, SCENARIO_DURATIONS[name])
      expect(chest.quaternion.angleTo(new Quaternion())).toBeCloseTo(0)
    }
    expect(body.morphTargetInfluences).toEqual(morphs)
    expect(foot.position.equals(footPosition)).toBe(true)
    expect(player.mixer.time).toBe(0)
    player.dispose()
  })

  it('restores the same temporal frame without accumulating procedural offsets', () => {
    const { root, chest, clips } = fixture()
    const player = createScenarioPlayer(root, clips)
    const delta = new Quaternion().setFromAxisAngle(new Vector3(0, 0, 1), 0.01)
    for (let frame = 0; frame < 1000; frame++) {
      player.sample('athletic', 7)
      chest.quaternion.multiply(delta)
    }
    expect(chest.quaternion.angleTo(new Quaternion())).toBeCloseTo(0.51)
    player.sample('athletic', 14)
    expect(chest.quaternion.angleTo(new Quaternion())).toBeCloseTo(0)
    player.dispose()
  })

  it('rejects wrong durations, missing scenes and channels that could change body composition', () => {
    const { root, clips } = fixture()
    expect(() => createScenarioPlayer(root, clips.slice(1))).toThrow('scenario')
    const incorrectDuration = clips.map((clip) => clip.clone())
    incorrectDuration[0]!.duration = 1
    expect(() => createScenarioPlayer(root, incorrectDuration)).toThrow('scenario')
    const withMorph = clips.map((clip) => clip.clone())
    withMorph[0]!.tracks.push(
      new NumberKeyframeTrack('body.morphTargetInfluences[0]', [0, 10], [0, 1]),
    )
    expect(() => createScenarioPlayer(root, withMorph)).toThrow('skeleton-only')
  })
})
