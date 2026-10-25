import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  AnimationClip,
  Bone,
  BoxGeometry,
  DoubleSide,
  Float32BufferAttribute,
  FrontSide,
  Group,
  Mesh,
  MeshStandardMaterial,
  MeshStandardNodeMaterial,
  NoColorSpace,
  PropertyBinding,
  Quaternion,
  QuaternionKeyframeTrack,
  Skeleton,
  SkinnedMesh,
  SRGBColorSpace,
  Texture,
  Uint16BufferAttribute,
  Vector2,
  Vector3,
  type Object3D,
} from 'three/webgpu'
import {
  bodyCanvasSize,
  bodyMorphWeights,
  bodyScenarioSelection,
  createBodyRenderer,
  type BodyRendererState,
} from './bodyGpu'
import { neutralBodyVisual } from './bodyVisual'
import { SHAPE_NAMES } from './character/morphology'
import { SCENARIO_DURATIONS } from './character/scenarios'
import { createCharacterRenderer } from './character/renderer'
import { LOOKAROUND_LOOP_BLEND_SECONDS } from './character/lookaround'
import { syntheticBodyAsset } from './character/assetFixture'
import { CORRECTION_NAMES } from './character/assetContract'

const { state } = vi.hoisted(() => ({
  state: {
    engine: null as unknown,
    parse: vi.fn(),
    compile: vi.fn(),
    init: vi.fn(),
    webgpu: true,
  },
}))
// Lifecycle tests isolate rendering from browser-only CSS color conversion.
vi.mock('../../design/colors', () => ({ readCssSrgbColor: () => '#7f7f7f' }))
vi.mock('three/webgpu', async (importOriginal) => {
  const actual = await importOriginal<typeof import('three/webgpu')>()
  return {
    ...actual,
    WebGPURenderer: class {
      _animation = { stop: vi.fn() }
      _nodes = { nodeFrame: { update: vi.fn() } }
      shadowMap = {}
      backend = state.webgpu ? { isWebGPUBackend: true } : { isWebGLBackend: true }
      render = vi.fn()
      compileAsync = state.compile
      dispose = vi.fn().mockResolvedValue(undefined)
      setSize = vi.fn()
      onDeviceLost = vi.fn()
      onError = vi.fn()
      init = state.init
      constructor() {
        state.engine = this
      }
    },
  }
})
vi.mock('three/addons/loaders/GLTFLoader.js', () => ({
  GLTFLoader: class {
    parseAsync = state.parse
    register() {
      return this
    }
  },
}))
const engine = () =>
  state.engine as {
    render: ReturnType<typeof vi.fn>
    _animation: { stop: ReturnType<typeof vi.fn> }
    _nodes: { nodeFrame: { update: ReturnType<typeof vi.fn> } }
    dispose: ReturnType<typeof vi.fn>
    setSize: ReturnType<typeof vi.fn>
    onDeviceLost: () => void
    onError: () => void
  }
function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((done, fail) => {
    resolve = done
    reject = fail
  })
  return { promise, resolve, reject }
}
let frames: Map<number, FrameRequestCallback>
let frameId: number
let mesh: SkinnedMesh
let bone: Bone
let root: Group
let clips: AnimationClip[]
let now: number
let reliefMaps: Texture[]
let sourceNodes: { name: string }[]
let sourceAssociations: Map<Object3D, { nodes: number }>
function associateSourceNode(object: Object3D, name: string) {
  sourceAssociations.set(object, { nodes: sourceNodes.length })
  sourceNodes.push({ name })
}
function loadedBody() {
  return {
    scene: root,
    animations: clips,
    parser: {
      json: {
        nodes: sourceNodes,
        get textures() {
          return reliefMaps.map(() => ({}))
        },
      },
      associations: sourceAssociations,
      getDependencies: vi.fn().mockResolvedValue(reliefMaps),
    },
  }
}
function beardSurface(side: typeof FrontSide | typeof DoubleSide = FrontSide) {
  const diffuse = new Texture()
  diffuse.colorSpace = SRGBColorSpace
  const normal = new Texture()
  normal.colorSpace = NoColorSpace
  const roughness = new Texture()
  roughness.colorSpace = NoColorSpace
  // GLTFLoader clones maps for texture transforms/UV channels. Both resources
  // must be released, even though the clone shares the decoded image source.
  const diffuseClone = diffuse.clone()
  const source = new MeshStandardMaterial({
    name: 'Authored CC0 beard',
    map: diffuseClone,
    normalMap: normal,
    normalScale: new Vector2(0.5, 0.5),
    roughnessMap: roughness,
    metalnessMap: roughness,
    alphaTest: 0.35,
    opacity: 1,
    transparent: false,
    side,
  })
  const beard = new Mesh(new BoxGeometry(0.2, 0.3, 0.1), source)
  beard.name = 'SpartanBeard'
  associateSourceNode(beard, 'SpartanBeard')
  root.add(beard)
  root.userData.bodyAsset.surfaces.push({
    node: beard.name,
    material: source.name,
    role: 'hair',
  })
  reliefMaps.push(diffuse, normal, roughness)
  return { beard, source, diffuse, diffuseClone, normal, roughness }
}
function drawAt(time: number) {
  now = time
  const current = [...frames.values()]
  frames.clear()
  current.forEach((frame) => frame(time))
}
function advance(seconds: number) {
  for (let elapsed = 0; elapsed < seconds; elapsed += 0.04) drawAt(now + 40)
}
function setup() {
  const canvas = document.createElement('canvas')
  document.body.appendChild(canvas)
  vi.spyOn(canvas, 'getBoundingClientRect').mockReturnValue(new DOMRect(0, 0, 400, 600))
  const options = {
    visual: { ...neutralBodyVisual(), fat: 0.4, muscle: 0.5 },
    layer: 'composition' as const,
    motion: false,
    active: true,
    signal: new AbortController().signal,
    onFailure: vi.fn(),
    onState: vi.fn<(state: BodyRendererState) => void>(),
  }
  return { canvas, options }
}
function skeletonClip(name: string, duration: number, angle: number) {
  const q = new Quaternion().setFromAxisAngle(new Vector3(0, 0, 1), angle)
  return new AnimationClip(name, duration, [
    new QuaternionKeyframeTrack(
      'spine01.quaternion',
      [0, duration / 2, duration],
      [0, 0, 0, 1, ...q.toArray(), 0, 0, 0, 1],
    ),
  ])
}
beforeEach(() => {
  frames = new Map()
  frameId = 0
  now = 0
  state.webgpu = true
  state.init.mockReset().mockResolvedValue(undefined)
  state.compile.mockReset().mockResolvedValue(undefined)
  vi.spyOn(console, 'error').mockImplementation(() => undefined)
  sourceNodes = []
  sourceAssociations = new Map()
  const geometry = new BoxGeometry(1, 3, 0.5)
  const count = geometry.getAttribute('position').count
  geometry.setAttribute('skinIndex', new Uint16BufferAttribute(Array(count * 4).fill(0), 4))
  geometry.setAttribute(
    'skinWeight',
    new Float32BufferAttribute(
      Array.from({ length: count * 4 }, (_, index) => (index % 4 === 0 ? 1 : 0)),
      4,
    ),
  )
  mesh = new SkinnedMesh(geometry, new MeshStandardMaterial({ name: 'InBody sculpt / anatomy' }))
  mesh.name = 'InBodyCharacter'
  associateSourceNode(mesh, 'InBodyCharacter')
  bone = new Bone()
  bone.name = 'spine01'
  associateSourceNode(bone, 'spine01')
  mesh.add(bone)
  const correctionBones = syntheticBodyAsset().pose_correctives.map((correction) => {
    const joint = new Bone()
    joint.name = PropertyBinding.sanitizeNodeName(correction.bone)
    associateSourceNode(joint, correction.bone)
    mesh.add(joint)
    return joint
  })
  mesh.bind(new Skeleton([bone, ...correctionBones]))
  mesh.morphTargetDictionary = Object.fromEntries(
    [...SHAPE_NAMES, ...CORRECTION_NAMES].map((name, index) => [name, index]),
  )
  mesh.morphTargetInfluences = Array(34).fill(0)
  root = new Group()
  associateSourceNode(root, 'SyntheticBodyScene')
  root.add(mesh)
  root.userData.bodyAsset = syntheticBodyAsset()
  reliefMaps = Array.from({ length: 4 }, () => new Texture())
  clips = [
    ...['relaxed', 'flex', 'open'].map((name) => skeletonClip(name, 1, 0)),
    ...Object.entries(SCENARIO_DURATIONS).map(([name, duration]) =>
      skeletonClip(`scenario_${name}`, duration, 0.5),
    ),
  ]
  state.parse
    .mockReset()
    .mockResolvedValueOnce(loadedBody())
    .mockResolvedValue({
      scene: new Group(),
      animations: [skeletonClip('rokoko_lookaround', 27, 0.1)],
    })
  vi.stubGlobal(
    'fetch',
    vi.fn().mockResolvedValue({ ok: true, arrayBuffer: async () => new ArrayBuffer(0) }),
  )
  vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => {
    frames.set(++frameId, callback)
    return frameId
  })
  vi.stubGlobal('cancelAnimationFrame', (id: number) => frames.delete(id))
  vi.spyOn(performance, 'now').mockImplementation(() => now)
  vi.spyOn(window, 'devicePixelRatio', 'get').mockReturnValue(1)
})
afterEach(() => {
  document.body.replaceChildren()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('production morphology adapter', () => {
  it('keeps missing axes neutral and blends 30 shapes without extrapolation', () => {
    expect(bodyMorphWeights(null, null)[12]).toBe(1)
    expect(bodyMorphWeights(-5, 10)[4]).toBe(1)
    expect(bodyMorphWeights(NaN, Infinity)).toEqual(bodyMorphWeights(null, null))
    const weights = bodyMorphWeights(0.25, 0.6)
    expect(weights).toHaveLength(30)
    expect(weights.filter((value) => value > 0)).toHaveLength(4)
    expect(weights.reduce((a, b) => a + b)).toBeCloseTo(1)
  })
  it('caps resolution without style mutations', () => {
    expect(bodyCanvasSize(1200, 1600, 3)).toEqual({ width: 675, height: 900 })
    expect(bodyCanvasSize(400, 600, 3)).toEqual({ width: 600, height: 900 })
  })
  it('uses calm automatic motion for incomplete scans without overriding manual choices', () => {
    const visual = { ...neutralBodyVisual(), muscle: 1 }
    expect(bodyScenarioSelection('auto', visual)).toBe('calm')
    expect(bodyScenarioSelection('idle', visual)).toBe('idle')
    expect(bodyScenarioSelection('stage', visual)).toBe('stage')
    expect(bodyScenarioSelection('auto', { ...visual, fat: 0 })).toBe('auto')
  })
})
describe('shared character renderer lifecycle', () => {
  it.each([FrontSide, DoubleSide] as const)(
    'retains authored alpha-masked hair PBR maps and side %i through body changes',
    async (side) => {
      const { beard, source, diffuse, diffuseClone, normal, roughness } = beardSurface(side)
      const disposeTextures = [...reliefMaps, diffuseClone].map((texture) =>
        vi.spyOn(texture, 'dispose'),
      )
      const disposeSource = vi.spyOn(source, 'dispose')
      const { canvas, options } = setup()
      const renderer = await createBodyRenderer(canvas, options)
      expect(renderer).not.toBeNull()
      const converted: unknown = beard.material
      expect(converted).toBeInstanceOf(MeshStandardNodeMaterial)
      const material = converted as MeshStandardNodeMaterial
      const disposeMaterial = vi.spyOn(material, 'dispose')
      const assertAppearance = () => {
        expect(material.map).toBe(diffuseClone)
        expect(material.normalMap).toBe(normal)
        expect(material.normalScale.toArray()).toEqual([0.5, 0.5])
        expect(material.roughnessMap).toBe(roughness)
        expect(material.metalnessMap).toBe(roughness)
        expect(material.alphaTest).toBe(0.35)
        expect(material.transparent).toBe(false)
        expect(material.opacity).toBe(1)
        expect(material.side).toBe(side)
        expect(material.normalNode).toBeNull()
        expect(diffuse.colorSpace).toBe(SRGBColorSpace)
        expect(diffuseClone.colorSpace).toBe(SRGBColorSpace)
        expect(normal.colorSpace).toBe(NoColorSpace)
        expect(roughness.colorSpace).toBe(NoColorSpace)
      }
      assertAppearance()
      renderer!.setLayer('muscle')
      renderer!.setVisual({ ...options.visual, fat: 0.85, muscle: 1 })
      drawAt(40)
      assertAppearance()
      engine().onDeviceLost()
      renderer!.destroy()
      for (const dispose of disposeTextures) expect(dispose).toHaveBeenCalledOnce()
      expect(disposeSource).toHaveBeenCalledOnce()
      expect(disposeMaterial).toHaveBeenCalledOnce()
    },
  )

  it('releases additional hair maps and texture clones when material conversion rejects the model', async () => {
    const { source, diffuseClone } = beardSurface()
    const disposeTextures = [...reliefMaps, diffuseClone].map((texture) =>
      vi.spyOn(texture, 'dispose'),
    )
    const disposeSource = vi.spyOn(source, 'dispose')
    root.userData.bodyAsset.surfaces[1].material = 'Invalid beard material'
    const { canvas, options } = setup()
    expect(await createBodyRenderer(canvas, options)).toBeNull()
    expect(options.onFailure).toHaveBeenCalledOnce()
    for (const dispose of disposeTextures) expect(dispose).toHaveBeenCalledOnce()
    expect(disposeSource).toHaveBeenCalledOnce()
  })

  it('releases all additional maps when an authored hair shader fails compilation', async () => {
    const { source, diffuseClone } = beardSurface()
    const disposeTextures = [...reliefMaps, diffuseClone].map((texture) =>
      vi.spyOn(texture, 'dispose'),
    )
    const disposeSource = vi.spyOn(source, 'dispose')
    state.compile.mockRejectedValue(new Error('Hair shader unavailable'))
    const { canvas, options } = setup()
    expect(await createBodyRenderer(canvas, options)).toBeNull()
    expect(options.onFailure).toHaveBeenCalledOnce()
    for (const dispose of disposeTextures) expect(dispose).toHaveBeenCalledOnce()
    expect(disposeSource).toHaveBeenCalledOnce()
  })

  it('releases authored hair maps from a parse that arrives after its preview is aborted', async () => {
    const { source, diffuseClone, normal, roughness } = beardSurface()
    const pending = deferred<ReturnType<typeof loadedBody>>()
    state.parse.mockReset().mockReturnValue(pending.promise)
    const disposeTextures = [diffuseClone, normal, roughness].map((texture) =>
      vi.spyOn(texture, 'dispose'),
    )
    const disposeSource = vi.spyOn(source, 'dispose')
    const abort = new AbortController()
    const { canvas, options } = setup()
    const created = createBodyRenderer(canvas, { ...options, signal: abort.signal })
    await vi.waitFor(() => expect(state.parse).toHaveBeenCalledOnce())
    abort.abort()
    pending.resolve(loadedBody())
    expect(await created).toBeNull()
    expect(options.onFailure).not.toHaveBeenCalled()
    for (const dispose of disposeTextures) expect(dispose).toHaveBeenCalledOnce()
    expect(disposeSource).toHaveBeenCalledOnce()
    expect(frames.size).toBe(0)
  })

  it('renders a still frame with exact weights, no idle work and no inline styles', async () => {
    const { canvas, options } = setup()
    const renderer = await createBodyRenderer(canvas, options)
    expect(renderer?.mode).toBe('webgpu')
    drawAt(40)
    expect(engine().render).toHaveBeenCalledOnce()
    expect(engine()._animation.stop).toHaveBeenCalledOnce()
    expect(engine()._nodes.nodeFrame.update).toHaveBeenCalledOnce()
    expect(mesh.morphTargetInfluences![12]).toBe(1)
    expect(frames.size).toBe(0)
    renderer!.setVisual({ ...options.visual, fat: 1, muscle: 0 })
    drawAt(80)
    expect(mesh.morphTargetInfluences![25]).toBe(1)
    expect(frames.size).toBe(0)
    expect(engine().setSize).toHaveBeenCalledWith(400, 600, false)
    expect(canvas.hasAttribute('style')).toBe(false)
    renderer!.destroy()
  })
  it('loads only the active template and its idle, never the diagnostic CMU asset', async () => {
    const { canvas, options } = setup()
    const renderer = await createBodyRenderer(canvas, {
      ...options,
      visual: { ...options.visual, template: 'female' },
    })
    const urls = vi.mocked(fetch).mock.calls.map(([url]) => String(url))
    expect(urls).toHaveLength(2)
    expect(urls[0]).toMatch(/female\.glb/)
    expect(urls[1]).toMatch(/female-lookaround\.glb/)
    expect(Number(canvas.dataset.clipDuration)).toBeCloseTo(25.8)
    expect(urls.join(' ')).not.toMatch(/male\.glb[^\s]*\s.*cmu|cmu/i)
    renderer!.destroy()
  })
  it('loads the selected Spartan with its corresponding animation and retains actual camera state', async () => {
    const { canvas, options } = setup()
    const renderer = await createBodyRenderer(canvas, {
      ...options,
      characterId: 'spartan',
      initialView: { yaw: 2.9, polar: 1.4, radius: 4.5 },
    })
    expect(vi.mocked(fetch).mock.calls.map(([url]) => String(url))).toEqual([
      expect.stringContaining('male_spartan.glb'),
      expect.stringContaining('male_spartan-lookaround.glb'),
    ])
    expect(canvas.dataset.characterId).toBe('spartan')
    expect(renderer!.getViewState()).toMatchObject({ yaw: 2.9, polar: 1.4, radius: 4.5 })
    renderer!.setView('back')
    expect(renderer!.getViewState().yaw).toBeCloseTo(Math.PI)
    renderer!.destroy()
  })
  it('orbits the rest anatomical axis of offset scenes while retaining the saved view and body framing', async () => {
    root.position.set(8, 6, -4)
    root.scale.setScalar(2)
    mesh.geometry.translate(0, 1.5, 1.2)
    const rig = new Group()
    rig.position.set(0.4, 0, -0.2)
    rig.rotation.y = Math.PI / 2
    const pelvis = new Bone()
    pelvis.name = 'AuthoredPelvis'
    pelvis.position.set(0.3, 1, 0.1)
    const joints = [...mesh.skeleton.bones]
    joints.forEach((joint) => pelvis.add(joint))
    rig.add(pelvis)
    root.add(rig)
    root.updateMatrixWorld(true)
    mesh.bind(new Skeleton([...joints, pelvis]))
    const { beard } = beardSurface()
    beard.geometry.dispose()
    beard.geometry = new BoxGeometry(50, 50, 50)
    beard.position.set(20, 20, 20)
    bone.add(beard)
    const { canvas, options } = setup()
    const initialView = { yaw: 2.9, polar: 1.4, radius: 4.5 }
    const renderer = await createBodyRenderer(canvas, { ...options, initialView })
    expect(renderer).not.toBeNull()
    const centered = pelvis.getWorldPosition(new Vector3())
    expect(centered.x).toBeCloseTo(0)
    expect(centered.z).toBeCloseTo(0)
    expect(root.scale.x).toBeCloseTo(3.25 / 3)
    for (const [field, value] of Object.entries(initialView)) {
      expect(renderer!.getViewState()[field as keyof typeof initialView]).toBeCloseTo(value)
    }
    const placement = root.position.clone()
    const target = new Vector3(0, 1.625, 0)
    for (const yaw of [0, Math.PI / 2, Math.PI, -Math.PI / 2]) {
      renderer!.setYaw(yaw)
      drawAt(now + 40)
      const camera = engine().render.mock.lastCall![1]
      const offset = camera.position.clone().sub(target)
      expect(offset.length()).toBeCloseTo(initialView.radius)
      expect(Math.atan2(offset.x, offset.z)).toBeCloseTo(yaw)
      expect(camera.getWorldDirection(new Vector3()).dot(offset.normalize().negate())).toBeCloseTo(
        1,
      )
      expect(renderer!.getViewState().polar).toBeCloseTo(initialView.polar)
    }
    renderer!.setVisual({ ...options.visual, fat: 0.8, muscle: 0.2 })
    renderer!.setMotion(true)
    renderer!.setScenario('calm')
    advance(0.5)
    const animatedCamera = engine().render.mock.lastCall![1]
    const animatedOffset = animatedCamera.position.clone().sub(target)
    expect(animatedOffset.length()).toBeCloseTo(initialView.radius)
    expect(
      animatedCamera.getWorldDirection(new Vector3()).dot(animatedOffset.normalize().negate()),
    ).toBeCloseTo(1)
    expect(root.position.equals(placement)).toBe(true)
    expect(root.scale.x).toBeCloseTo(3.25 / 3)
    expect(renderer!.getViewState().yaw).toBeCloseTo(-Math.PI / 2)
    expect(renderer!.getViewState().polar).toBeCloseTo(initialView.polar)
    expect(renderer!.getViewState().radius).toBeCloseTo(initialView.radius)
    renderer!.destroy()
  })
  it('downloads body and idle bytes while backend initialization is still pending', async () => {
    const initialization = deferred<void>()
    const body = deferred<ArrayBuffer>()
    const idle = deferred<ArrayBuffer>()
    const bodyBuffer = vi.fn(() => body.promise)
    const idleBuffer = vi.fn(() => idle.promise)
    state.init.mockReturnValue(initialization.promise)
    vi.mocked(fetch)
      .mockResolvedValueOnce({ ok: true, arrayBuffer: bodyBuffer } as unknown as Response)
      .mockResolvedValueOnce({ ok: true, arrayBuffer: idleBuffer } as unknown as Response)
    const { canvas, options } = setup()
    const created = createBodyRenderer(canvas, options)
    await vi.waitFor(() => {
      expect(fetch).toHaveBeenCalledTimes(2)
      expect(bodyBuffer).toHaveBeenCalledOnce()
      expect(idleBuffer).toHaveBeenCalledOnce()
    })
    const requests = vi.mocked(fetch).mock.calls
    expect(requests.every(([url]) => new URL(String(url)).origin === location.origin)).toBe(true)
    expect(requests[0]![1]).toMatchObject({ credentials: 'same-origin' })
    expect(requests[1]![1]?.signal).toBe(requests[0]![1]?.signal)
    body.resolve(new ArrayBuffer(1))
    idle.resolve(new ArrayBuffer(2))
    await Promise.resolve()
    expect(state.parse).not.toHaveBeenCalled()
    initialization.resolve()
    const renderer = await created
    expect(state.parse.mock.calls.map(([bytes]) => (bytes as ArrayBuffer).byteLength)).toEqual([
      1, 2,
    ])
    expect(renderer).not.toBeNull()
    renderer!.destroy()
  })
  it('handles an early optional idle rejection and keeps the still model usable', async () => {
    const initialization = deferred<void>()
    state.init.mockReturnValue(initialization.promise)
    vi.mocked(fetch)
      .mockResolvedValueOnce({ ok: true, arrayBuffer: async () => new ArrayBuffer(0) } as Response)
      .mockRejectedValueOnce(new Error('Idle download failed'))
    const { canvas, options } = setup()
    const created = createBodyRenderer(canvas, options)
    // An optional request can reject a full task before the mandatory model is ready.
    // Vitest rejects this test on an unhandled promise rejection.
    await new Promise<void>((resolve) => setTimeout(resolve, 0))
    initialization.resolve()
    const renderer = await created
    expect(renderer).not.toBeNull()
    expect(state.parse).toHaveBeenCalledOnce()
    expect(options.onFailure).not.toHaveBeenCalled()
    drawAt(40)
    expect(engine().render).toHaveBeenCalledOnce()
    expect(bone.quaternion.angleTo(new Quaternion())).toBeLessThan(1e-6)
    renderer!.setYaw(0.4)
    drawAt(80)
    expect(engine().render).toHaveBeenCalledTimes(2)
    renderer!.destroy()
  })
  it('uses WebGL and does no offscreen work while retaining a full camera orbit', async () => {
    state.webgpu = false
    const { canvas, options } = setup()
    const renderer = await createBodyRenderer(canvas, options)
    expect(renderer?.mode).toBe('webgl')
    renderer!.setActive(false)
    renderer!.setYaw(99)
    expect(frames.size).toBe(0)
    renderer!.setActive(true)
    drawAt(50)
    const camera = engine().render.mock.lastCall![1]
    expect(Math.atan2(camera.position.x, camera.position.z)).toBeCloseTo(
      Math.atan2(Math.sin(99), Math.cos(99)),
    )
    renderer!.destroy()
  })
  it('coalesces high-frequency yaw input into one camera update and one layout check per frame', async () => {
    const { canvas, options } = setup()
    const renderer = await createBodyRenderer(canvas, options)
    drawAt(40)
    const camera = engine().render.mock.lastCall![1]
    const initial = camera.position.clone()
    const update = vi.spyOn(camera, 'lookAt')
    const bounds = vi.mocked(canvas.getBoundingClientRect)
    bounds.mockClear()
    for (let event = 0; event < 240; event++) renderer!.setYaw(-0.5 + event / 240)
    expect(camera.position.equals(initial)).toBe(true)
    expect(update).not.toHaveBeenCalled()
    expect(bounds).toHaveBeenCalledOnce()
    expect(frames.size).toBe(1)
    drawAt(80)
    expect(Math.atan2(camera.position.x, camera.position.z)).toBeCloseTo(-0.5 + 239 / 240)
    expect(update).toHaveBeenCalledOnce()
    expect(bounds).toHaveBeenCalledOnce()
    expect(frames.size).toBe(0)
    renderer!.setComparison(true)
    renderer!.setYaw(NaN)
    drawAt(120)
    expect(Math.atan2(camera.position.x, camera.position.z)).toBeCloseTo(-0.5 + 239 / 240)
    expect(canvas.dataset.characterComparison).toBe('true')
    expect(frames.size).toBe(0)
    renderer!.destroy()
  })
  it('keeps camera transition ordering when a view follows or precedes queued yaw input', async () => {
    const { canvas, options } = setup()
    const renderer = await createCharacterRenderer(canvas, {
      assetUrl: '/body.glb',
      reducedMotion: false,
      active: true,
      externalControls: true,
    })
    renderer!.setMotionEnabled(false)
    drawAt(40)
    renderer!.setYaw(0.4)
    renderer!.setView('side')
    drawAt(80)
    const camera = engine().render.mock.lastCall![1]
    expect(Math.atan2(camera.position.x, camera.position.z)).toBeGreaterThan(0.4)
    advance(0.9)
    expect(Math.atan2(camera.position.x, camera.position.z)).toBeCloseTo(Math.PI / 2)
    renderer!.setView('front')
    renderer!.setYaw(-0.3)
    drawAt(now + 40)
    expect(Math.atan2(camera.position.x, camera.position.z)).toBeCloseTo(-0.3)
    expect(frames.size).toBe(0)
    renderer!.dispose()
    expect(options.onFailure).not.toHaveBeenCalled()
  })
  it('reuses source bridge snapshots without losing the evolving target or transition continuity', async () => {
    const otherBone = new Bone()
    otherBone.name = 'spine02'
    associateSourceNode(otherBone, 'spine02')
    mesh.add(otherBone)
    const idle = skeletonClip('rokoko_lookaround', 27, 0.1)
    for (const clip of [...clips, idle]) {
      const angle = clip === idle ? -0.18 : clip.name.startsWith('scenario_') ? -0.3 : 0
      const rotation = new Quaternion().setFromAxisAngle(new Vector3(1, 0, 0), angle)
      clip.tracks.push(
        new QuaternionKeyframeTrack(
          'spine02.quaternion',
          [0, clip.duration / 2, clip.duration],
          [0, 0, 0, 1, ...rotation.toArray(), 0, 0, 0, 1],
        ),
      )
    }
    state.parse
      .mockReset()
      .mockResolvedValueOnce(loadedBody())
      .mockResolvedValue({ scene: new Group(), animations: [idle] })
    const { canvas, options } = setup()
    const renderer = await createBodyRenderer(canvas, {
      ...options,
      motion: true,
      scenario: 'stage',
    })
    advance(3)
    const from = bone.quaternion.clone()
    const otherFrom = otherBone.quaternion.clone()
    expect(from.angleTo(new Quaternion())).toBeGreaterThan(0.05)
    renderer!.setScenario('idle')
    const clones = [
      vi.spyOn(bone.position, 'clone'),
      vi.spyOn(bone.quaternion, 'clone'),
      vi.spyOn(bone.scale, 'clone'),
      vi.spyOn(otherBone.position, 'clone'),
      vi.spyOn(otherBone.quaternion, 'clone'),
      vi.spyOn(otherBone.scale, 'clone'),
    ]
    drawAt(now + 40)
    expect(bone.quaternion.angleTo(from)).toBeLessThan(1e-6)
    expect(otherBone.quaternion.angleTo(otherFrom)).toBeLessThan(1e-6)
    advance(0.4)
    const elapsed = Number(canvas.dataset.clipElapsed)
    const target = new Quaternion().setFromAxisAngle(
      new Vector3(0, 0, 1),
      ((elapsed + LOOKAROUND_LOOP_BLEND_SECONDS) / 13.5) * 0.1,
    )
    const progress = elapsed / 0.8
    const midpoint = new Quaternion().slerpQuaternions(
      from,
      target,
      progress * progress * (3 - 2 * progress),
    )
    expect(bone.quaternion.angleTo(midpoint)).toBeLessThan(1e-6)
    const otherTarget = new Quaternion().setFromAxisAngle(
      new Vector3(1, 0, 0),
      ((elapsed + LOOKAROUND_LOOP_BLEND_SECONDS) / 13.5) * -0.18,
    )
    const otherMidpoint = new Quaternion().slerpQuaternions(
      otherFrom,
      otherTarget,
      progress * progress * (3 - 2 * progress),
    )
    expect(otherBone.quaternion.angleTo(otherMidpoint)).toBeLessThan(1e-6)
    advance(0.4)
    const finalTarget = new Quaternion().setFromAxisAngle(
      new Vector3(0, 0, 1),
      ((Number(canvas.dataset.clipElapsed) + LOOKAROUND_LOOP_BLEND_SECONDS) / 13.5) * 0.1,
    )
    expect(bone.quaternion.angleTo(finalTarget)).toBeLessThan(1e-6)
    const otherFinalTarget = new Quaternion().setFromAxisAngle(
      new Vector3(1, 0, 0),
      ((Number(canvas.dataset.clipElapsed) + LOOKAROUND_LOOP_BLEND_SECONDS) / 13.5) * -0.18,
    )
    // Keyframe storage is Float32; normalize its sampled quaternion for an angular
    // comparison so tiny length error is not mistaken for an orientation change.
    expect(
      new Quaternion().copy(otherBone.quaternion).normalize().angleTo(otherFinalTarget),
    ).toBeLessThan(1e-6)
    for (const clone of clones) expect(clone).not.toHaveBeenCalled()
    renderer!.destroy()
  })
  it('caps live motion at 60 fps and freezes bones while shape changes remain usable', async () => {
    const { canvas, options } = setup()
    const renderer = await createBodyRenderer(canvas, { ...options, motion: true })
    drawAt(0)
    drawAt(5)
    expect(engine().render).toHaveBeenCalledOnce()
    advance(1)
    const rotation = bone.quaternion.clone()
    renderer!.setPaused(true)
    renderer!.setVisual({ ...options.visual, fat: 1, muscle: 1 })
    advance(0.8)
    expect(bone.quaternion.angleTo(rotation)).toBeLessThan(1e-6)
    expect(mesh.morphTargetInfluences![29]).toBe(1)
    expect(frames.size).toBe(0)
    renderer!.setPaused(false)
    advance(0.1)
    expect(bone.quaternion.angleTo(rotation)).toBeGreaterThan(0)
    renderer!.destroy()
  })
  it.each([30, 60, 90, 120, 144])(
    'keeps the available refresh rate up to 60 fps without drift on a %i Hz display',
    async (refreshRate) => {
      const { canvas, options } = setup()
      const renderer = await createBodyRenderer(canvas, { ...options, motion: true })
      const renderedAt: number[] = []
      engine().render.mockImplementation(() => renderedAt.push(now))
      for (let frame = 0; frame < refreshRate * 10; frame++) drawAt((frame * 1000) / refreshRate)
      expect(renderedAt).toHaveLength(Math.min(refreshRate, 60) * 10)
      const gaps = renderedAt.slice(1).map((time, index) => time - renderedAt[index]!)
      expect(Math.max(...gaps)).toBeLessThanOrEqual(1000 / 60 + 1000 / refreshRate + 0.5)
      // Animation time follows the actual displayed frames, independently of the deadline phase.
      expect(Number(canvas.dataset.clipElapsed)).toBeCloseTo(renderedAt.at(-1)! / 1000, 6)
      renderer!.destroy()
    },
  )
  it('tolerates sub-millisecond RAF rounding and skips late deadlines without catch-up bursts', async () => {
    const { canvas, options } = setup()
    const renderer = await createBodyRenderer(canvas, { ...options, motion: true })
    const renderedAt: number[] = []
    engine().render.mockImplementation(() => renderedAt.push(now))
    const jitter = [0.12, -0.18, 0.06, -0.11]
    for (let frame = 0; frame < 600; frame++)
      drawAt((frame * 1000) / 60 + jitter[frame % jitter.length]!)
    expect(renderedAt).toHaveLength(600)
    const gaps = renderedAt.slice(1).map((time, index) => time - renderedAt[index]!)
    expect(Math.max(...gaps)).toBeLessThan(17)
    expect(Number(canvas.dataset.clipElapsed)).toBeCloseTo(
      (renderedAt.at(-1)! - renderedAt[0]!) / 1000,
      6,
    )
    const before = renderedAt.length
    drawAt(now + 500)
    expect(renderedAt).toHaveLength(before + 1)
    drawAt(now + 1)
    expect(renderedAt).toHaveLength(before + 1)
    renderer!.destroy()
  })
  it('does not discard future slots when a rounded display clock drifts across the early tolerance', async () => {
    const { canvas, options } = setup()
    const renderer = await createBodyRenderer(canvas, { ...options, motion: true })
    const renderedAt: number[] = []
    engine().render.mockImplementation(() => renderedAt.push(now))
    // A 0.04 ms/s display-clock drift and 0.1 ms RAF precision eventually place
    // arrivals on both sides of the early-admission boundary. The tolerance may
    // admit a frame early, but may never discard a still-future target slot.
    for (let frame = 0; frame < 1800; frame++)
      drawAt(Math.round(((frame * 1000) / 60 - (frame * 0.04) / 60) * 10) / 10)
    expect(renderedAt).toHaveLength(1799)
    const gaps = renderedAt.slice(1).map((time, index) => time - renderedAt[index]!)
    expect(gaps.filter((gap) => gap > 25)).toHaveLength(1)
    expect(Number(canvas.dataset.clipElapsed)).toBeCloseTo((renderedAt.at(-1)! / 1000) % 25.8, 6)
    renderer!.destroy()
  })
  it('animates settled bones without layout reads or rewriting unchanged morphology', async () => {
    const { canvas, options } = setup()
    const renderer = await createBodyRenderer(canvas, { ...options, motion: true })
    drawAt(0)
    const bounds = vi.mocked(canvas.getBoundingClientRect)
    bounds.mockClear()
    let morphologyWrites = 0
    mesh.morphTargetInfluences = new Proxy(mesh.morphTargetInfluences!, {
      set(target, property, value) {
        morphologyWrites++
        return Reflect.set(target, property, value)
      },
    })
    drawAt(5) // Throttled RAFs must not force layout either.
    window.dispatchEvent(new Event('scroll'))
    advance(1)
    expect(engine().render.mock.calls.length).toBeGreaterThan(20)
    expect(bone.quaternion.angleTo(new Quaternion())).toBeGreaterThan(0)
    expect(bounds).not.toHaveBeenCalled()
    expect(morphologyWrites).toBe(0)

    renderer!.setVisual({ ...options.visual, fat: 1, muscle: 1 })
    advance(0.8)
    expect(mesh.morphTargetInfluences![29]).toBe(1)
    expect(morphologyWrites).toBeGreaterThan(0)
    morphologyWrites = 0
    bounds.mockClear()
    advance(0.3)
    expect(morphologyWrites).toBe(0)
    expect(bounds).not.toHaveBeenCalled()
    renderer!.destroy()
  })
  it('stops on intersection changes and lets controls wake a visible canvas before observer delivery', async () => {
    let intersection!: IntersectionObserverCallback
    vi.stubGlobal(
      'IntersectionObserver',
      class {
        constructor(callback: IntersectionObserverCallback) {
          intersection = callback
        }
        observe = vi.fn()
        disconnect = vi.fn()
      },
    )
    const { canvas, options } = setup()
    const renderer = await createBodyRenderer(canvas, { ...options, motion: true })
    drawAt(40)
    const bounds = vi.mocked(canvas.getBoundingClientRect)
    bounds.mockReturnValue(new DOMRect(0, 2000, 400, 600))
    intersection([], {} as IntersectionObserver)
    const rendered = engine().render.mock.calls.length
    advance(0.2)
    expect(canvas.dataset.rendererState).toBe('inactive')
    expect(engine().render).toHaveBeenCalledTimes(rendered)
    expect(frames.size).toBe(0)

    bounds.mockReturnValue(new DOMRect(0, 0, 400, 600))
    renderer!.setYaw(0.4)
    drawAt(now + 40)
    expect(engine().render).toHaveBeenCalledTimes(rendered + 1)
    bounds.mockReturnValue(new DOMRect(0, 2000, 400, 600))
    intersection([], {} as IntersectionObserver)
    bounds.mockReturnValue(new DOMRect(0, 0, 400, 600))
    window.dispatchEvent(new Event('scroll'))
    drawAt(now + 40)
    expect(engine().render).toHaveBeenCalledTimes(rendered + 2)
    renderer!.destroy()
  })
  it('does not restart a settled shape transition when the same visual is propagated again', async () => {
    const { canvas, options } = setup()
    const renderer = await createBodyRenderer(canvas, { ...options, motion: true, paused: true })
    drawAt(40)
    expect(frames.size).toBe(0)
    renderer!.setVisual({ ...options.visual })
    expect(frames.size).toBe(0)
    renderer!.setVisual({ ...options.visual, fat: 1, muscle: 1 })
    advance(0.4)
    renderer!.setVisual({ ...options.visual, fat: 1, muscle: 1 })
    advance(0.4)
    expect(mesh.morphTargetInfluences![29]).toBe(1)
    expect(frames.size).toBe(0)
    renderer!.destroy()
  })
  it('comparison uses the relaxed pose, freezes the idle clock and restores its time on exit', async () => {
    const { canvas, options } = setup()
    const renderer = await createBodyRenderer(canvas, { ...options, motion: true })
    advance(1)
    const rotation = bone.quaternion.clone()
    const time = Number(canvas.dataset.clipElapsed)
    expect(rotation.angleTo(new Quaternion())).toBeGreaterThan(0)
    renderer!.setComparison(true)
    advance(0.1)
    expect(bone.quaternion.angleTo(new Quaternion())).toBeLessThan(1e-6)
    expect(canvas.dataset.characterPose).toBe('relaxed')
    expect(Number(canvas.dataset.clipElapsed)).toBe(time)
    expect(frames.size).toBe(0)
    renderer!.setVisual({ ...options.visual, fat: 0.8 })
    advance(0.3)
    expect(Number(canvas.dataset.clipElapsed)).toBe(time)
    renderer!.setComparison(false)
    drawAt(now + 40)
    expect(bone.quaternion.angleTo(rotation)).toBeLessThan(1e-6)
    renderer!.destroy()
  })
  it('a reduced-motion change stops idle movement and snaps the next shape change', async () => {
    const { canvas, options } = setup()
    const renderer = await createBodyRenderer(canvas, { ...options, motion: true })
    advance(0.4)
    renderer!.setMotion(false)
    renderer!.setVisual({ ...options.visual, fat: 1, muscle: 1 })
    drawAt(now + 40)
    expect(mesh.morphTargetInfluences![29]).toBe(1)
    expect(frames.size).toBe(0)
    renderer!.setMotion(true)
    advance(0.1)
    expect(frames.size).toBe(1)
    renderer!.destroy()
  })
  it('preserves scenario selection and pause when comparison is toggled', async () => {
    const { canvas, options } = setup()
    const renderer = await createBodyRenderer(canvas, {
      ...options,
      motion: true,
      scenario: 'athletic',
    })
    advance(1.2)
    renderer!.setPaused(true)
    const rotation = bone.quaternion.clone()
    renderer!.setComparison(true)
    drawAt(now + 40)
    expect(bone.quaternion.angleTo(new Quaternion())).toBeLessThan(1e-6)
    renderer!.setComparison(false)
    drawAt(now + 40)
    expect(bone.quaternion.angleTo(rotation)).toBeLessThan(1e-6)
    expect(options.onState.mock.lastCall![0]).toMatchObject({ scenario: 'athletic', paused: true })
    expect(frames.size).toBe(0)
    renderer!.destroy()
  })
  it('replaces a queued automatic scene after changing to a softer complete analysis', async () => {
    const { canvas, options } = setup()
    const renderer = await createBodyRenderer(canvas, {
      ...options,
      motion: true,
      scenario: 'auto',
      visual: { ...options.visual, fat: 0.1, muscle: 1 },
    })
    renderer!.setVisual({ ...options.visual, fat: 1, muscle: 0 })
    advance(1.3)
    expect(options.onState.mock.lastCall![0]).toMatchObject({
      scenario: 'auto',
      activeScenario: 'calm',
    })
    renderer!.destroy()
  })
  it('disposes once and reports safe fallback on device loss', async () => {
    const { canvas, options } = setup()
    const disposeGeometry = vi.spyOn(mesh.geometry, 'dispose')
    const disposeSkeleton = vi.spyOn(mesh.skeleton, 'dispose')
    const renderer = await createBodyRenderer(canvas, options)
    engine().onDeviceLost()
    engine().onError()
    renderer!.destroy()
    expect(options.onFailure).toHaveBeenCalledOnce()
    expect(engine().dispose).toHaveBeenCalledOnce()
    expect(disposeGeometry).toHaveBeenCalledOnce()
    expect(disposeSkeleton).toHaveBeenCalledOnce()
    expect(frames.size).toBe(0)
  })
  it('aborts parallel asset requests and disposes late initialization after unmount', async () => {
    const pending = deferred<void>()
    state.init.mockReturnValue(pending.promise)
    const abort = new AbortController()
    const { canvas, options } = setup()
    const remove = vi.spyOn(abort.signal, 'removeEventListener')
    const created = createBodyRenderer(canvas, { ...options, signal: abort.signal })
    expect(fetch).toHaveBeenCalledTimes(2)
    abort.abort()
    pending.resolve()
    expect(await created).toBeNull()
    expect(vi.mocked(fetch).mock.calls.every(([, request]) => request?.signal?.aborted)).toBe(true)
    expect(state.parse).not.toHaveBeenCalled()
    expect(remove).toHaveBeenCalledWith('abort', expect.any(Function))
    expect(engine().dispose).toHaveBeenCalledOnce()
    expect(options.onFailure).not.toHaveBeenCalled()
  })
  it('aborts outstanding downloads after initialization fails without retrying the backend', async () => {
    state.init.mockRejectedValue(new Error('Backend unavailable'))
    vi.mocked(fetch).mockImplementation(
      (_, request) =>
        new Promise<Response>((_, reject) => {
          request?.signal?.addEventListener('abort', () => reject(request.signal?.reason), {
            once: true,
          })
        }),
    )
    const { canvas, options } = setup()
    expect(await createBodyRenderer(canvas, options)).toBeNull()
    expect(fetch).toHaveBeenCalledTimes(2)
    expect(vi.mocked(fetch).mock.calls.every(([, request]) => request?.signal?.aborted)).toBe(true)
    expect(state.parse).not.toHaveBeenCalled()
    expect(engine().dispose).not.toHaveBeenCalled()
    expect(options.onFailure).toHaveBeenCalledOnce()
    expect(canvas.dataset.rendererState).toBe('disposed')
    await new Promise<void>((resolve) => setTimeout(resolve, 0))
  })
  it.each([1, 2])(
    'stops startup work when unmounted during yield checkpoint %i',
    async (checkpoint) => {
      const pending = deferred<void>()
      const nativeYield = vi.fn().mockResolvedValue(undefined)
      nativeYield.mockImplementationOnce(() =>
        checkpoint === 1 ? pending.promise : Promise.resolve(),
      )
      if (checkpoint === 2) nativeYield.mockImplementationOnce(() => pending.promise)
      vi.stubGlobal('scheduler', { yield: nativeYield })
      const abort = new AbortController()
      const { canvas, options } = setup()
      const disposeGeometry = vi.spyOn(mesh.geometry, 'dispose')
      const created = createBodyRenderer(canvas, { ...options, signal: abort.signal })
      await vi.waitFor(() => expect(nativeYield).toHaveBeenCalledTimes(checkpoint))
      abort.abort()
      pending.resolve()
      expect(await created).toBeNull()
      expect(state.parse).toHaveBeenCalledTimes(checkpoint - 1)
      expect(disposeGeometry).toHaveBeenCalledTimes(checkpoint - 1)
      expect(engine().dispose).toHaveBeenCalledOnce()
      expect(options.onFailure).not.toHaveBeenCalled()
      expect(state.compile).not.toHaveBeenCalled()
      expect(frames.size).toBe(0)
    },
  )
  it('disposes an asset that finishes parsing after unmount', async () => {
    const pending = deferred<ReturnType<typeof loadedBody>>()
    state.parse.mockReset().mockReturnValue(pending.promise)
    const abort = new AbortController()
    const { canvas, options } = setup()
    const created = createBodyRenderer(canvas, { ...options, signal: abort.signal })
    await vi.waitFor(() => expect(state.parse).toHaveBeenCalledOnce())
    const disposeGeometry = vi.spyOn(mesh.geometry, 'dispose')
    abort.abort()
    pending.resolve(loadedBody())
    expect(await created).toBeNull()
    expect(disposeGeometry).toHaveBeenCalledOnce()
    expect(engine().dispose).toHaveBeenCalledOnce()
    expect(frames.size).toBe(0)
  })
  it('falls back instead of displaying an incomplete morphology grid', async () => {
    const { canvas, options } = setup()
    delete mesh.morphTargetDictionary!.shape_f0_m0
    expect(await createBodyRenderer(canvas, options)).toBeNull()
    expect(options.onFailure).toHaveBeenCalledOnce()
    expect(engine().dispose).toHaveBeenCalledOnce()
    expect(frames.size).toBe(0)
  })
  it('retains the poster and cleans up if shader compilation fails', async () => {
    state.compile.mockRejectedValue(new Error('Unsupported material'))
    const { canvas, options } = setup()
    expect(await createBodyRenderer(canvas, options)).toBeNull()
    expect(options.onFailure).toHaveBeenCalledOnce()
    expect(engine().dispose).toHaveBeenCalledOnce()
    expect(frames.size).toBe(0)
  })
  it('does not start frames after compilation finishes after abort', async () => {
    const pending = deferred<void>()
    state.compile.mockReturnValue(pending.promise)
    const abort = new AbortController()
    const { canvas, options } = setup()
    const created = createBodyRenderer(canvas, { ...options, signal: abort.signal })
    await vi.waitFor(() => expect(state.compile).toHaveBeenCalledOnce())
    abort.abort()
    pending.resolve()
    expect(await created).toBeNull()
    expect(engine().dispose).toHaveBeenCalledOnce()
    expect(options.onFailure).not.toHaveBeenCalled()
    expect(frames.size).toBe(0)
  })
  it('releases shader-only relief maps when an undeclared material rejects the model', async () => {
    const disposeMaps = reliefMaps.map((map) => vi.spyOn(map, 'dispose'))
    const { canvas, options } = setup()
    root.userData.bodyAsset.surfaces[0].material = 'Unexpected material'
    expect(await createBodyRenderer(canvas, options)).toBeNull()
    expect(options.onFailure).toHaveBeenCalledOnce()
    for (const dispose of disposeMaps) expect(dispose).toHaveBeenCalledOnce()
  })
  it('redraws after resize and inactive resume without reallocating an unchanged buffer', async () => {
    const { canvas, options } = setup()
    const renderer = await createBodyRenderer(canvas, options)
    drawAt(40)
    renderer!.resize()
    drawAt(80)
    expect(engine().setSize).toHaveBeenCalledOnce()
    expect(engine().render).toHaveBeenCalledTimes(2)
    expect(frames.size).toBe(0)
    renderer!.setActive(false)
    vi.mocked(canvas.getBoundingClientRect).mockReturnValue(new DOMRect(0, 0, 390, 560))
    renderer!.resize()
    expect(engine().setSize).toHaveBeenLastCalledWith(390, 560, false)
    expect(frames.size).toBe(0)
    renderer!.setActive(true)
    drawAt(120)
    expect(engine().render.mock.lastCall![1].aspect).toBeCloseTo(390 / 560)
    expect(frames.size).toBe(0)
    renderer!.destroy()
  })
})
