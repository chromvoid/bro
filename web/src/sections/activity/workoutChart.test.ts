import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import { i18n } from '@/i18n'
import WorkoutHeartRateChart from './WorkoutHeartRateChart.vue'
import { pulseIsStale, workoutPoints, type WorkoutPulse } from './workoutChart'
import { WorkoutHeartRatePage, workoutHeartRateSamples } from './workoutHeartRate'

const start = '2026-10-09T08:00:00Z'
const workout = 'dba98120-9cdd-4b65-b7af-75a747d72a88'
const samples: WorkoutPulse[] = [
  { observed_at: start, bpm: 140, quality: 'valid', phase: 'running' },
  { observed_at: '2026-10-09T08:05:00Z', bpm: 150, quality: 'valid', phase: 'running' },
  { observed_at: '2026-10-09T08:06:00Z', bpm: 110, quality: 'valid', phase: 'recovery' },
]
describe('actual workout pulse presentation', () => {
  it('does not promote an old, malformed or future reading to a current pulse', () => {
    const timestamp = Date.parse(start)
    expect(pulseIsStale(samples[0]!, timestamp + 10_000)).toBe(false)
    expect(pulseIsStale(samples[0]!, timestamp + 16_000)).toBe(true)
    expect(pulseIsStale(samples[0]!, timestamp - 1)).toBe(true)
    expect(pulseIsStale({ observed_at: 'broken' }, timestamp)).toBe(true)
  })
  it('splits running and recovery, draws actual dots and never bridges the five-minute gap', () => {
    expect(workoutPoints(samples, 'running')).toHaveLength(2)
    const wrapper = mount(WorkoutHeartRateChart, {
      props: { samples, timezone: 'UTC' },
      global: { plugins: [i18n] },
    })
    expect(wrapper.findAll('svg path')).toHaveLength(0)
    expect(wrapper.findAll('svg circle')).toHaveLength(3)
    expect(wrapper.text()).toContain('Пульс после завершения')
    expect(wrapper.findAll('[style]')).toHaveLength(0)
    wrapper.unmount()
  })
  it('derives recovery from the report boundary and drops invalid raw results', () => {
    const page = WorkoutHeartRatePage.parse({
      workout_id: workout,
      source_id: '2b0a091f-c1fb-4b0b-8b9a-a84a752bb01c',
      report_ref: { id: '423164a7-7f6e-4d44-8f8b-426a1a66caf5', revision: 1 },
      fingerprint: 'a'.repeat(64),
      workout: {
        start_at: start,
        end_at: '2026-10-09T08:00:30Z',
        timezone: 'UTC',
        status: 'completed',
        trigger: 'manual',
        recovery_end_at: null,
        stop_reason: 'manual',
      },
      chunks: [
        {
          external_id: 'synthetic-chunk',
          workout_id: workout,
          sequence: 0,
          start_at: start,
          end_at: '2026-10-09T08:00:59Z',
          time_basis: 'phone_monotonic',
          samples: [
            { offset_ms: 0, bpm: 140, quality: 'valid' },
            { offset_ms: 30_000, bpm: 115, quality: 'unknown' },
            { offset_ms: 31_000, bpm: 0, quality: 'unknown' },
            { offset_ms: 32_000, bpm: 300, quality: 'unknown' },
            { offset_ms: 33_000, bpm: 250, quality: 'invalid' },
          ],
          gaps: [],
          clock_anchor: { utc_at: start, elapsed_realtime_ms: 0, boot_id: 'synthetic-boot' },
        },
      ],
      next_cursor: null,
    })
    const results = workoutHeartRateSamples([page])
    expect(results.map((sample) => [sample.bpm, sample.phase, sample.quality])).toEqual([
      [140, 'running', 'valid'],
      [115, 'recovery', 'unknown'],
      [0, 'recovery', 'unknown'],
      [300, 'recovery', 'unknown'],
    ])
    expect(results[2]!.observed_at).toBe('2026-10-09T08:00:31.000Z')
    expect(workoutPoints(results, 'recovery')).toEqual([])
    expect(page.chunks[0]!.samples.map((sample) => sample.quality)).toEqual([
      'valid',
      'unknown',
      'unknown',
      'unknown',
      'invalid',
    ])
  })
  it('keeps generic activity separate from running and excludes samples inside recorded pauses', () => {
    const page = WorkoutHeartRatePage.parse({
      workout_id: workout,
      source_id: '2b0a091f-c1fb-4b0b-8b9a-a84a752bb01c',
      report_ref: { id: '423164a7-7f6e-4d44-8f8b-426a1a66caf5', revision: 1 },
      fingerprint: 'a'.repeat(64),
      workout: {
        kind: 'workouts',
        sport_type: 'boxing',
        start_at: start,
        end_at: '2026-10-09T08:00:30Z',
        timezone: 'UTC',
        status: 'completed',
        trigger: 'manual',
        pauses: [{ start_at: '2026-10-09T08:00:10Z', end_at: '2026-10-09T08:00:20Z' }],
      },
      chunks: [
        {
          external_id: 'synthetic-paused-chunk',
          workout_id: workout,
          sequence: 0,
          start_at: start,
          end_at: '2026-10-09T08:00:30Z',
          time_basis: 'phone_monotonic',
          samples: [
            { offset_ms: 0, bpm: 140, quality: 'valid' },
            { offset_ms: 15_000, bpm: 145, quality: 'valid' },
            { offset_ms: 20_000, bpm: 150, quality: 'valid' },
            { offset_ms: 30_000, bpm: 115, quality: 'valid' },
          ],
          gaps: [],
          clock_anchor: { utc_at: start, elapsed_realtime_ms: 0, boot_id: 'synthetic' },
        },
      ],
      next_cursor: null,
    })
    const results = workoutHeartRateSamples([page])
    expect(results.map((sample) => [sample.bpm, sample.phase])).toEqual([
      [140, 'workout'],
      [150, 'workout'],
      [115, 'recovery'],
    ])
    expect(page.chunks[0]!.samples).toHaveLength(4)
    const wrapper = mount(WorkoutHeartRateChart, {
      props: { samples: results, timezone: 'UTC' },
      global: { plugins: [i18n] },
    })
    expect(wrapper.text()).toContain('Пульс на тренировке')
    expect(wrapper.text()).not.toContain('Пульс на пробежке')
    wrapper.unmount()
  })
  it('preserves burst observations with the same timestamp in their actual order', () => {
    expect(
      workoutPoints([samples[0]!, { ...samples[0]!, bpm: 141 }], 'running').map((point) => point.y),
    ).toEqual([140, 141])
  })
  it('withholds unknown zeros and outliers while keeping valid dots independent', () => {
    const actual: WorkoutPulse[] = [
      samples[0]!,
      { observed_at: '2026-10-09T08:00:01Z', bpm: 0, quality: 'unknown', phase: 'running' },
      { observed_at: '2026-10-09T08:00:02Z', bpm: 300, quality: 'unknown', phase: 'running' },
      samples[1]!,
    ]
    expect(workoutPoints(actual, 'running').map((point) => point.y)).toEqual([140, 150])
    const wrapper = mount(WorkoutHeartRateChart, {
      props: { samples: actual, timezone: 'UTC' },
      global: { plugins: [i18n] },
    })
    expect(wrapper.findAll('svg circle')).toHaveLength(2)
    expect(wrapper.findAll('svg path')).toHaveLength(0)
    const warning = wrapper.get('[data-testid="workout-heart-rate-quality-warning"]')
    expect(warning.attributes('role')).toBe('status')
    expect(warning.text()).toContain('не показаны на графике')
    expect(wrapper.find('[data-testid="workout-heart-rate-withheld"]').exists()).toBe(false)
    expect(actual[1]!.bpm).toBe(0)
    expect(actual[2]!.bpm).toBe(300)
    wrapper.unmount()
  })
  it('explains a wholly unconfirmed series without treating it as no received samples', () => {
    const actual: WorkoutPulse[] = [
      { observed_at: start, bpm: 0, quality: 'unknown', phase: 'running' },
    ]
    const wrapper = mount(WorkoutHeartRateChart, {
      props: { samples: actual, timezone: 'UTC' },
      global: { plugins: [i18n] },
    })
    expect(wrapper.find('svg').exists()).toBe(false)
    expect(wrapper.get('[data-testid="workout-heart-rate-withheld"]').text()).toContain(
      'Подтверждённых показаний пульса для графика нет',
    )
    expect(wrapper.text()).not.toContain('Полученных показаний пульса нет')
    wrapper.unmount()
  })
})
