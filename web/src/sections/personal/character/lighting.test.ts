import { expect, it, vi } from 'vitest'
import { Color, PerspectiveCamera, Vector3 } from 'three/webgpu'
import { createStudioLighting } from './lighting'

function studioProbe() {
  const lighting = createStudioLighting(() => new Color().setScalar(0.5))
  const camera = new PerspectiveCamera()
  const lights = [lighting.key, lighting.fill, lighting.rim]
  const measure = (yaw: number) => {
    lighting.update(yaw)
    lighting.group.updateMatrixWorld(true)
    const target = lighting.key.target.getWorldPosition(new Vector3())
    camera.position.set(6 * Math.sin(yaw), target.y, 6 * Math.cos(yaw))
    camera.lookAt(target)
    camera.updateMatrixWorld(true)
    return lights.map((light) => {
      const at = light.target.getWorldPosition(new Vector3())
      const direction = light.getWorldPosition(new Vector3()).sub(at)
      return {
        at,
        distance: direction.length(),
        world: direction.clone().normalize(),
        direction: direction.transformDirection(camera.matrixWorldInverse),
      }
    })
  }
  return { lighting, lights, measure }
}

it('retains studio light directions and targets at cardinal inspection views', () => {
  const { lighting, lights, measure } = studioProbe()
  const initial = measure(0)
  expect(initial[0]!.at.y).toBeGreaterThan(initial[1]!.at.y)
  expect(initial[1]!.at.distanceTo(initial[2]!.at)).toBeLessThan(1e-12)
  expect(initial[0]!.direction.x).toBeLessThan(0)
  expect(initial[1]!.direction.x).toBeGreaterThan(0)
  expect(initial[2]!.direction.z).toBeLessThan(0)
  const angles = [-4 * Math.PI, -Math.PI, -Math.PI / 2, Math.PI / 2, Math.PI, 4 * Math.PI]
  for (const yaw of angles) {
    const values = measure(yaw)
    values.forEach((value, i) => {
      expect(value.at.distanceTo(initial[i]!.at)).toBeLessThan(1e-12)
      expect(value.distance).toBeCloseTo(initial[i]!.distance, 12)
      expect(value.direction.distanceTo(initial[i]!.direction)).toBeLessThan(1e-12)
    })
  }
  expect(lights.filter((light) => light.castShadow)).toEqual([lighting.key])
  lighting.dispose()
})

it('preserves raking light on torso relief in the three-quarter view', () => {
  const { lighting, measure } = studioProbe()
  const initial = measure(0)[0]!.world
  const oblique = measure(0.48)[0]!.world
  // Opposite shallow planes of the abdomen should retain almost the same key
  // contrast as the front rig. A camera-locked key turns towards the torso and
  // loses more than half of this directional contrast at the 3/4 preset.
  const left = new Vector3(-0.3, 0, 1).normalize()
  const right = new Vector3(0.3, 0, 1).normalize()
  const frontContrast = left.dot(initial) - right.dot(initial)
  const obliqueContrast = left.dot(oblique) - right.dot(oblique)
  expect(frontContrast).toBeGreaterThan(0)
  expect(obliqueContrast).toBeGreaterThan(frontContrast * 0.9)
  expect(obliqueContrast).toBeLessThan(frontContrast * 1.1)
  // The lamp still contributes light to both planes instead of obtaining
  // stronger contrast by leaving one plane completely unlit.
  expect(left.dot(oblique)).toBeGreaterThan(0)
  expect(right.dot(oblique)).toBeGreaterThan(0)
  lighting.dispose()
})

it('keeps all sides readable through smooth, repeatable full camera turns', () => {
  const { lighting, measure } = studioProbe()
  const step = Math.PI / 96
  let previous = measure(-2 * Math.PI)
  for (let sample = 1; sample <= 384; sample++) {
    const yaw = -2 * Math.PI + sample * step
    const values = measure(yaw)
    // In camera space the key and fill stay on opposite sides in front of the
    // subject, while the rim remains behind it throughout the entire orbit.
    expect(values[0]!.direction.x).toBeLessThan(0)
    expect(values[0]!.direction.z).toBeGreaterThan(0)
    expect(values[1]!.direction.x).toBeGreaterThan(0)
    expect(values[1]!.direction.z).toBeGreaterThan(0)
    expect(values[2]!.direction.z).toBeLessThan(0)
    const nextTurn = measure(yaw + 2 * Math.PI)
    values.forEach((value, i) => {
      expect(value.at.distanceTo(previous[i]!.at)).toBeLessThan(1e-12)
      expect(value.distance).toBeCloseTo(previous[i]!.distance, 12)
      expect(value.world.angleTo(previous[i]!.world)).toBeLessThan(step * 2.1)
      expect(value.world.distanceTo(nextTurn[i]!.world)).toBeLessThan(1e-12)
    })
    previous = values
  }
  // A small drag around the front/back inspection views must not swing the
  // rig or cause a visible lighting twitch as the azimuth wraps at the back.
  for (const yaw of [0, -Math.PI, Math.PI]) {
    const before = measure(yaw - 0.01)
    const after = measure(yaw + 0.01)
    before.forEach((value, i) => {
      expect(value.world.angleTo(after[i]!.world)).toBeLessThan(0.00001)
    })
  }
  lighting.dispose()
})

it('rejects a non-finite view without moving the last valid studio orientation', () => {
  const lighting = createStudioLighting(() => new Color().setScalar(0.5))
  lighting.update(0.6)
  const rotation = lighting.group.quaternion.clone()
  for (const yaw of [NaN, Infinity, -Infinity]) {
    expect(() => lighting.update(yaw)).toThrow('Invalid studio view angle')
    expect(lighting.group.quaternion.equals(rotation)).toBe(true)
  }
  lighting.dispose()
})

it('keeps the filtered shadow footprint inside the offset of a planar receiver', () => {
  const lighting = createStudioLighting(() => new Color().setScalar(0.5))
  const { shadow } = lighting.key
  const camera = shadow.camera
  // Three 0.186.1 PCF uses five Vogel disk taps plus bilinear comparison filtering.
  // Bound the outer tap and its hardware texel support in world units. On a plane
  // with its geometric normal, a larger normal offset prevents taps seeing that
  // same plane as an occluder even when the light meets it at a shallow angle.
  // Mapped normals and curved surfaces still require the native GPU art review.
  const width = camera.right - camera.left
  const height = camera.top - camera.bottom
  const texelWidth = width / shadow.mapSize.width
  const texelHeight = height / shadow.mapSize.height
  // The filter uses radius / mapSize.width for both UV axes.
  const outerTap =
    (Math.sqrt(4.5 / 5) * shadow.radius * Math.max(width, height)) / shadow.mapSize.width
  const support = outerTap + Math.hypot(texelWidth, texelHeight)
  expect(support).toBeGreaterThan(0)
  expect(support).toBeLessThan(shadow.normalBias)
  // Keep physical self-shadowing and filtered ground contact instead of hiding
  // the artifact by disabling the light's shadows or their contribution.
  expect(lighting.key.castShadow).toBe(true)
  expect(shadow.radius).toBeGreaterThan(0)
  expect(shadow.intensity).toBeGreaterThan(0)
  expect(shadow.intensity).toBeLessThanOrEqual(1)
  lighting.dispose()
})

it('releases the only shadow allocation once when loading is cancelled or the view is destroyed', () => {
  const lighting = createStudioLighting(() => new Color().setScalar(0.5))
  const release = vi.spyOn(lighting.key.shadow, 'dispose')
  lighting.dispose()
  lighting.dispose()
  expect(release).toHaveBeenCalledTimes(1)
})
