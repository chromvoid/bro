import { DirectionalLight, Group, HemisphereLight, type ColorRepresentation } from 'three/webgpu'
import type { ColorToken } from '../../../design/colors'

/** Production studio lighting preserves anatomical shading while following a full body orbit. */
export function createStudioLighting(color: (token: ColorToken) => ColorRepresentation) {
  const group = new Group()
  group.name = 'Body studio lighting'
  const ambient = new HemisphereLight(color('--body-light-sky'), color('--body-light-ground'), 0.68)
  const key = new DirectionalLight(color('--body-light-key'), 3.2)
  const fill = new DirectionalLight(color('--body-light-fill'), 0.8)
  const rim = new DirectionalLight(color('--body-light-rim'), 2.1)
  key.position.set(-3.8, 6, 4)
  fill.position.set(4, 2, 3)
  rim.position.set(2.5, 3, -4)
  key.target.position.set(0, 1.4, 0)
  key.castShadow = true
  key.shadow.mapSize.set(1536, 1536)
  key.shadow.camera.left = -3
  key.shadow.camera.right = 3
  key.shadow.camera.top = 4
  key.shadow.camera.bottom = -2
  key.shadow.camera.near = 0.5
  key.shadow.camera.far = 18
  key.shadow.bias = -0.00015
  key.shadow.normalBias = 0.012
  // Three 0.186.1 rotates five PCF taps per pixel. A wide radius compares distant
  // points on sloping skin at one depth, exposing that pattern as self-shadow acne.
  // One texel retains filtered contact shadows inside the existing normal offset.
  key.shadow.radius = 1
  key.shadow.intensity = 0.72
  group.add(ambient, key, fill, rim, key.target, fill.target, rim.target)
  let disposed = false
  return {
    group,
    key,
    fill,
    rim,
    update(yaw: number) {
      if (!Number.isFinite(yaw)) throw new Error('Invalid studio view angle')
      // Keep the front/back rig steady during oblique inspection: a camera-locked
      // key moves towards the torso's front and washes out its surface relief.
      // This smooth, bounded lag retains the original cardinal-view lighting and
      // stays within half a radian of the camera throughout a complete orbit.
      group.rotation.y = yaw - Math.sin(yaw) * Math.cos(yaw)
    },
    dispose() {
      if (disposed) return
      disposed = true
      key.shadow.dispose()
    },
  }
}
