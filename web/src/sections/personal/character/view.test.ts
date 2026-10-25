import { expect, it } from 'vitest'
import {
  Bone,
  BoxGeometry,
  Float32BufferAttribute,
  Group,
  Mesh,
  MeshBasicMaterial,
  Skeleton,
  SkinnedMesh,
  Uint16BufferAttribute,
  Vector3,
} from 'three/webgpu'
import { bodyFramingBounds, bodyOrbitAxis, nearestViewYaw, normalizeBodyFrame } from './view'

it('chooses short preset transitions without losing accumulated full turns', () => {
  const start = 12 * Math.PI + 3
  const target = nearestViewYaw(start, -3)
  expect(target - start).toBeCloseTo(2 * Math.PI - 6)
  expect(Math.sin(target)).toBeCloseTo(Math.sin(-3))
  expect(Math.abs(nearestViewYaw(start, 0) - start)).toBeLessThanOrEqual(Math.PI)
})

it('frames only the body surface even when accessories are attached under its head', () => {
  const geometry = new BoxGeometry(1, 3, 1)
  const count = geometry.getAttribute('position').count
  geometry.setAttribute('skinIndex', new Uint16BufferAttribute(Array(count * 4).fill(0), 4))
  geometry.setAttribute(
    'skinWeight',
    new Float32BufferAttribute(
      Array.from({ length: count * 4 }, (_, i) => (i % 4 === 0 ? 1 : 0)),
      4,
    ),
  )
  const body = new SkinnedMesh(geometry, new MeshBasicMaterial())
  const head = new Bone()
  body.add(head)
  body.bind(new Skeleton([head]))
  const hair = new Mesh(new BoxGeometry(5, 50, 5), new MeshBasicMaterial())
  head.add(hair)
  body.position.y = 4
  body.updateMatrixWorld(true)
  const bounds = bodyFramingBounds([body])
  expect(bounds.max.y - bounds.min.y).toBeCloseTo(3)
  expect(bounds.min.y).toBeCloseTo(2.5)
})

it.each(['skinned', 'rigid'])(
  'frames a separate %s head without including its eyes or beard children',
  (kind) => {
    const skinned = (geometry: BoxGeometry) => {
      const count = geometry.getAttribute('position').count
      geometry.setAttribute('skinIndex', new Uint16BufferAttribute(Array(count * 4).fill(0), 4))
      geometry.setAttribute(
        'skinWeight',
        new Float32BufferAttribute(
          Array.from({ length: count * 4 }, (_, i) => (i % 4 === 0 ? 1 : 0)),
          4,
        ),
      )
      const mesh = new SkinnedMesh(geometry, new MeshBasicMaterial())
      const bone = new Bone()
      mesh.add(bone)
      mesh.bind(new Skeleton([bone]))
      return mesh
    }
    const body = skinned(new BoxGeometry(1, 3, 1))
    body.position.y = 4
    const headGeometry = new BoxGeometry(0.8, 1, 0.8)
    const head =
      kind === 'skinned' ? skinned(headGeometry) : new Mesh(headGeometry, new MeshBasicMaterial())
    head.position.y = 6
    head.add(
      new Mesh(new BoxGeometry(5, 50, 5), new MeshBasicMaterial()),
      new Mesh(new BoxGeometry(50, 5, 50), new MeshBasicMaterial()),
    )
    const root = new Group()
    root.add(body, head)
    root.updateMatrixWorld(true)
    const bounds = bodyFramingBounds([body, head])
    expect(bounds.min.y).toBeCloseTo(2.5)
    expect(bounds.max.y).toBeCloseTo(6.5)
    expect(bounds.max.x - bounds.min.x).toBeCloseTo(1)
    expect(head.morphTargetInfluences?.length ?? 0).toBe(0)
  },
)

it('centers the rest rig axis through scene transforms without using asymmetric surfaces or accessories', () => {
  const model = new Group()
  model.position.set(8, 6, -4)
  model.scale.setScalar(2)
  const rig = new Group()
  rig.position.set(0.4, 0, -0.2)
  rig.rotation.y = Math.PI / 2
  const pelvis = new Bone()
  pelvis.name = 'AuthoredPelvis'
  pelvis.position.set(0.3, 1, 0.1)
  const head = new Bone()
  head.position.y = 1
  pelvis.add(head)
  rig.add(pelvis)
  const geometry = new BoxGeometry(1, 3, 0.6).translate(0, 1.5, 1.2)
  const count = geometry.getAttribute('position').count
  geometry.setAttribute('skinIndex', new Uint16BufferAttribute(Array(count * 4).fill(0), 4))
  geometry.setAttribute(
    'skinWeight',
    new Float32BufferAttribute(
      Array.from({ length: count * 4 }, (_, i) => (i % 4 === 0 ? 1 : 0)),
      4,
    ),
  )
  const body = new SkinnedMesh(geometry, new MeshBasicMaterial())
  model.add(rig, body)
  model.updateMatrixWorld(true)
  body.bind(new Skeleton([head, pelvis]))
  head.add(new Mesh(new BoxGeometry(50, 50, 50), new MeshBasicMaterial()))
  model.updateMatrixWorld(true)
  const bounds = bodyFramingBounds([body])
  const axis = bodyOrbitAxis([body])
  expect(axis.x).toBeCloseTo(9)
  expect(axis.y).toBeCloseTo(8)
  expect(axis.z).toBeCloseTo(-5)
  expect(bounds.getCenter(new Vector3()).z).toBeCloseTo(-1.6)
  const target = normalizeBodyFrame(model, bounds, axis)
  expect(target.toArray()).toEqual([0, 1.625, 0])
  const centeredAxis = pelvis.getWorldPosition(new Vector3())
  expect(centeredAxis.x).toBeCloseTo(0)
  expect(centeredAxis.z).toBeCloseTo(0)
  const normalizedBounds = bodyFramingBounds([body])
  expect(normalizedBounds.min.y).toBeCloseTo(0)
  expect(normalizedBounds.max.y).toBeCloseTo(3.25)
})

it('uses only anatomical bounds when a rigid body has no shared rig root', () => {
  const body = new Mesh(new BoxGeometry(1, 3, 0.6), new MeshBasicMaterial())
  body.position.set(3, 4, 5)
  body.add(new Mesh(new BoxGeometry(50, 50, 50), new MeshBasicMaterial()))
  body.updateMatrixWorld(true)
  expect(bodyOrbitAxis([body]).toArray()).toEqual([3, 4, 5])
})
