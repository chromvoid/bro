import { describe, expect, it, vi } from 'vitest'
import {
  AnimationClip,
  Bone,
  BufferGeometry,
  Color,
  DoubleSide,
  Group,
  Mesh,
  MeshBasicMaterial,
  MeshStandardMaterial,
  MeshStandardNodeMaterial,
  NumberKeyframeTrack,
  Quaternion,
  QuaternionKeyframeTrack,
  Texture,
  Vector2,
  Vector3,
} from 'three/webgpu'
import { normalView, vec3 } from 'three/tsl'
import { GLTFLoader } from 'three/addons/loaders/GLTFLoader.js'
import {
  anatomyNormalStrength,
  authoredSurfaceMaterial,
  createPoseBlender,
  createSurfaceLayer,
  interpolateWeights,
  poseClips,
  previewCanvasSize,
  sourceSurfaceNode,
} from './renderer'
import { syntheticBodyAsset } from './assetFixture'
import { CORRECTION_NAMES } from './assetContract'
import {
  BODY_NORMAL_STRENGTH,
  COMPOSITION_PRESETS,
  compositionWeights,
  interpolateComposition,
  normalizeComposition,
  SHAPE_NAMES,
} from './morphology'
import { advanceBreathPhase, advanceMotionTime, motionCharacter, sampleMotion } from './motion'
import { createTextureResources } from './textures'

describe('local character prototype contract', () => {
  it('resolves both material primitives through their actual GLTF source node', async () => {
    const asset = syntheticBodyAsset()
    asset.surfaces.push({
      node: 'InBodyCharacter',
      material: 'Authored neck skin',
      role: 'head_skin',
    })
    const targetNames = [...SHAPE_NAMES, ...CORRECTION_NAMES]
    const primitive = (material: number) => ({
      attributes: { POSITION: 0, NORMAL: 0 },
      material,
      targets: targetNames.map(() => ({ POSITION: 0, NORMAL: 0 })),
    })
    // Bufferless accessors contain synthetic zero values; no files or URLs are loaded.
    const gltf = await new GLTFLoader().parseAsync(
      JSON.stringify({
        asset: { version: '2.0' },
        scene: 0,
        scenes: [{ nodes: [0] }],
        nodes: [{ name: 'InBodyCharacter', mesh: 0 }],
        meshes: [
          {
            name: 'Body primitives',
            primitives: [primitive(0), primitive(1)],
            extras: { targetNames },
            weights: targetNames.map((_, i) => Number(i === 12)),
          },
        ],
        materials: asset.surfaces.map(({ material }) => ({ name: material })),
        accessors: [
          {
            componentType: 5126,
            type: 'VEC3',
            count: 3,
            min: [0, 0, 0],
            max: [0, 0, 0],
          },
        ],
      }),
      '',
    )
    const node = gltf.scene.getObjectByName('InBodyCharacter')!
    expect(node).toBeInstanceOf(Group)
    const primitives = node.children.filter((object) => object instanceof Mesh)
    expect(primitives).toHaveLength(2)
    const roles = primitives.map((primitive) => {
      const sourceNode = sourceSurfaceNode(primitive, gltf.parser)
      expect(sourceNode).toBe('InBodyCharacter')
      expect(primitive.morphTargetInfluences).toHaveLength(34)
      expect(Object.keys(primitive.morphTargetDictionary!)).toEqual(targetNames)
      return asset.surfaces.find(
        ({ node, material }) =>
          node === sourceNode && material === (primitive.material as MeshStandardMaterial).name,
      )!.role
    })
    expect(roles).toEqual(['skin', 'head_skin'])
    for (const primitive of primitives) {
      primitive.geometry.dispose()
      ;(primitive.material as MeshStandardMaterial).dispose()
    }
  })

  it.each(['head_skin', 'eye', 'hair'] as const)(
    'retains authored normal, PBR and alpha maps for %s without sharing mutable material values',
    (role) => {
      const mapNames = [
        'map',
        'normalMap',
        'roughnessMap',
        'metalnessMap',
        'aoMap',
        'emissiveMap',
        'alphaMap',
      ] as const
      const source = new MeshStandardMaterial({
        normalScale: new Vector2(0.65, 0.8),
        roughness: 0.72,
        metalness: 0.04,
        aoMapIntensity: 0.9,
        emissiveIntensity: 0.2,
        transparent: true,
        opacity: 0.85,
        alphaTest: 0.4,
        depthWrite: false,
        vertexColors: true,
        side: DoubleSide,
      })
      source.color.setScalar(0.3)
      source.emissive.setScalar(0.1)
      for (const name of mapNames) source[name] = new Texture()
      const material = authoredSurfaceMaterial(source, role)
      for (const name of mapNames) expect(material[name]).toBe(source[name])
      expect(material.normalNode).toBeNull()
      expect(material.normalScale.toArray()).toEqual([0.65, 0.8])
      expect(material.normalScale).not.toBe(source.normalScale)
      expect(material.color).not.toBe(source.color)
      expect(material.color.equals(source.color)).toBe(true)
      expect(material.emissive.equals(source.emissive)).toBe(true)
      expect(material.roughness).toBe(0.72)
      expect(material.metalness).toBe(0.04)
      expect(material.aoMapIntensity).toBe(0.9)
      expect(material.emissiveIntensity).toBe(0.2)
      expect(material.transparent).toBe(true)
      expect(material.opacity).toBe(0.85)
      expect(material.alphaTest).toBe(0.4)
      expect(material.depthWrite).toBe(false)
      expect(material.vertexColors).toBe(true)
      expect(material.side).toBe(DoubleSide)
      material.normalScale.setScalar(0.1)
      expect(source.normalScale.toArray()).toEqual([0.65, 0.8])
      material.dispose()
      source.dispose()
      for (const name of mapNames) source[name]!.dispose()
    },
  )

  it('keeps the established body ambient occlusion strength', () => {
    const source = new MeshStandardMaterial({ aoMapIntensity: 0.9 })
    const material = authoredSurfaceMaterial(source, 'skin')
    expect(material.aoMapIntensity).toBe(0.4)
    material.dispose()
    source.dispose()
  })

  it.each(['skin', 'head_skin'] as const)(
    'shows actual geometry for %s and restores its authored surface without releasing maps',
    (role) => {
      const mapNames = [
        'map',
        'normalMap',
        'roughnessMap',
        'metalnessMap',
        'aoMap',
        'emissiveMap',
        'alphaMap',
      ] as const
      const source = new MeshStandardMaterial({
        normalScale: new Vector2(0.65, 0.8),
        roughness: 0.72,
        metalness: 0.04,
        aoMapIntensity: 0.9,
        emissiveIntensity: 0.2,
        transparent: true,
        opacity: 0.85,
        alphaTest: 0.4,
        vertexColors: true,
      })
      source.color.setScalar(0.3)
      source.emissive.setScalar(0.1)
      const resources = createTextureResources()
      const disposals = mapNames.map((name) => {
        const texture = new Texture()
        source[name] = texture
        resources.track(texture)
        return vi.spyOn(texture, 'dispose')
      })
      const material = authoredSurfaceMaterial(source, role)
      expect(material).toBeInstanceOf(MeshStandardNodeMaterial)
      // Composition relief and the subtle appearance rim belong only to body skin.
      if (role === 'skin') {
        material.normalNode = normalView
        material.emissiveNode = vec3(0.01)
      }
      const normalNode = material.normalNode
      const emissiveNode = material.emissiveNode
      const formColor = new Color().setScalar(0.5)
      const layer = createSurfaceLayer(material, role, formColor)!
      const version = material.version
      layer.setLayer('form')
      expect(material.version).toBe(version + 1)
      expect(material.color.equals(formColor)).toBe(true)
      expect(material.roughness).toBe(0.8)
      expect(material.metalness).toBe(0)
      expect(material.emissive.toArray()).toEqual([0, 0, 0])
      expect(material.emissiveIntensity).toBe(0)
      expect(material.vertexColors).toBe(false)
      expect(material.normalNode).toBeNull()
      expect(material.emissiveNode).toBeNull()
      expect(material.transparent).toBe(false)
      expect(material.opacity).toBe(1)
      expect(material.alphaTest).toBe(0)
      for (const name of mapNames) expect(material[name]).toBeNull()
      layer.setLayer('form')
      expect(material.version).toBe(version + 1)
      const tint = new Color().setScalar(0.8)
      layer.setAppearance(tint)
      expect(material.color.equals(formColor)).toBe(true)
      expect(material.version).toBe(version + 1)
      layer.setLayer('surface')
      expect(material.version).toBe(version + 2)
      layer.setLayer('surface')
      expect(material.version).toBe(version + 2)
      const expected = source.color.clone()
      if (role === 'skin') expected.lerp(tint, 0.16)
      expect(material.color.equals(expected)).toBe(true)
      for (const name of mapNames) expect(material[name]).toBe(source[name])
      expect(material.normalNode).toBe(normalNode)
      expect(material.emissiveNode).toBe(emissiveNode)
      expect(material.normalScale.toArray()).toEqual([0.65, 0.8])
      expect(material.normalScale).not.toBe(source.normalScale)
      expect(material.roughness).toBe(source.roughness)
      expect(material.metalness).toBe(source.metalness)
      expect(material.aoMapIntensity).toBe(role === 'skin' ? 0.4 : 0.9)
      expect(material.emissive.equals(source.emissive)).toBe(true)
      expect(material.emissiveIntensity).toBe(source.emissiveIntensity)
      expect(material.vertexColors).toBe(true)
      expect(material.transparent).toBe(true)
      expect(material.opacity).toBe(0.85)
      expect(material.alphaTest).toBe(0.4)
      layer.setAppearance(null)
      expect(material.color.equals(source.color)).toBe(true)
      expect(source.normalScale.toArray()).toEqual([0.65, 0.8])
      // Disposal while maps are hidden still belongs to the shared resource registry.
      layer.setLayer('form')
      material.dispose()
      source.dispose()
      for (const disposed of disposals) expect(disposed).not.toHaveBeenCalled()
      resources.dispose()
      resources.dispose()
      for (const disposed of disposals) expect(disposed).toHaveBeenCalledTimes(1)
    },
  )

  it.each(['skin', 'head_skin'] as const)(
    'invalidates the %s shader once per layer transition when no alpha test changes',
    (role) => {
      const source = new MeshStandardMaterial({ normalMap: new Texture(), alphaTest: 0 })
      const material = authoredSurfaceMaterial(source, role)
      if (role === 'skin') material.normalNode = normalView
      const normalNode = material.normalNode
      const layer = createSurfaceLayer(material, role, new Color().setScalar(0.5))!
      const version = material.version
      layer.setLayer('form')
      expect(material.alphaTest).toBe(0)
      expect(material.normalMap).toBeNull()
      expect(material.normalNode).toBeNull()
      expect(material.version).toBe(version + 1)
      layer.setLayer('form')
      expect(material.version).toBe(version + 1)
      layer.setLayer('surface')
      expect(material.alphaTest).toBe(0)
      expect(material.normalMap).toBe(source.normalMap)
      expect(material.normalNode).toBe(normalNode)
      expect(material.version).toBe(version + 2)
      layer.setLayer('surface')
      expect(material.version).toBe(version + 2)
      material.dispose()
      source.dispose()
      source.normalMap!.dispose()
    },
  )

  it.each(['eye', 'hair', 'cloth', 'accessory'] as const)(
    'keeps %s authored during anatomical inspection',
    (role) => {
      const source = new MeshStandardMaterial({ normalMap: new Texture(), vertexColors: true })
      const material = authoredSurfaceMaterial(source, role)
      const version = material.version
      expect(createSurfaceLayer(material, role, new Color().setScalar(0.5))).toBeNull()
      expect(material.version).toBe(version)
      expect(material.normalMap).toBe(source.normalMap)
      expect(material.normalNode).toBeNull()
      expect(material.vertexColors).toBe(true)
      material.dispose()
      source.dispose()
      source.normalMap!.dispose()
    },
  )

  it('blends authored full physiques without overshoot or negative influences', () => {
    for (const from of [
      [1, 0, 0],
      [0.2, 0.3, 0.5],
    ] as [number, number, number][]) {
      let prior = 0
      for (let step = 0; step <= 100; step++) {
        const weights = interpolateWeights(from, [0, 1, 0], step / 100)
        expect(weights.reduce((sum, value) => sum + value, 0)).toBeCloseTo(1)
        expect(weights.every((value) => value >= 0 && value <= 1)).toBe(true)
        expect(weights[1]).toBeGreaterThanOrEqual(prior)
        prior = weights[1]
      }
    }
  })

  it('samples real held bone poses, crossfades quaternions and leaves morphology unchanged', () => {
    const root = new Group()
    const bone = new Bone()
    bone.name = 'upper_arm'
    const body = new Mesh(new BufferGeometry(), new MeshBasicMaterial())
    body.morphTargetInfluences = [0, 1, 0]
    root.add(bone, body)
    const axis = new Vector3(0, 0, 1)
    const clips = ['relaxed', 'flex', 'open'].map((name, index) => {
      const q = new Quaternion().setFromAxisAngle(axis, (index * Math.PI) / 2)
      return new AnimationClip(name, 1, [
        new QuaternionKeyframeTrack(
          'upper_arm.quaternion',
          [0, 1],
          [...q.toArray(), ...q.toArray()],
        ),
      ])
    })
    const controller = createPoseBlender(root, clips)
    controller.apply([1, 0, 0])
    expect(bone.quaternion.angleTo(new Quaternion())).toBeCloseTo(0)
    controller.apply([0.5, 0.5, 0])
    expect(bone.quaternion.angleTo(new Quaternion())).toBeCloseTo(Math.PI / 4)
    controller.apply([0, 0, 1])
    expect(bone.quaternion.angleTo(new Quaternion())).toBeCloseTo(Math.PI)
    expect(body.morphTargetInfluences).toEqual([0, 1, 0])
    // Real PropertyMixer caches held transforms and skips unchanged writes.
    // Repeated procedural offsets must not integrate into the next base pose.
    const delta = new Quaternion().setFromAxisAngle(axis, 0.01)
    for (let frame = 0; frame < 1000; frame++) {
      controller.apply([0, 1, 0])
      bone.quaternion.multiply(delta)
    }
    expect(bone.quaternion.angleTo(new Quaternion())).toBeCloseTo(Math.PI / 2 + 0.01)
    controller.apply([1, 0, 0])
    expect(bone.quaternion.angleTo(new Quaternion())).toBeCloseTo(0)
    // There is no persistent mixer time or animation clock after a held pose is applied.
    expect(controller.mixer.time).toBe(0)
    controller.mixer.stopAllAction()
    controller.mixer.uncacheRoot(root)
  })

  it('rejects animation that could overwrite the selected physique', () => {
    const track = new NumberKeyframeTrack('body.morphTargetInfluences[0]', [0, 1], [1, 0])
    expect(() =>
      poseClips(['relaxed', 'flex', 'open'].map((name) => new AnimationClip(name, 1, [track]))),
    ).toThrow('skeleton-only')
    expect(() => poseClips([])).toThrow('skeleton-only')
  })

  it('bounds GPU pixel allocation while preserving viewport shape', () => {
    expect(previewCanvasSize(400, 800, 3)).toEqual({ width: 640, height: 1280 })
    expect(previewCanvasSize(2000, 1000, 3)).toEqual({ width: 1600, height: 800 })
    expect(previewCanvasSize(0, 0, Number.NaN)).toEqual({ width: 1, height: 1 })
  })

  it('removes relief in volume-only mode and restores a stable body surface strength', () => {
    expect(anatomyNormalStrength('form')).toBe(0)
    expect(anatomyNormalStrength('surface')).toBe(BODY_NORMAL_STRENGTH)
  })

  it('interpolates all 30 authored shapes with no more than four convex influences', () => {
    for (let fat = 0; fat <= 100; fat++)
      for (let muscle = 0; muscle <= 100; muscle++) {
        const weights = compositionWeights({ fat: fat / 100, muscle: muscle / 100 })
        expect(weights).toHaveLength(30)
        expect(weights.filter((value) => value > 0).length).toBeLessThanOrEqual(4)
        expect(weights.every((value) => value >= 0 && value <= 1)).toBe(true)
        expect(weights.reduce((sum, value) => sum + value, 0)).toBeCloseTo(1)
      }
    expect(compositionWeights({ fat: 1, muscle: 1 })[29]).toBe(1)
    expect(compositionWeights({ fat: 0, muscle: 0 })[0]).toBe(1)
    expect(normalizeComposition({ fat: Infinity, muscle: -4 })).toEqual({ fat: 0.4, muscle: 0 })
  })

  it('keeps composition changes and motion amplitudes continuous across shape grid boundaries', () => {
    for (const fat of [0.2, 0.4, 0.6, 0.8]) {
      const left = compositionWeights({ fat: fat - 1e-6, muscle: 0.51 })
      const right = compositionWeights({ fat: fat + 1e-6, muscle: 0.51 })
      expect(Math.max(...left.map((value, i) => Math.abs(value - right[i]!)))).toBeLessThan(0.00002)
    }
    const from = COMPOSITION_PRESETS.soft
    const to = COMPOSITION_PRESETS.athletic
    expect(interpolateComposition(from, to, 0)).toEqual(from)
    expect(interpolateComposition(from, to, 1).fat).toBeCloseTo(to.fat)
    const sample = sampleMotion(10000, { fat: 0.3, muscle: 0.8 }, 1.2)
    const next = sampleMotion(10000, { fat: 0.300001, muscle: 0.800001 }, 1.2)
    expect(Math.abs(sample.chestExpansion - next.chestExpansion)).toBeLessThan(0.000001)
    expect(Math.abs(sample.torsoYaw - next.torsoYaw)).toBeLessThan(0.000001)
  })

  it('freezes active time and breathing during pause and bounds late frames on resume', () => {
    expect(advanceMotionTime(12, 60, false)).toBe(12)
    expect(advanceMotionTime(12, 60, true)).toBe(12.1)
    expect(advanceMotionTime(12, Number.NaN, true)).toBe(12)
    expect(advanceBreathPhase(1.2, 60, COMPOSITION_PRESETS.athletic, false)).toBe(1.2)
    const phase = advanceBreathPhase(1.2, 0.03, COMPOSITION_PRESETS.soft, true)
    expect(phase).toBeGreaterThan(1.2)
    expect(phase).toBeLessThan(1.25)
  })

  it('keeps ambient movement deterministic and small without synthesizing static pose pulses', () => {
    expect(sampleMotion(9, COMPOSITION_PRESETS.athletic)).toEqual(
      sampleMotion(9, COMPOSITION_PRESETS.athletic),
    )
    expect(motionCharacter(COMPOSITION_PRESETS.athletic).style).toBe('stage')
    expect(motionCharacter(COMPOSITION_PRESETS.soft).style).toBe('calm')
    for (let t = 0; t < 70; t += 0.2) {
      const soft = sampleMotion(t, COMPOSITION_PRESETS.soft)
      expect(soft.pose).toEqual([1, 0, 0])
      expect(sampleMotion(t, COMPOSITION_PRESETS.athletic).pose).toEqual([1, 0, 0])
      const athletic = sampleMotion(t, COMPOSITION_PRESETS.athletic)
      expect(athletic.pose.reduce((sum, value) => sum + value, 0)).toBeCloseTo(1)
      expect(athletic.pose.every((value) => value >= 0)).toBe(true)
      expect(Math.abs(athletic.chestExpansion)).toBeLessThan(0.02)
      expect(Math.abs(athletic.torsoRoll)).toBeLessThan(0.005)
    }
  })
})
