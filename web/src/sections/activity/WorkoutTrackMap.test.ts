import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import { i18n } from '@/i18n'
import WorkoutTrackMap from './WorkoutTrackMap.vue'
import type { TrackPoint } from './workoutTrack'

const point = (longitude: number): TrackPoint => ({
  at: '2026-10-09T08:00:00Z',
  latitude: 10,
  longitude,
  accuracy_meters: 5,
  quality: 'valid',
})

describe('accessible workout map', () => {
  it('keeps server gaps and recovery separate, names the SVG and credits the tile source', () => {
    const wrapper = mount(WorkoutTrackMap, {
      global: { plugins: [i18n] },
      props: {
        segments: [
          { context: 'running', quality: 'valid', points: [point(179.999), point(-179.999)] },
          { context: 'running', quality: 'valid', points: [point(-179.998)] },
          { context: 'recovery', quality: 'valid', points: [point(-179.997), point(-179.996)] },
        ],
      },
    })
    expect(wrapper.get('svg').attributes('role')).toBe('img')
    expect(wrapper.get('svg').attributes('aria-label')).toContain('Маршрут тренировки')
    expect(wrapper.findAll('polyline')).toHaveLength(3)
    expect(wrapper.findAll('polyline')[2]!.attributes('stroke-dasharray')).toBe('8 6')
    expect(wrapper.get('a').attributes('href')).toBe('https://www.openstreetmap.org/copyright')
    expect(wrapper.find('[style]').exists()).toBe(false)
    expect(wrapper.findAll('image').length).toBeLessThanOrEqual(12)
    wrapper.unmount()
  })
})
