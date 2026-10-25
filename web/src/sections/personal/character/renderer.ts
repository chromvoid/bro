import {
  ACESFilmicToneMapping,
  AnimationClip,
  AnimationMixer,
  Bone,
  CircleGeometry,
  Color,
  Group,
  LoopOnce,
  Mesh,
  MeshStandardNodeMaterial,
  PCFShadowMap,
  PropertyBinding,
  PerspectiveCamera,
  Quaternion,
  Scene,
  ShadowNodeMaterial,
  SkinnedMesh,
  Spherical,
  Texture,
  TextureLoader,
  Vector3,
  WebGPURenderer,
  type Material,
  type MeshStandardMaterial,
  type Object3D,
} from 'three/webgpu'
import { abs, float, normalView, smoothstep, uniform, uv, vec2 } from 'three/tsl'
import { GLTFLoader, type GLTFParser } from 'three/addons/loaders/GLTFLoader.js'
import { OrbitControls } from 'three/addons/controls/OrbitControls.js'
import { yieldToMain } from '@/core/scheduling'
import { readCssSrgbColor, type ColorToken } from '../../../design/colors'
import { createMocapPlayer, MocapTimeline } from './mocap'
import { readBodyAsset, CORRECTION_NAMES, type SurfaceRole } from './assetContract'
import { createPoseCorrections } from './corrections'
import { createReliefNode } from './relief'
import { createTextureResources } from './textures'
import { createEmbeddedImageResources } from './embeddedImages'
import {
  bodyFramingBounds,
  bodyOrbitAxis,
  nearestViewYaw,
  normalizeBodyFrame,
  type CharacterViewState,
} from './view'
import { createStudioLighting } from './lighting'
import {
  COMPOSITION_PRESETS,
  SHAPE_NAMES,
  BODY_NORMAL_STRENGTH,
  compositionWeights,
  interpolateComposition,
  ease,
  normalizeComposition,
  type Composition,
  type PhysiquePreset,
} from './morphology'
import {
  advanceMotionTime,
  advanceBreathPhase,
  motionCharacter,
  sampleMotion,
  type MotionStyle,
  type MotionSample,
} from './motion'
import {
  ScenarioDirector,
  SCENARIO_DURATIONS,
  SCENARIO_NAMES,
  nextAutomaticScenario,
  scenarioPhase,
  type Scenario,
  type ScenarioSelection,
  type ScenarioPhase,
} from './scenarios'

export type { Composition, PhysiquePreset } from './morphology'
export type { MotionStyle } from './motion'
export type { Scenario, ScenarioSelection, ScenarioPhase } from './scenarios'
export type { CharacterViewState } from './view'
export type Physique = PhysiquePreset | 'custom'
export type Pose = 'relaxed' | 'flex' | 'open'
export type View = 'front' | 'side' | 'back' | 'three-quarter'
export type Layer = 'surface' | 'form'
export type Appearance = 'composition' | 'muscle' | 'fat' | 'water'
const ACCENTS: Record<Appearance, ColorToken> = {
  composition: '--body-composition',
  muscle: '--body-muscle',
  fat: '--body-fat',
  water: '--body-water',
}
export interface PreviewState {
  status: 'loading' | 'ready' | 'unavailable'
  mode: 'webgpu' | 'webgl' | null
  physique: Physique
  pose: Pose
  layer: Layer
  playing: boolean
  composition: Composition
  motionEnabled: boolean
  motionStyle: MotionStyle
  automaticPose: boolean
  scenario: ScenarioSelection
  activeScenario: Scenario | null
  queuedScenario: Scenario | null
  scenarioPhase: ScenarioPhase
  scenarioProgress: number
  scenarioRepeating: boolean
  motionSource: 'cmu' | 'game-idle' | 'authored' | 'static'
  clipAvailable: boolean
  clipElapsed: number
  clipDuration: number
  clipPlaying: boolean
  clipError?: string
  error?: string
}
export interface CharacterPreview {
  setPhysique(value: PhysiquePreset): void
  setComposition(value: Composition): void
  setMotionEnabled(value: boolean): void
  setAutomaticPose(): void
  setScenario(value: ScenarioSelection): void
  playScenario(value: Scenario): void
  setPose(value: Pose): void
  setView(value: View): void
  getViewState(): CharacterViewState
  setLayer(value: Layer): void
  setPlaying(value: boolean): void
  playSourceAnimation(): void
  setSourceAnimationPlaying(value: boolean): void
  seekSourceAnimation(seconds: number): void
  setActive(value: boolean): void
  setYaw(value: number): void
  setAppearance(value: Appearance): void
  setComparison(value: boolean): void
  setReducedMotion(value: boolean): void
  resize(): void
  dispose(): void
}
export interface PreviewOptions {
  assetUrl: string
  mocapUrl?: string
  idleUrl?: string
  idleClipName?: string
  idleLoopBlendSeconds?: number
  automaticScenarios?: boolean
  initialComposition?: Composition
  compositionTransition?: number
  active?: boolean
  externalControls?: boolean
  maxDpr?: number
  maxBufferSize?: number
  onState?: (state: PreviewState) => void
  reducedMotion?: boolean
  signal?: AbortSignal
  forceWebGL?: boolean
  initialView?: { yaw: number; polar?: number; radius?: number }
}

const POSES: readonly Pose[] = ['relaxed', 'flex', 'open']
const TRANSITION = 800
const FRAME_INTERVAL = 1000 / 60
const FRAME_TOLERANCE = 0.5
const FRAME_EPSILON = 1e-7
type Weights = [number, number, number]

export function selectionWeights(index: number): Weights {
  return [index === 0 ? 1 : 0, index === 1 ? 1 : 0, index === 2 ? 1 : 0]
}

/** Convex interpolation preserves authored full shapes; it never sums body deltas. */
export function interpolateWeights(from: Weights, to: Weights, progress: number): Weights {
  const t = Math.min(1, Math.max(0, progress))
  const ease = t * t * (3 - 2 * t)
  return from.map((value, index) => value + (to[index]! - value) * ease) as Weights
}

export function anatomyNormalStrength(layer: Layer): number {
  return layer === 'form' ? 0 : BODY_NORMAL_STRENGTH
}

/** Authored head, eye and hair maps remain independent of composition relief. */
export function authoredSurfaceMaterial(
  source: MeshStandardMaterial,
  role: SurfaceRole,
): MeshStandardNodeMaterial {
  return new MeshStandardNodeMaterial({
    name: source.name,
    color: source.color.clone(),
    roughness: source.roughness,
    metalness: source.metalness,
    map: source.map,
    normalMap: source.normalMap,
    normalMapType: source.normalMapType,
    normalScale: source.normalScale.clone(),
    roughnessMap: source.roughnessMap,
    metalnessMap: source.metalnessMap,
    aoMap: source.aoMap,
    aoMapIntensity: role === 'skin' ? Math.min(0.4, source.aoMapIntensity) : source.aoMapIntensity,
    emissive: source.emissive.clone(),
    emissiveMap: source.emissiveMap,
    emissiveIntensity: source.emissiveIntensity,
    alphaMap: source.alphaMap,
    vertexColors: source.vertexColors,
    side: source.side,
    transparent: source.transparent,
    opacity: source.opacity,
    alphaTest: source.alphaTest,
    depthWrite: source.depthWrite,
  })
}

/** Geometry inspection hides surface detail without transferring texture ownership. */
export function createSurfaceLayer(
  material: MeshStandardNodeMaterial,
  role: SurfaceRole,
  formColor: Color,
) {
  if (role !== 'skin' && role !== 'head_skin') return null
  const color = material.color.clone()
  const emissive = material.emissive.clone()
  const authored = {
    map: material.map,
    normalMap: material.normalMap,
    roughnessMap: material.roughnessMap,
    metalnessMap: material.metalnessMap,
    aoMap: material.aoMap,
    emissiveMap: material.emissiveMap,
    alphaMap: material.alphaMap,
    normalNode: material.normalNode,
    emissiveNode: material.emissiveNode,
    roughness: material.roughness,
    metalness: material.metalness,
    aoMapIntensity: material.aoMapIntensity,
    emissiveIntensity: material.emissiveIntensity,
    vertexColors: material.vertexColors,
    transparent: material.transparent,
    opacity: material.opacity,
    alphaTest: material.alphaTest,
  }
  let layer: Layer = 'surface'
  let appearanceTint: Color | null = null
  const applyColor = () => {
    material.color.copy(layer === 'form' ? formColor : color)
    if (layer === 'surface' && appearanceTint) material.color.lerp(appearanceTint, 0.16)
  }
  return {
    setLayer(value: Layer) {
      if (layer === value) return
      const version = material.version
      layer = value
      if (value === 'form') {
        material.map = null
        material.normalMap = null
        material.roughnessMap = null
        material.metalnessMap = null
        material.aoMap = null
        material.emissiveMap = null
        material.alphaMap = null
        material.normalNode = null
        material.emissiveNode = null
        material.roughness = 0.8
        material.metalness = 0
        material.aoMapIntensity = 0
        material.emissive.setScalar(0)
        material.emissiveIntensity = 0
        material.vertexColors = false
        material.transparent = false
        material.opacity = 1
        material.alphaTest = 0
      } else {
        Object.assign(material, authored)
        material.emissive.copy(emissive)
      }
      applyColor()
      // Alpha-test changes already invalidate the shader; maps and nodes need an explicit update.
      if (material.version === version) material.needsUpdate = true
    },
    setAppearance(tint: Color | null) {
      if (role !== 'skin') return
      appearanceTint = tint?.clone() ?? null
      applyColor()
    },
  }
}

/** GLTFLoader splits a multi-material node into meshes owned by its source node. */
export function sourceSurfaceNode(
  object: Object3D,
  parser: Pick<GLTFParser, 'associations' | 'json'>,
): string {
  const nodes = parser.json.nodes as { name?: unknown }[] | undefined
  for (let owner: Object3D | null = object; owner; owner = owner.parent) {
    const index = parser.associations.get(owner)?.nodes
    if (index === undefined) continue
    const name = nodes?.[index]?.name
    if (typeof name !== 'string' || !name) throw new Error('Missing character source node name')
    return name
  }
  throw new Error('Missing character source node association')
}

/** Animation may move bones, but must never overwrite the independently selected physique. */
export function poseClips(clips: AnimationClip[]): Map<Pose, AnimationClip> {
  const result = new Map<Pose, AnimationClip>()
  for (const pose of POSES) {
    const clip = clips.find((item) => item.name === pose)
    if (
      !clip ||
      !clip.tracks.length ||
      clip.tracks.some((track) => track.name.includes('morphTargetInfluences'))
    ) {
      throw new Error('Character requires three skeleton-only pose clips')
    }
    result.set(pose, clip)
  }
  return result
}

export function createPoseBlender(root: Object3D, clips: AnimationClip[]) {
  const mixer = new AnimationMixer(root)
  const selected = poseClips(clips)
  const actions = POSES.map((pose) => {
    const clip = selected.get(pose)!
    const action = mixer.clipAction(clip)
    action.setLoop(LoopOnce, 1)
    action.clampWhenFinished = true
    action.play()
    action.paused = true
    action.time = clip.duration
    return action
  })
  const bones: {
    bone: Bone
    position: Vector3
    rotation: Quaternion
    scale: Vector3
  }[] = []
  root.traverse((object) => {
    if (object instanceof Bone)
      bones.push({
        bone: object,
        position: object.position.clone(),
        rotation: object.quaternion.clone(),
        scale: object.scale.clone(),
      })
  })
  let lastValues: Weights | null = null
  return {
    mixer,
    apply(values: Weights) {
      // PropertyMixer skips writing unchanged held poses. Restore the cached clean
      // pose before mixer.update(), so procedural offsets cannot accumulate.
      for (const item of bones) {
        item.bone.position.copy(item.position)
        item.bone.quaternion.copy(item.rotation)
        item.bone.scale.copy(item.scale)
      }
      if (lastValues && values.every((value, index) => value === lastValues![index])) return
      actions.forEach((action, index) => action.setEffectiveWeight(values[index]!))
      mixer.update(0)
      lastValues = [...values]
      for (const item of bones) {
        item.position.copy(item.bone.position)
        item.rotation.copy(item.bone.quaternion)
        item.scale.copy(item.bone.scale)
      }
    },
  }
}

type BoneTransform = {
  bone: Bone
  position: Vector3
  rotation: Quaternion
  scale: Vector3
}
function captureBoneTransforms(root: Object3D): BoneTransform[] {
  const result: BoneTransform[] = []
  root.traverse((object) => {
    if (object instanceof Bone)
      result.push({
        bone: object,
        position: object.position.clone(),
        rotation: object.quaternion.clone(),
        scale: object.scale.clone(),
      })
  })
  return result
}
function restoreBoneTransforms(values: BoneTransform[]) {
  for (const value of values) {
    value.bone.position.copy(value.position)
    value.bone.quaternion.copy(value.rotation)
    value.bone.scale.copy(value.scale)
  }
}
function updateBoneTransforms(values: BoneTransform[]) {
  for (const value of values) {
    value.position.copy(value.bone.position)
    value.rotation.copy(value.bone.quaternion)
    value.scale.copy(value.bone.scale)
  }
}
function mixBoneTransforms(from: BoneTransform[], to: BoneTransform[], progress: number) {
  const t = ease(progress)
  for (let i = 0; i < from.length; i++) {
    const start = from[i]!
    const end = to[i]!
    start.bone.position.lerpVectors(start.position, end.position, t)
    start.bone.quaternion.slerpQuaternions(start.rotation, end.rotation, t)
    start.bone.scale.lerpVectors(start.scale, end.scale, t)
  }
}

/** Real temporal glTF tracks, sampled only with visible active time. Morphs are forbidden. */
export function createScenarioPlayer(root: Object3D, clips: AnimationClip[]) {
  const scenarioClips = SCENARIO_NAMES.map((scene) => {
    const clip = clips.find((item) => item.name === `scenario_${scene}`)
    if (
      !clip ||
      Math.abs(clip.duration - SCENARIO_DURATIONS[scene]) > 0.05 ||
      !clip.tracks.length ||
      clip.tracks.some((track) => {
        const parsed = PropertyBinding.parseTrackName(track.name)
        return (
          !['position', 'quaternion', 'scale'].includes(parsed.propertyName) ||
          !(root.getObjectByName(parsed.nodeName) instanceof Bone)
        )
      })
    )
      throw new Error(`Invalid skeleton-only scenario ${scene}`)
    return clip
  })
  const mixer = new AnimationMixer(root)
  const actions = scenarioClips.map((clip) => {
    const action = mixer.clipAction(clip)
    action.setLoop(LoopOnce, 1)
    action.clampWhenFinished = true
    action.play()
    action.paused = true
    action.setEffectiveWeight(0)
    return action
  })
  const clean = captureBoneTransforms(root)
  let lastScene: Scenario | null = null
  let lastTime = -1
  return {
    mixer,
    sample(scene: Scenario, elapsed: number) {
      const time = Math.max(0, Math.min(SCENARIO_DURATIONS[scene], elapsed))
      restoreBoneTransforms(clean)
      if (lastScene === scene && lastTime === time) return
      actions.forEach((action, index) => {
        action.setEffectiveWeight(SCENARIO_NAMES[index] === scene ? 1 : 0)
        if (SCENARIO_NAMES[index] === scene) action.time = time
      })
      mixer.update(0)
      for (const value of clean) {
        value.position.copy(value.bone.position)
        value.rotation.copy(value.bone.quaternion)
        value.scale.copy(value.bone.scale)
      }
      lastScene = scene
      lastTime = time
    },
    dispose() {
      mixer.stopAllAction()
      mixer.uncacheRoot(root)
    },
  }
}

export function previewCanvasSize(
  width: number,
  height: number,
  dpr: number,
  maxDpr = 1.6,
  maxBufferSize = 1600,
) {
  const ratio = Math.min(Number.isFinite(dpr) ? Math.max(1, dpr) : 1, maxDpr)
  const scale = Math.min(ratio, maxBufferSize / Math.max(1, width, height))
  return {
    width: Math.max(1, Math.round(width * scale)),
    height: Math.max(1, Math.round(height * scale)),
  }
}

function releaseModel(
  root: Object3D,
  releaseTexture?: (texture: Texture) => void,
  releaseMaterial?: (material: Material) => void,
) {
  const geometries = new Set<Mesh['geometry']>()
  const materials = new Set<Material>()
  const textures = new Set<Texture>()
  const skeletons = new Set<SkinnedMesh['skeleton']>()
  root.traverse((object) => {
    if (!(object instanceof Mesh)) return
    geometries.add(object.geometry)
    for (const material of Array.isArray(object.material) ? object.material : [object.material]) {
      materials.add(material)
      for (const value of Object.values(material)) if (value instanceof Texture) textures.add(value)
    }
    if (object instanceof SkinnedMesh) skeletons.add(object.skeleton)
  })
  textures.forEach((texture) => {
    if (releaseTexture) {
      releaseTexture(texture)
      return
    }
    texture.dispose()
    const source: unknown = texture.source?.data
    if (typeof ImageBitmap !== 'undefined' && source instanceof ImageBitmap) source.close()
  })
  materials.forEach((material) => {
    if (releaseMaterial) releaseMaterial(material)
    else material.dispose()
  })
  geometries.forEach((geometry) => geometry.dispose())
  skeletons.forEach((skeleton) => skeleton.dispose())
}

/** Normal dispose retries init after rejection in pinned Three 0.186.1. */
function releaseFailedBackend(backend: unknown, canvas: HTMLCanvasElement) {
  const partial = backend as {
    _onContextLost?: EventListener
    gl?: WebGL2RenderingContext | null
    device?: { onuncapturederror: unknown; destroy(): void } | null
  }
  if (partial._onContextLost) canvas.removeEventListener('webglcontextlost', partial._onContextLost)
  try {
    partial.gl?.getExtension('WEBGL_lose_context')?.loseContext()
  } catch {
    // Already lost.
  }
  if (partial.device) {
    partial.device.onuncapturederror = null
    partial.device.destroy()
  }
}

export async function createCharacterPreview(
  canvas: HTMLCanvasElement,
  options: PreviewOptions,
): Promise<CharacterPreview | null> {
  if (options.signal?.aborted) return null
  let reduced = options.reducedMotion ?? matchMedia('(prefers-reduced-motion: reduce)').matches
  let active = options.active ?? true
  let comparison = false
  const initialComposition = normalizeComposition(
    options.initialComposition ?? COMPOSITION_PRESETS.athletic,
  )
  const sourceUrl = options.mocapUrl ?? options.idleUrl
  const sourceKind = options.mocapUrl ? 'cmu' : 'game-idle'
  const state: PreviewState = {
    status: 'loading',
    mode: null,
    physique: options.initialComposition ? 'custom' : 'athletic',
    pose: 'relaxed',
    layer: 'surface',
    playing: false,
    composition: { ...initialComposition },
    motionEnabled: !reduced,
    motionStyle: motionCharacter(initialComposition).style,
    automaticPose: options.automaticScenarios ?? false,
    scenario: 'auto',
    activeScenario: null,
    queuedScenario: null,
    scenarioPhase: 'idle',
    scenarioProgress: 0,
    scenarioRepeating: !reduced && !!options.automaticScenarios,
    motionSource: sourceUrl ? sourceKind : options.automaticScenarios ? 'authored' : 'static',
    clipAvailable: false,
    clipElapsed: 0,
    clipDuration: 0,
    clipPlaying: false,
  }
  const isSourceAnimation = () => state.motionSource === 'cmu' || state.motionSource === 'game-idle'
  const notify = () => options.onState?.({ ...state, composition: { ...state.composition } })
  notify()
  const abort = new AbortController()
  let renderer: WebGPURenderer | undefined
  let initialBackend: unknown
  let initialized = false
  let backendDisposed = false
  let disposed = false
  let ready = false
  let raf = 0
  let frames = 0
  canvas.dataset.rendererFrames = '0'
  canvas.dataset.rendererState = 'initializing'
  let scene: Scene | undefined
  let model: Group | undefined
  let mixer: AnimationMixer | undefined
  const cleanup: (() => void)[] = []
  const textureResources = createTextureResources()
  const embeddedImages = createEmbeddedImageResources()
  const createdMaterials = new Set<Material>()
  const releasedMaterials = new WeakSet<Material>()
  const releaseMaterial = (material: Material) => {
    createdMaterials.delete(material)
    if (releasedMaterials.has(material)) return
    releasedMaterials.add(material)
    material.dispose()
  }
  const disposeBackend = () => {
    if (!renderer || backendDisposed) return
    backendDisposed = true
    if (initialized) void renderer.dispose().catch(() => undefined)
    else releaseFailedBackend(renderer.backend, canvas)
    if (initialBackend && initialBackend !== renderer.backend)
      releaseFailedBackend(initialBackend, canvas)
  }
  const dispose = () => {
    if (disposed) return
    disposed = true
    canvas.dataset.rendererState = 'disposed'
    cancelAnimationFrame(raf)
    raf = 0
    abort.abort()
    cleanup.splice(0).forEach((fn) => fn())
    mixer?.stopAllAction()
    if (model) mixer?.uncacheRoot(model)
    if (scene) releaseModel(scene, textureResources.release, releaseMaterial)
    for (const material of createdMaterials) releaseMaterial(material)
    textureResources.dispose()
    embeddedImages.dispose()
    if (initialized) disposeBackend()
  }
  const unavailable = () => {
    if (disposed) return
    state.status = 'unavailable'
    state.playing = false
    state.error = 'Не удалось открыть 3D-модель. Попробуйте обновить страницу.'
    dispose()
    notify()
  }
  options.signal?.addEventListener('abort', dispose, { once: true })
  cleanup.push(() => options.signal?.removeEventListener('abort', dispose))
  try {
    renderer = new WebGPURenderer({
      canvas,
      antialias: true,
      alpha: true,
      forceWebGL: options.forceWebGL || !navigator.gpu,
      powerPreference: 'low-power',
    })
    initialBackend = renderer.backend
    renderer.onDeviceLost = unavailable
    renderer.onError = unavailable
    const assetUrl = new URL(options.assetUrl, location.href)
    if (assetUrl.origin !== location.origin) throw new Error('Character must be same-origin')
    const fetchBytes = async (url: URL, error: string) => {
      const response = await fetch(url, { signal: abort.signal, credentials: 'same-origin' })
      if (!response.ok) throw new Error(error)
      return response.arrayBuffer()
    }
    // Both requests overlap backend initialization. Attach rejection handlers now:
    // initialization can fail before either download settles, and motion is optional.
    const settled = <T>(pending: Promise<T>): Promise<PromiseSettledResult<T>> =>
      pending.then(
        (value) => ({ status: 'fulfilled', value }),
        (reason: unknown) => ({ status: 'rejected', reason }),
      )
    const bodyBytes = settled(fetchBytes(assetUrl, 'Character unavailable'))
    let motionUrl: URL | undefined
    const motionBytes = sourceUrl
      ? settled(
          (async () => {
            motionUrl = new URL(sourceUrl, location.href)
            if (motionUrl.origin !== location.origin) throw new Error('Mocap must be same-origin')
            return fetchBytes(motionUrl, 'Mocap unavailable')
          })(),
        )
      : null
    await renderer.init()
    initialized = true
    if (disposed) {
      disposeBackend()
      return null
    }
    const engine = renderer
    // Three 0.186.1 otherwise retains a perpetual RAF even without an animation loop.
    const clock = engine as unknown as {
      _animation?: { stop?: () => void }
      _nodes?: { nodeFrame?: { update?: () => void } }
    }
    if (!clock._animation?.stop || !clock._nodes?.nodeFrame?.update) {
      throw new Error('Unsupported Three frame clock')
    }
    clock._animation.stop()
    engine.toneMapping = ACESFilmicToneMapping
    engine.toneMappingExposure = 0.92
    engine.shadowMap.enabled = true
    engine.shadowMap.type = PCFShadowMap
    scene = new Scene()
    const studio = scene
    const camera = new PerspectiveCamera(33, 1, 0.05, 40)
    camera.position.set(options.externalControls ? 0.88 : 2.3, 1.8, 6.4)
    const controls = new OrbitControls(camera)
    // Deliberately don't call connect(): stock controls write inline touch-action:none.
    // CSS pan-y and the handlers below preserve page scroll while OrbitControls owns camera math.
    controls.domElement = canvas
    controls.target.set(0, 1.62, 0)
    controls.enablePan = false
    controls.enableDamping = false
    controls.minDistance = 3.7
    controls.maxDistance = 10
    controls.minPolarAngle = Math.PI * 0.36
    controls.maxPolarAngle = Math.PI * 0.57
    controls.update()
    if (options.initialView) {
      const { yaw, polar, radius } = options.initialView
      if (!Number.isFinite(yaw)) throw new Error('Invalid initial camera yaw')
      const distance = radius ?? camera.position.distanceTo(controls.target)
      const inclination = polar ?? controls.getPolarAngle()
      if (!Number.isFinite(distance) || !Number.isFinite(inclination))
        throw new Error('Invalid initial camera view')
      camera.position
        .setFromSpherical(
          new Spherical(
            Math.min(controls.maxDistance, Math.max(controls.minDistance, distance)),
            Math.min(controls.maxPolarAngle, Math.max(controls.minPolarAngle, inclination)),
            yaw,
          ),
        )
        .add(controls.target)
      controls.update()
    }
    // Illustration colors stay fixed across themes; resolve them once at the sRGB GPU boundary.
    const color = (token: ColorToken) => readCssSrgbColor(token, canvas)
    const lighting = createStudioLighting(color)
    lighting.update(controls.getAzimuthalAngle())
    studio.add(lighting.group)
    cleanup.push(() => lighting.dispose())
    const floorMaterial = new ShadowNodeMaterial({
      color: color('--body-shadow'),
      depthWrite: false,
    })
    floorMaterial.opacityNode = float(1)
      .sub(smoothstep(0.22, 0.5, uv().distance(vec2(0.5))))
      .mul(0.62)
    const floor = new Mesh(new CircleGeometry(1.8, 80), floorMaterial)
    floor.rotation.x = -Math.PI / 2
    floor.position.y = -0.015
    floor.receiveShadow = true
    studio.add(floor)

    const downloaded = await bodyBytes
    if (disposed) return null
    if (downloaded.status === 'rejected') throw downloaded.reason
    await yieldToMain(abort.signal)
    const loader = new GLTFLoader(embeddedImages.manager).register((parser) => {
      // Explicit URIs belong to the document/provider. Only URLs minted from
      // embedded bufferViews are ours to revoke on decode failure or disposal.
      embeddedImages.declareUris(parser.json.images)
      embeddedImages.declareUris(parser.json.buffers)
      // ImageBitmapLoader uses fetch(blob:), which is forbidden by the app's
      // connect-src. TextureLoader decodes through <img>; img-src already permits
      // local blob images. Keep the production policy unchanged.
      parser.textureLoader = new TextureLoader(parser.options.manager).setCrossOrigin(
        parser.options.crossOrigin,
      )
      return {
        name: 'LOCAL_CSP_TEXTURE_IMAGES',
        async afterRoot(result) {
          // GLTFLoader normally catches texture errors and silently drops maps.
          // The normal bake is essential to this visual prototype: fail visibly.
          const textures: (Texture | null)[] = await parser.getDependencies('texture')
          textures.forEach((texture) => {
            if (texture) textureResources.track(texture)
          })
          if (textures.some((texture) => texture === null)) {
            releaseModel(result.scene, textureResources.release, releaseMaterial)
            throw new Error('Character texture decoding failed')
          }
        },
      }
    })
    const gltf = await loader.parseAsync(downloaded.value, new URL('.', assetUrl).href)
    if (disposed) {
      releaseModel(gltf.scene, textureResources.release, releaseMaterial)
      return null
    }
    model = gltf.scene
    studio.add(model)
    const asset = readBodyAsset(
      gltf.scene.userData.bodyAsset,
      (gltf.parser.json.textures as unknown[] | undefined)?.length ?? 0,
    )
    const reliefTextures: Texture[] = await gltf.parser.getDependencies('texture')
    reliefTextures.forEach((texture) => textureResources.track(texture))
    if (disposed) return null
    const relief = createReliefNode(asset, reliefTextures)
    await yieldToMain(abort.signal)
    const meshes: Mesh[] = []
    const surfaces: NonNullable<ReturnType<typeof createSurfaceLayer>>[] = []
    const appearanceColors = Object.fromEntries(
      Object.entries(ACCENTS).map(([appearance, token]) => [appearance, color(token)]),
    ) as Record<Appearance, string>
    const formColor = new Color(color('--body-form'))
    const accent = uniform(new Color(appearanceColors.composition))
    const sourceMaterials = new Map<Material, Map<SurfaceRole, MeshStandardNodeMaterial>>()
    const declaredSurfaces = new Set<string>()
    const loadedBodyNodes = new Set<string>()
    const loadedFramingNodes = new Set<string>()
    const framingMeshes: Mesh[] = []
    let skinned = 0
    model.traverse((object) => {
      if (!(object instanceof Mesh)) return
      if (object instanceof SkinnedMesh) skinned += 1
      const sourceNode = sourceSurfaceNode(object, gltf.parser)
      object.castShadow = true
      object.receiveShadow = true
      object.frustumCulled = false
      const convert = (source: Material) => {
        const old = source as MeshStandardMaterial
        const declaration = asset.surfaces.find(
          (surface) => surface.node === sourceNode && surface.material === old.name,
        )
        if (!declaration) throw new Error('Undeclared character surface')
        declaredSurfaces.add(JSON.stringify([declaration.node, declaration.material]))
        const existing = sourceMaterials.get(source)?.get(declaration.role)
        if (existing) return existing
        const material = authoredSurfaceMaterial(old, declaration.role)
        createdMaterials.add(material)
        const converted = sourceMaterials.get(source) ?? new Map()
        converted.set(declaration.role, material)
        sourceMaterials.set(source, converted)
        if (declaration.role === 'skin') {
          material.normalNode = relief.forSurface(material.normalScale).node
          material.emissiveNode = accent.mul(float(1).sub(abs(normalView.z)).pow(4).mul(0.006))
        }
        const surface = createSurfaceLayer(material, declaration.role, formColor)
        if (surface) surfaces.push(surface)
        releaseMaterial(source)
        return material
      }
      object.material = Array.isArray(object.material)
        ? object.material.map(convert)
        : convert(object.material)
      const isBody = asset.body_surface_nodes.includes(sourceNode)
      if (isBody) {
        if (
          !(object instanceof SkinnedMesh) ||
          !object.morphTargetInfluences ||
          object.morphTargetInfluences.length !== SHAPE_NAMES.length + CORRECTION_NAMES.length ||
          [...SHAPE_NAMES, ...CORRECTION_NAMES].some(
            (name) => object.morphTargetDictionary?.[name] === undefined,
          )
        )
          throw new Error('Incomplete v3 body surface')
        loadedBodyNodes.add(sourceNode)
      }
      const hasMorphs =
        !!object.morphTargetInfluences?.length ||
        Object.values(object.geometry.morphAttributes).some(
          (attributes) => Array.isArray(attributes) && attributes.length > 0,
        )
      if (
        !isBody &&
        hasMorphs &&
        asset.surfaces.some(
          ({ node, role }) =>
            node === sourceNode && (role === 'head_skin' || role === 'eye' || role === 'hair'),
        )
      )
        throw new Error('Authored head surfaces must not introduce facial morphs')
      if (asset.framing.body_surface_nodes.includes(sourceNode)) {
        framingMeshes.push(object)
        loadedFramingNodes.add(sourceNode)
      }
      if (hasMorphs) {
        if (
          !object.morphTargetDictionary ||
          !object.morphTargetInfluences ||
          SHAPE_NAMES.some((name) => object.morphTargetDictionary![name] === undefined)
        ) {
          throw new Error('Incomplete physique morphs')
        }
        meshes.push(object)
      }
    })
    if (
      !meshes.length ||
      !skinned ||
      loadedBodyNodes.size !== asset.body_surface_nodes.length ||
      loadedFramingNodes.size !== asset.framing.body_surface_nodes.length ||
      declaredSurfaces.size !== asset.surfaces.length
    )
      throw new Error('Character requires independent skin and physique morphs')
    canvas.dataset.rendererMorphCount = String(SHAPE_NAMES.length)
    canvas.dataset.rendererCorrectionCount = String(CORRECTION_NAMES.length)
    model.updateMatrixWorld(true)
    const orbitAxis = bodyOrbitAxis(framingMeshes)
    const corrections = createPoseCorrections(model, meshes, asset)
    const poseBlender = createPoseBlender(model, gltf.animations)
    mixer = poseBlender.mixer
    const scenarioPlayer = createScenarioPlayer(model, gltf.animations)
    cleanup.push(() => scenarioPlayer.dispose())
    const director = new ScenarioDirector()
    let mocapPlayer: ReturnType<typeof createMocapPlayer> | null = null
    let mocapTimeline: MocapTimeline | null = null
    if (motionBytes) {
      try {
        const downloaded = await motionBytes
        if (disposed) return null
        if (downloaded.status === 'rejected') throw downloaded.reason
        await yieldToMain(abort.signal)
        const motion = await loader.parseAsync(downloaded.value, new URL('.', motionUrl!).href)
        try {
          if (disposed) return null
          mocapPlayer = createMocapPlayer(
            model,
            motion.animations,
            sourceKind === 'cmu' ? 'cmu_flexing' : (options.idleClipName ?? 'game_idle'),
            { loopBlendSeconds: sourceKind === 'cmu' ? 0 : options.idleLoopBlendSeconds },
          )
        } finally {
          releaseModel(motion.scene)
        }
        mocapTimeline = new MocapTimeline(mocapPlayer.duration, sourceKind === 'game-idle')
        if (!reduced) mocapTimeline.play()
        state.clipAvailable = true
        state.clipDuration = mocapTimeline.duration
        state.automaticPose = false
        state.scenarioRepeating = false
        cleanup.push(() => mocapPlayer?.dispose())
      } catch (error) {
        if (disposed) return null
        console.error('Source animation loading failed', error)
        state.clipError =
          sourceKind === 'cmu'
            ? 'Запись CMU не загрузилась. Обновите страницу.'
            : 'Движение не загрузилось. Обновите страницу.'
        state.motionEnabled = false
      }
    }
    await yieldToMain(abort.signal)
    let composition = { ...state.composition }
    let compositionFrom = { ...composition }
    let compositionTo = { ...composition }
    let pose: Weights = [1, 0, 0]
    let poseFrom: Weights = [1, 0, 0]
    let poseTo: Weights = [1, 0, 0]
    let displayedPose: Weights = [1, 0, 0]
    let physiqueStart = -Infinity
    let compositionDuration = TRANSITION
    let poseStart = -Infinity
    let viewStart = -Infinity
    let viewFromYaw = controls.getAzimuthalAngle()
    let viewToYaw = viewFromYaw
    let viewFromPolar = controls.getPolarAngle()
    let viewRadius = camera.position.distanceTo(controls.target)
    let pendingYaw: number | null = null
    const viewPolar = Math.PI / 2 - 0.025
    const viewSphere = new Spherical()
    let activeTime = 0
    let motionTime = 0
    let breathPhase = 0
    let interactionUntil = 0
    let pendingHeldPose: Pose | null = null
    let bridge: {
      from: BoneTransform[]
      to: BoneTransform[]
      start: number
    } | null = null
    let sourceBridge: { from: BoneTransform[]; to: BoneTransform[]; start: number } | null = null
    let pausedBones: BoneTransform[] | null = null
    let lastTick: number | null = null
    let nextFrame: number | null = null
    let inViewport = false
    const updateViewport = (box = canvas.getBoundingClientRect()) => {
      inViewport =
        box.width > 0 &&
        box.height > 0 &&
        box.bottom > 0 &&
        box.right > 0 &&
        box.top < window.innerHeight &&
        box.left < window.innerWidth
    }
    const canDraw = (refresh = false) => {
      if (!active || document.hidden || !canvas.isConnected) return false
      if (refresh) updateViewport()
      return inViewport
    }
    const applyPose = poseBlender.apply
    let appliedComposition: Composition | null = null
    let appliedLayer: Layer | null = null
    const applyPhysique = (value: Composition) => {
      if (
        appliedComposition?.fat === value.fat &&
        appliedComposition.muscle === value.muscle &&
        appliedLayer === state.layer
      )
        return
      const values = compositionWeights(value)
      for (const mesh of meshes)
        SHAPE_NAMES.forEach((name, index) => {
          mesh.morphTargetInfluences![mesh.morphTargetDictionary![name]!] = values[index]!
        })
      const normalStrength = anatomyNormalStrength(state.layer)
      relief.update(value, normalStrength)
      appliedComposition = { ...value }
      appliedLayer = state.layer
    }
    const upperChest = model.getObjectByName('spine01')
    const torso = model.getObjectByName('spine02')
    const bone = (name: string) => model!.getObjectByName(PropertyBinding.sanitizeNodeName(name))
    const leftClavicle = bone('clavicle.L')
    const rightClavicle = bone('clavicle.R')
    const leftArm = bone('upperarm01.L')
    const rightArm = bone('upperarm01.R')
    const rotationDelta = new Quaternion()
    const axisX = new Vector3(1, 0, 0)
    const axisY = new Vector3(0, 1, 0)
    const axisZ = new Vector3(0, 0, 1)
    const offset = (bone: Object3D | undefined, axis: Vector3, angle: number) => {
      bone?.quaternion.multiply(rotationDelta.setFromAxisAngle(axis, angle))
    }
    const applyLivePose = (sample: MotionSample, amount = 1) => {
      if (upperChest) {
        upperChest.scale.x *= 1 + sample.chestExpansion * 0.45 * amount
        upperChest.scale.z *= 1 + sample.chestExpansion * amount
      }
      offset(upperChest, axisX, sample.chestPitch * amount)
      offset(torso, axisZ, sample.torsoRoll * amount)
      offset(torso, axisY, sample.torsoYaw * amount)
      offset(leftClavicle, axisZ, sample.shoulderLift * amount)
      offset(rightClavicle, axisZ, -sample.shoulderLift * amount)
      offset(leftArm, axisX, sample.armSway * amount)
      offset(rightArm, axisX, -sample.armSway * amount)
    }
    let liveSample = sampleMotion(0, composition)
    applyPhysique(composition)
    applyPose(pose)
    model.updateMatrixWorld(true)
    const bounds = bodyFramingBounds(framingMeshes)
    const orbitTarget = normalizeBodyFrame(model, bounds, orbitAxis)
    camera.position.add(orbitTarget.clone().sub(controls.target))
    controls.target.copy(orbitTarget)
    controls.update()
    const schedule = (refresh = true) => {
      // Visibility/IntersectionObserver delivery may lag viewport changes (notably
      // embedded browsers). Explicit controls recheck geometry, while a running
      // animation uses the observer snapshot instead of forcing layout every RAF.
      if (!disposed && ready && !raf && canDraw(refresh)) {
        canvas.dataset.rendererState = 'scheduled'
        raf = requestAnimationFrame(draw)
      }
    }
    const transitionPose = (value: Pose) => {
      state.pose = value
      poseFrom = [...displayedPose]
      poseTo = selectionWeights(POSES.indexOf(value))
      poseStart = reduced || !state.motionEnabled ? -Infinity : motionTime * 1000
    }
    const bridgeToHeld = (value: Pose) => {
      const from = captureBoneTransforms(model!)
      transitionPose(value)
      pose = selectionWeights(POSES.indexOf(value))
      poseFrom = [...pose]
      poseTo = [...pose]
      poseStart = -Infinity
      applyPose(pose)
      const to = captureBoneTransforms(model!)
      if (state.motionEnabled && !reduced) {
        bridge = { from, to, start: motionTime }
        restoreBoneTransforms(from)
      } else {
        bridge = null
        pausedBones = captureBoneTransforms(model!)
      }
    }
    const syncSourceAnimationState = (force = false) => {
      if (!isSourceAnimation()) return
      const previous = `${Math.floor(state.clipElapsed * 10)}:${state.clipPlaying}`
      state.clipElapsed = mocapTimeline?.elapsed ?? 0
      state.clipPlaying = !!mocapTimeline?.playing && state.motionEnabled && !reduced
      state.playing = false
      state.scenarioRepeating = false
      state.activeScenario = null
      state.queuedScenario = null
      canvas.dataset.motionSource = state.motionSource
      canvas.dataset.clipElapsed = String(state.clipElapsed)
      canvas.dataset.clipDuration = String(state.clipDuration)
      const next = `${Math.floor(state.clipElapsed * 10)}:${state.clipPlaying}`
      if (force || previous !== next) notify()
    }
    const syncScenarioState = (force = false) => {
      if (isSourceAnimation()) return
      canvas.dataset.motionSource = state.motionSource
      const previous = `${state.activeScenario}:${state.queuedScenario}:${state.scenarioPhase}:${Math.floor(state.scenarioProgress * 20)}:${state.playing}:${state.scenarioRepeating}`
      state.scenario = director.selection
      state.scenarioRepeating = state.automaticPose && !reduced && !director.stopping
      state.activeScenario = director.active
      state.queuedScenario = director.queued
      state.scenarioPhase = scenarioPhase(director.active, director.elapsed)
      state.scenarioProgress =
        director.active === null ? 0 : director.elapsed / SCENARIO_DURATIONS[director.active]
      state.playing = director.active !== null || director.queued !== null
      const next = `${state.activeScenario}:${state.queuedScenario}:${state.scenarioPhase}:${Math.floor(state.scenarioProgress * 20)}:${state.playing}:${state.scenarioRepeating}`
      if (force || previous !== next) notify()
    }
    const stopSequence = () => {
      director.stopAfterExit()
      syncScenarioState()
    }
    const draw = (time: number) => {
      raf = 0
      if (disposed) return
      if (!canDraw()) {
        lastTick = null
        canvas.dataset.rendererState = 'inactive'
        return
      }
      if (nextFrame !== null && time + FRAME_TOLERANCE < nextFrame) {
        schedule(false)
        return
      }
      // Keep the 60 Hz phase independent of the screen's refresh rate. Assigning
      // the last RAF timestamp accumulates rounding/overshoot and skips otherwise
      // on-time frames. Skip missed deadlines without catch-up renders.
      // Early admission tolerance must not count a future deadline as missed.
      nextFrame =
        nextFrame === null || time - nextFrame >= FRAME_INTERVAL - FRAME_EPSILON
          ? time + FRAME_INTERVAL
          : nextFrame + FRAME_INTERVAL
      const dt = lastTick === null ? 0 : (time - lastTick) / 1000
      lastTick = time
      activeTime = advanceMotionTime(activeTime, dt, true)
      const moving = state.motionEnabled && !reduced && !comparison
      motionTime = advanceMotionTime(motionTime, dt, moving)
      breathPhase = advanceBreathPhase(breathPhase, dt, composition, moving)
      const frameTime = activeTime * 1000
      const physiqueProgress = Math.min(1, (frameTime - physiqueStart) / compositionDuration)
      const poseProgress = Math.min(1, (motionTime * 1000 - poseStart) / TRANSITION)
      const viewProgress = Math.min(1, (frameTime - viewStart) / TRANSITION)
      composition =
        physiqueProgress < 1
          ? interpolateComposition(compositionFrom, compositionTo, physiqueProgress)
          : compositionTo
      pose = interpolateWeights(poseFrom, poseTo, poseProgress)
      applyPhysique(composition)
      canvas.dataset.characterPose = comparison
        ? 'relaxed'
        : isSourceAnimation()
          ? 'idle'
          : (director.active ?? state.pose)
      canvas.dataset.characterComparison = String(comparison)
      if (comparison) {
        // Comparing two scans changes only the morphology, never the pose or clip clock.
        applyPose([1, 0, 0])
      } else if (isSourceAnimation()) {
        mocapTimeline?.advance(dt, moving)
        if (mocapPlayer && mocapTimeline) mocapPlayer.sample(mocapTimeline.elapsed)
        else applyPose([1, 0, 0])
        if (sourceBridge !== null) {
          const progress = reduced ? 1 : Math.min(1, (motionTime - sourceBridge.start) / 0.8)
          updateBoneTransforms(sourceBridge.to)
          mixBoneTransforms(sourceBridge.from, sourceBridge.to, progress)
          if (progress >= 1) sourceBridge = null
        }
        syncSourceAnimationState()
      } else {
        // Freeze the last live sample exactly when paused; changing a slider cannot
        // start a hidden animation clock or gradually move the held skeleton.
        if (state.motionEnabled && !reduced)
          liveSample = sampleMotion(motionTime, composition, breathPhase)
        const event = director.update(dt, {
          enabled: state.motionEnabled && !reduced,
          allowStart:
            pointers.size === 0 &&
            activeTime >= interactionUntil &&
            bridge === null &&
            poseProgress >= 1,
          automatic: state.automaticPose,
          composition,
        })
        if (event.ended) {
          scenarioPlayer.sample(event.ended, SCENARIO_DURATIONS[event.ended])
          displayedPose = [1, 0, 0]
          if (pendingHeldPose !== null) {
            if (!reduced) applyLivePose(liveSample)
            bridgeToHeld(pendingHeldPose)
            pendingHeldPose = null
          }
        }
        if (pausedBones !== null && !state.motionEnabled) restoreBoneTransforms(pausedBones)
        else if (director.active !== null) {
          scenarioPlayer.sample(director.active, director.elapsed)
          displayedPose = [1, 0, 0]
          // Keep the authored middle untouched; ease the ambient breathing layer out
          // at entry and back in at exit, where every scene meets the relaxed pose.
          const duration = SCENARIO_DURATIONS[director.active]
          const ambient =
            1 - ease(director.elapsed / 0.7) + ease((director.elapsed - duration + 0.7) / 0.7)
          if (!reduced) applyLivePose(liveSample, ambient)
        } else if (bridge !== null) {
          const progress = Math.min(1, (motionTime - bridge.start) / 0.8)
          mixBoneTransforms(bridge.from, bridge.to, progress)
          if (!reduced) applyLivePose(liveSample, ease(progress))
          if (progress >= 1) bridge = null
        } else {
          const finalPose: Weights = state.automaticPose
            ? interpolateWeights(poseFrom, [1, 0, 0], poseProgress)
            : pose
          displayedPose = [...finalPose]
          applyPose(finalPose)
          if (!reduced) applyLivePose(liveSample)
        }
        syncScenarioState()
      }
      canvas.dataset.rendererCorrectionMax = String(corrections.apply(composition))
      if (pendingYaw !== null) {
        viewRadius = camera.position.distanceTo(controls.target)
        viewFromPolar = controls.getPolarAngle()
        viewToYaw = pendingYaw
        camera.position
          .setFromSpherical(viewSphere.set(viewRadius, viewFromPolar, pendingYaw))
          .add(controls.target)
        pendingYaw = null
        controls.update()
      } else if (viewProgress < 1) {
        const t = viewProgress * viewProgress * (3 - 2 * viewProgress)
        viewSphere.set(
          viewRadius,
          viewFromPolar + (viewPolar - viewFromPolar) * t,
          viewFromYaw + (viewToYaw - viewFromYaw) * t,
        )
        camera.position.setFromSpherical(viewSphere).add(controls.target)
        controls.update()
      } else if (Number.isFinite(viewStart)) {
        camera.position
          .setFromSpherical(viewSphere.set(viewRadius, viewPolar, viewToYaw))
          .add(controls.target)
        viewStart = -Infinity
        controls.update()
      }
      try {
        // Use the displayed camera after drag/preset interpolation, never its destination.
        const yaw = controls.getAzimuthalAngle()
        lighting.update(yaw)
        canvas.dataset.characterYaw = String(yaw)
        clock._nodes!.nodeFrame!.update!()
        engine.render(studio, camera)
        canvas.dataset.rendererFrames = String(++frames)
      } catch {
        unavailable()
        return
      }
      if (
        physiqueProgress < 1 ||
        (!isSourceAnimation() && moving && (poseProgress < 1 || bridge !== null)) ||
        viewProgress < 1 ||
        (moving && (isSourceAnimation() ? mocapTimeline?.playing : true))
      )
        schedule(false)
      else {
        canvas.dataset.rendererState = 'idle'
        lastTick = null
      }
    }
    let bufferWidth = 0
    let bufferHeight = 0
    const resize = () => {
      if (disposed) return
      const box = canvas.getBoundingClientRect()
      updateViewport(box)
      const size = previewCanvasSize(
        box.width,
        box.height,
        devicePixelRatio,
        options.maxDpr,
        options.maxBufferSize,
      )
      if (bufferWidth !== size.width || bufferHeight !== size.height) {
        bufferWidth = size.width
        bufferHeight = size.height
        engine.setSize(size.width, size.height, false)
        camera.aspect = size.width / size.height
        // Preserve horizontal room for the open-arm pose on narrow screens.
        camera.fov =
          (2 * Math.atan(Math.tan((33 * Math.PI) / 360) / Math.min(1, camera.aspect)) * 180) /
          Math.PI
        camera.updateProjectionMatrix()
      }
      schedule(false)
    }

    // OrbitControls camera behavior with explicit DOM listeners and no inline style mutations.
    const pointers = new Map<
      number,
      { x: number; y: number; startX: number; startY: number; type: string }
    >()
    const onPointerDown = (event: PointerEvent) => {
      if (event.pointerType !== 'touch' && event.button !== 0) return
      pointers.set(event.pointerId, {
        x: event.clientX,
        y: event.clientY,
        startX: event.clientX,
        startY: event.clientY,
        type: event.pointerType,
      })
      if (event.pointerType !== 'touch') canvas.setPointerCapture(event.pointerId)
      viewStart = -Infinity
      interactionUntil = activeTime + 0.8
    }
    const onPointerMove = (event: PointerEvent) => {
      const previous = pointers.get(event.pointerId)
      if (!previous) return
      const dx = event.clientX - previous.x
      const dy = event.clientY - previous.y
      const other = [...pointers.entries()].find(([id]) => id !== event.pointerId)?.[1]
      if (other && previous.type === 'touch') {
        const before = Math.hypot(previous.x - other.x, previous.y - other.y)
        const after = Math.hypot(event.clientX - other.x, event.clientY - other.y)
        if (before > 2 && after > 2) controls.dollyIn(before / after)
      } else {
        const horizontal =
          Math.abs(event.clientX - previous.startX) > Math.abs(event.clientY - previous.startY)
        if (previous.type !== 'touch' || horizontal) {
          if (previous.type === 'touch' && !canvas.hasPointerCapture(event.pointerId))
            canvas.setPointerCapture(event.pointerId)
          controls.rotateLeft((2 * Math.PI * dx) / Math.max(1, canvas.clientHeight))
          if (previous.type !== 'touch')
            controls.rotateUp((Math.PI * dy) / Math.max(1, canvas.clientHeight))
        }
      }
      previous.x = event.clientX
      previous.y = event.clientY
      controls.update()
      schedule()
    }
    const onPointerUp = (event: PointerEvent) => {
      pointers.delete(event.pointerId)
      if (canvas.hasPointerCapture(event.pointerId)) canvas.releasePointerCapture(event.pointerId)
      interactionUntil = activeTime + 0.8
    }
    const onWheel = (event: WheelEvent) => {
      // Ordinary wheel continues scrolling the page; Ctrl/trackpad-pinch zooms the model.
      if (!event.ctrlKey && !event.metaKey) return
      event.preventDefault()
      interactionUntil = activeTime + 0.8
      controls.dollyIn(Math.exp(Math.min(200, Math.max(-200, event.deltaY)) * 0.003))
      controls.update()
      schedule()
    }
    if (!options.externalControls) {
      canvas.addEventListener('pointerdown', onPointerDown)
      canvas.addEventListener('pointermove', onPointerMove)
      canvas.addEventListener('pointerup', onPointerUp)
      canvas.addEventListener('pointercancel', onPointerUp)
      canvas.addEventListener('wheel', onWheel, { passive: false })
    }
    cleanup.push(() => {
      canvas.removeEventListener('pointerdown', onPointerDown)
      canvas.removeEventListener('pointermove', onPointerMove)
      canvas.removeEventListener('pointerup', onPointerUp)
      canvas.removeEventListener('pointercancel', onPointerUp)
      canvas.removeEventListener('wheel', onWheel)
      pointers.forEach((_, id) => {
        if (canvas.hasPointerCapture(id)) canvas.releasePointerCapture(id)
      })
      pointers.clear()
      controls.enabled = false
      // No controls.dispose(): disconnected controls have no listeners and dispose writes styles.
    })
    const visibility = () => {
      if (!canDraw(true)) {
        lastTick = null
        cancelAnimationFrame(raf)
        raf = 0
        canvas.dataset.rendererState = 'inactive'
      } else schedule(false)
    }
    document.addEventListener('visibilitychange', visibility)
    cleanup.push(() => document.removeEventListener('visibilitychange', visibility))
    const observer = new ResizeObserver(resize)
    observer.observe(canvas)
    cleanup.push(() => observer.disconnect())
    const intersection = new IntersectionObserver(visibility)
    intersection.observe(canvas)
    cleanup.push(() => intersection.disconnect())
    const onScroll = () => {
      if (canvas.dataset.rendererState === 'inactive') schedule()
    }
    window.addEventListener('resize', resize)
    window.addEventListener('focus', resize)
    window.addEventListener('pageshow', resize)
    window.addEventListener('scroll', onScroll, { passive: true })
    window.visualViewport?.addEventListener('resize', resize)
    cleanup.push(() => {
      window.removeEventListener('resize', resize)
      window.removeEventListener('focus', resize)
      window.removeEventListener('pageshow', resize)
      window.removeEventListener('scroll', onScroll)
      window.visualViewport?.removeEventListener('resize', resize)
    })
    const onContextLost = (event: Event) => {
      event.preventDefault()
      unavailable()
    }
    canvas.addEventListener('webglcontextlost', onContextLost)
    cleanup.push(() => canvas.removeEventListener('webglcontextlost', onContextLost))
    resize()
    await yieldToMain(abort.signal)
    await engine.compileAsync(studio, camera)
    if (disposed) return null
    ready = true
    state.status = 'ready'
    state.mode = 'isWebGPUBackend' in engine.backend ? 'webgpu' : 'webgl'
    canvas.dataset.rendererMode = state.mode
    notify()
    schedule()
    const setComposition = (value: Composition, preset: Physique = 'custom') => {
      if (disposed) return
      const normalized = normalizeComposition(value)
      if (
        state.physique === preset &&
        state.composition.fat === normalized.fat &&
        state.composition.muscle === normalized.muscle
      )
        return
      state.physique = preset
      state.composition = normalized
      state.motionStyle = motionCharacter(state.composition).style
      if (director.selection === 'auto' && director.queued !== null) {
        director.queued = nextAutomaticScenario(state.composition, director.active ?? director.last)
      }
      compositionFrom = { ...composition }
      compositionTo = { ...state.composition }
      compositionDuration =
        preset === 'custom' ? (options.compositionTransition ?? 120) : TRANSITION
      physiqueStart = reduced ? -Infinity : activeTime * 1000
      notify()
      schedule()
    }
    const leaveSourceAnimation = (source: 'authored' | 'static') => {
      if (isSourceAnimation()) {
        const previous = captureBoneTransforms(model!)
        mocapTimeline?.pause()
        mocapPlayer?.reset()
        restoreBoneTransforms(previous)
        sourceBridge = null
        bridge = null
        pausedBones = null
      }
      state.motionSource = source
      state.clipPlaying = false
    }
    const prepareAutomatic = () => {
      leaveSourceAnimation('authored')
      const wasManual = !state.automaticPose
      state.automaticPose = true
      state.motionEnabled = !reduced
      pendingHeldPose = null
      pausedBones = null
      lastTick = null
      if (wasManual && director.active === null) bridgeToHeld('relaxed')
    }
    const playScenario = (value: Scenario) => {
      if (disposed || reduced) return
      prepareAutomatic()
      director.play(value, true)
      syncScenarioState(true)
      schedule()
    }
    return {
      setPhysique(value) {
        setComposition(COMPOSITION_PRESETS[value], value)
      },
      setComposition,
      setPose(value) {
        if (disposed) return
        leaveSourceAnimation('static')
        state.automaticPose = false
        state.pose = value
        if (director.active !== null && state.motionEnabled && !reduced) {
          pendingHeldPose = value
          director.stopAfterExit()
        } else {
          director.cancel()
          pendingHeldPose = null
          bridgeToHeld(value)
        }
        syncScenarioState(true)
        schedule()
      },
      setAutomaticPose() {
        if (disposed) return
        if (reduced) {
          leaveSourceAnimation('authored')
          director.selection = 'auto'
        } else {
          prepareAutomatic()
          director.select('auto', state.composition)
        }
        syncScenarioState(true)
        schedule()
      },
      setScenario(value) {
        if (disposed) return
        if (reduced) {
          leaveSourceAnimation('authored')
          director.selection = value
        } else {
          prepareAutomatic()
          director.select(value, state.composition)
        }
        syncScenarioState(true)
        schedule()
      },
      playScenario,
      setMotionEnabled(value) {
        if (disposed) return
        if (!isSourceAnimation() && state.motionEnabled && !value)
          pausedBones = captureBoneTransforms(model!)
        state.motionEnabled = value && !reduced
        lastTick = null
        if (state.motionEnabled) pausedBones = null
        notify()
        schedule()
      },
      playSourceAnimation() {
        if (disposed || !mocapPlayer || !mocapTimeline) return
        const from = captureBoneTransforms(model!)
        director.cancel()
        bridge = null
        pendingHeldPose = null
        pausedBones = null
        state.motionSource = sourceKind
        state.automaticPose = false
        state.motionEnabled = !reduced
        sourceBridge = reduced
          ? null
          : { from, to: captureBoneTransforms(model!), start: motionTime }
        mocapTimeline.seek(0)
        if (!reduced) mocapTimeline.play()
        lastTick = null
        syncSourceAnimationState(true)
        schedule()
      },
      setSourceAnimationPlaying(value) {
        if (disposed || !mocapTimeline || !isSourceAnimation()) return
        if (value && !reduced) {
          mocapTimeline.play()
          state.motionEnabled = true
        } else mocapTimeline.pause()
        lastTick = null
        syncSourceAnimationState(true)
        schedule()
      },
      seekSourceAnimation(seconds) {
        if (disposed || !mocapPlayer || !mocapTimeline) return
        director.cancel()
        bridge = null
        pendingHeldPose = null
        pausedBones = null
        state.motionSource = sourceKind
        state.automaticPose = false
        mocapTimeline.seek(seconds)
        lastTick = null
        syncSourceAnimationState(true)
        schedule()
      },
      setView(value) {
        if (disposed) return
        const angle = {
          front: 0,
          side: Math.PI / 2,
          back: Math.PI,
          'three-quarter': 0.48,
        }[value]
        viewRadius = camera.position.distanceTo(controls.target)
        viewFromYaw = pendingYaw ?? controls.getAzimuthalAngle()
        pendingYaw = null
        viewFromPolar = controls.getPolarAngle()
        viewToYaw = nearestViewYaw(viewFromYaw, angle)
        viewStart = reduced ? -Infinity : activeTime * 1000
        interactionUntil = activeTime + 0.8
        if (reduced) {
          camera.position
            .setFromSpherical(viewSphere.set(viewRadius, viewPolar, viewToYaw))
            .add(controls.target)
          controls.update()
        }
        schedule()
      },
      getViewState() {
        return {
          yaw: pendingYaw ?? controls.getAzimuthalAngle(),
          polar: controls.getPolarAngle(),
          radius: camera.position.distanceTo(controls.target),
        }
      },
      setLayer(value) {
        if (disposed) return
        state.layer = value
        for (const surface of surfaces) surface.setLayer(value)
        notify()
        schedule()
      },
      setPlaying(value) {
        if (disposed) return
        if (value)
          playScenario(
            director.selection === 'auto'
              ? nextAutomaticScenario(state.composition, director.active ?? director.last)
              : director.selection,
          )
        else {
          stopSequence()
          schedule()
        }
      },
      setActive(value) {
        if (disposed) return
        active = value
        lastTick = null
        visibility()
      },
      setYaw(value) {
        if (disposed) return
        // Multiple pointer samples between displayed frames only need their latest yaw.
        if (!Number.isFinite(value)) return
        pendingYaw = value
        viewStart = -Infinity
        interactionUntil = activeTime + 0.8
        schedule()
      },
      setAppearance(value) {
        if (disposed) return
        accent.value.set(appearanceColors[value])
        const tint = value === 'composition' ? null : new Color(appearanceColors[value])
        for (const surface of surfaces) surface.setAppearance(tint)
        schedule()
      },
      setComparison(value) {
        if (disposed || comparison === value) return
        comparison = value
        lastTick = null
        schedule()
      },
      setReducedMotion(value) {
        if (disposed || reduced === value) return
        reduced = value
        if (reduced) {
          physiqueStart = -Infinity
          poseStart = -Infinity
          viewStart = -Infinity
        }
        lastTick = null
        schedule()
      },
      resize,
      dispose,
    }
  } catch (error) {
    if (!disposed) console.error('InBody renderer failed', error)
    if (!disposed) unavailable()
    disposeBackend()
    return null
  }
}

/** Production and preview deliberately share geometry, animation and lifecycle code. */
export const createCharacterRenderer = createCharacterPreview
export type CharacterRenderer = CharacterPreview
export type CharacterRendererState = PreviewState
