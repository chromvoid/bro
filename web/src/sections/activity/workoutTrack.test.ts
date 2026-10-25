import { describe, expect, it } from 'vitest'
import { mercator, projectSegment, trackView, visibleTiles, type TrackPoint } from './workoutTrack'

const point = (latitude: number, longitude: number): TrackPoint => ({
  at: '2026-10-09T08:00:00Z',
  latitude,
  longitude,
  accuracy_meters: 5,
  quality: 'valid',
})

describe('workout map viewport', () => {
  it('fits a short crossing of the dateline without a world-spanning line', () => {
    const points = [point(10, 179.999), point(10.001, -179.999)]
    const view = trackView(points)!
    const projected = projectSegment(points, view.zoom, view.x)
    expect(view.zoom).toBeGreaterThan(10)
    expect(Math.abs(projected[1]!.x - projected[0]!.x)).toBeLessThan(560)
    expect(visibleTiles(view).length).toBeLessThanOrEqual(12)
    expect(visibleTiles(view).every((tile) => /^\/v1\/maps\/osm\//.test(tile.href))).toBe(true)
  })
  it.each([90, -90])(
    'clamps polar latitude %s to finite map coordinates and valid tile rows',
    (latitude) => {
      const view = trackView([point(latitude, 180)])!
      const projected = mercator(point(latitude, 180), view.zoom)
      expect(Number.isFinite(projected.y)).toBe(true)
      expect(projected.y).toBeGreaterThanOrEqual(0)
      expect(projected.y).toBeLessThanOrEqual(256 * 2 ** view.zoom)
      for (const tile of visibleTiles(view)) {
        const [zoom, x, y] = tile.href.slice('/v1/maps/osm/'.length, -4).split('/').map(Number)
        expect(x).toBeGreaterThanOrEqual(0)
        expect(x).toBeLessThan(2 ** zoom!)
        expect(y).toBeGreaterThanOrEqual(0)
        expect(y).toBeLessThan(2 ** zoom!)
      }
    },
  )
  it('fetches only intersecting tiles even after longitude wrapping or large panning', () => {
    const tiles = visibleTiles({ zoom: 3, x: -20_000, y: 512 })
    expect(tiles.length).toBeLessThanOrEqual(12)
    for (const tile of tiles) {
      expect(tile.x).toBeLessThan(640)
      expect(tile.x + 256).toBeGreaterThan(0)
      expect(tile.y).toBeLessThan(360)
      expect(tile.y + 256).toBeGreaterThan(0)
    }
    expect(visibleTiles({ zoom: 3, x: 512, y: -10_000 })).toEqual([])
  })
  it('has no viewport without coordinates and does not synthesize a point', () => {
    expect(trackView([])).toBeNull()
    expect(projectSegment([], 10)).toEqual([])
  })
})

it('never joins either side of preserved pause points, keeping raw points untouched', async () => {
  const { drawableSegments, TrackPoint } = await import('./workoutTrack')
  const point = (longitude: number, paused = false) =>
    TrackPoint.parse({
      at: '2026-10-09T08:00:00Z',
      latitude: 55,
      longitude,
      accuracy_meters: 5,
      quality: 'valid',
      paused,
    })
  const original = [
    {
      context: 'workout' as const,
      quality: 'valid',
      points: [point(37), point(37.01, true), point(37.02)],
    },
  ]
  expect(drawableSegments(original).map((segment) => segment.points.length)).toEqual([1, 1])
  expect(original[0]!.points).toHaveLength(3)
  expect(drawableSegments([{ ...original[0]!, quality: 'paused' }])).toHaveLength(0)
  expect(
    drawableSegments([
      { ...original[0]!, quality: 'unknown', points: [point(37), point(37.01)] },
    ]).map((segment) => segment.points.length),
  ).toEqual([1, 1])
})
