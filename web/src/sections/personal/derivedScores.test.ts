import { describe, expect, it } from 'vitest'
import {
  DerivedBaseline,
  DerivedComponentValue,
  DerivedInputReference,
  DerivedInputWindow,
  DerivedResultVersion,
  DerivedScore,
  DerivedScoreSummary,
  DerivedScoresPage,
  componentValueLabel,
  referenceLabel,
  scoreValue,
} from './derivedScores'
import {
  captureReference,
  measurementReference,
  producerArchiveItem,
  producerCompactHomeArchiveItem,
  producerHomeArchiveItem,
  producerOwnerSleepScore,
  producerReferences,
  producerRejectedStrapScore,
  producerRhrScore,
  producerSummary,
  producerSummaryPage,
  producerWindowScore,
  resolutionReference,
} from './derivedScores.fixtures'

const score = (source = 'ring-a') =>
  DerivedScore.parse({
    definition_id: 'soul-home-readiness',
    formula_version: 'soul-v1',
    qualification: 'source_transform',
    status: 'unavailable',
    value: null,
    unit: 'index',
    components: [
      { id: 'hrv', value: null, unit: 'ms', quality: ['subtype_unknown'], synthetic: false },
    ],
    missing: ['hrv_subtype', 'session_anchor'],
    confidence: { kind: 'input_coverage', level: 'low', available_inputs: 1, required_inputs: 3 },
    coverage: { kind: 'input_presence', observed: 1, expected: 3, ratio: 1 / 3 },
    quality: [],
    context: {
      evaluation: 'day',
      date: '2026-10-09',
      time_zone: 'UTC',
      as_of: '2026-10-09T08:00:00Z',
      source_id: source,
    },
    input_revisions: [
      {
        type: 'sample',
        id: 'input',
        revision: 2,
        source_id: source,
        metric: 'heart_rate',
        observed_at: '2026-10-09T07:00:00Z',
        source_version: 1,
        override_revision: 0,
        annotation_revision: 0,
        annotation_id: null,
      },
    ],
    baseline: {
      as_of: '2026-10-09T07:00:00Z',
      source_id: source,
      components: [
        {
          id: 'hrv',
          as_of: '2026-10-09T07:00:00Z',
          window_start: '2026-10-01',
          window_end: '2026-10-08',
          points: 4,
          source_id: source,
          estimator: 'mean',
          formula_version: 'soul-v1',
          input_revisions: [measurementReference('input', source, 'heart_rate')],
        },
      ],
    },
    fingerprint: 'score-version',
    result_id: (source === 'ring-a' ? 'a' : 'b').repeat(64),
    version: 1,
    evaluated_at: '2026-10-09T09:00:00Z',
    temporal_basis: 'source_sample',
    input_adapter_version: 'adapter-v1',
    implementation_digest: 'synthetic-implementation',
  })

describe('Soul calculation envelopes', () => {
  it('round-trips actual Home current, day-history and sleep-anchor baselines without legacy fields', () => {
    const archive = producerHomeArchiveItem()
    const expected = { ...archive, score: { ...archive.score, input_windows: [] } }
    const saved = JSON.stringify(archive)
    const parsed = DerivedResultVersion.parse(archive)
    expect(parsed).toEqual(expected)
    expect(JSON.stringify(archive)).toBe(saved)
    expect(DerivedResultVersion.parse(JSON.parse(saved))).toEqual(expected)
    expect(parsed.score.definition_id).toBe('soul-home-readiness')
    expect(parsed.score.value).toBeNull()
    expect(parsed.score.baseline!.components.map((component) => component.id)).toEqual([
      'home_steps_current',
      'home_steps_day_db',
      'home_steps_anchor',
    ])
    const current = parsed.score.baseline!.components[0]!
    expect(current).toMatchObject({
      producer_version: 'soul-1.0.1230-home-activity-inputs-v1',
      missing: ['home_current_wrapper_unavailable'],
      unqualified_current_candidate: { total_steps: 1234 },
    })
    expect(current).not.toHaveProperty('formula_version')
    expect(current).not.toHaveProperty('algorithm_version')
    for (const malformed of [
      { ...current, producer_version: undefined },
      { ...current, formula_version: 'invented' },
      { ...archive.score.baseline.components[1], query_qualified: undefined },
      { ...archive.score.baseline.components[2], points: 1 },
    ])
      expect(
        DerivedResultVersion.safeParse({
          ...archive,
          score: {
            ...archive.score,
            baseline: { ...archive.score.baseline, components: [malformed] },
          },
        }).success,
      ).toBe(false)
  })
  it('round-trips a real compact Home archive with input counts, digests and windows', () => {
    const archive = producerCompactHomeArchiveItem()
    const saved = JSON.stringify(archive)
    const parsed = DerivedResultVersion.parse(archive)
    expect(parsed).toEqual(archive)
    expect(DerivedResultVersion.parse(JSON.parse(saved))).toEqual(archive)
    expect(JSON.stringify(archive)).toBe(saved)
    expect(parsed.score.input_windows.map((window) => window.count)).toEqual([96, 384])
    for (const [id, count] of [
      ['home_steps_day_db', 408],
      ['home_steps_anchor', 1],
    ] as const) {
      const baseline = parsed.score.baseline!.components.find((component) => component.id === id)
      expect(baseline).toMatchObject({
        input_count: count,
        input_digest: expect.stringMatching(/^[0-9a-f]{64}$/),
      })
      expect(baseline).not.toHaveProperty('input_ids')
      for (const malformed of [
        { ...baseline, input_count: undefined },
        { ...baseline, input_digest: undefined },
        { ...baseline, input_count: -1 },
        { ...baseline, input_count: 1.5 },
        { ...baseline, input_digest: 'not-a-sha256' },
        { ...baseline, input_ids: ['duplicate-evidence'] },
      ])
        expect(
          DerivedBaseline.safeParse({ ...archive.score.baseline, components: [malformed] }).success,
        ).toBe(false)
    }
  })
  it('preserves new Home and Screen references and immutable archived identity variants', () => {
    const activity = {
      ...measurementReference('counter', 'ring-b', 'running_steps'),
      type: 'activity_report',
      revision: 2,
      annotation_id: 'owner-exclusion',
      annotation_revision: 3,
    }
    const software = { type: 'software_context', id: 'context', revision: 1, source_id: null }
    const references = [
      activity,
      { ...software, id: 'home_steps_current:source_query', fingerprint: 'query-snapshot' },
      {
        ...software,
        id: 'soul-screen-source-default-tuning',
        producer_version: 'soul-1.0.1230-screen-pipeline-v1',
      },
      { ...software, formula_version: 'soul-1.0.1214-screen-pipeline-v1' },
      {
        type: 'source_identity',
        id: 'confirmed-link',
        revision: 4,
        source_id: 'ring-b',
        physical_id: 'ring-physical',
        confirmed: true,
      },
    ]
    const original = producerArchiveItem()
    const archive = {
      ...original,
      score: {
        ...original.score,
        input_revisions: [...original.score.input_revisions, ...references],
      },
    }
    const expected = { ...archive, score: { ...archive.score, input_windows: [] } }
    const before = JSON.stringify(archive)
    expect(DerivedResultVersion.parse(archive)).toEqual(expected)
    expect(JSON.stringify(archive)).toBe(before)
    expect(DerivedResultVersion.parse(JSON.parse(before))).toEqual(expected)
    expect(referenceLabel(DerivedInputReference.parse(activity))).toContain('Отчёт активности')
    for (const malformed of [
      software,
      { ...software, formula_version: 'old', producer_version: 'new' },
      { ...software, fingerprint: 'query', observed_at: 'invented' },
      { ...activity, annotation_revision: undefined },
      { ...activity, fingerprint: 'invented' },
    ])
      expect(DerivedInputReference.safeParse(malformed).success).toBe(false)
  })
  it('preserves actual measurement, owner preference and evaluation-context reference variants', () => {
    const references = producerReferences()
    expect(references.map((reference) => DerivedInputReference.parse(reference))).toEqual(
      references,
    )
    expect(
      DerivedInputReference.safeParse({ ...resolutionReference, metric: 'heart_rate' }).success,
    ).toBe(false)
    expect(
      DerivedInputReference.safeParse({ ...measurementReference(), observed_at: undefined })
        .success,
    ).toBe(false)
    expect(
      DerivedInputReference.safeParse({ type: 'unknown_context', id: 'invented', revision: 1 })
        .success,
    ).toBe(false)
  })
  it('parses current and archived producer-shaped scores and round-trips all captured evidence', () => {
    const current = producerSummary()
    const page = DerivedScoresPage.parse(producerSummaryPage([current]))
    expect(page.days[0]!.scores[0]).toEqual(current)
    expect(DerivedScore.parse(producerOwnerSleepScore())).toEqual({
      ...producerOwnerSleepScore(),
      input_windows: [],
    })
    const archive = producerArchiveItem()
    const expected = { ...archive, score: { ...archive.score, input_windows: [] } }
    expect(DerivedResultVersion.parse(archive)).toEqual(expected)
    expect(DerivedResultVersion.parse(JSON.parse(JSON.stringify(archive)))).toEqual(expected)
    expect(DerivedResultVersion.parse(archive).context_revisions).toEqual([
      resolutionReference,
      captureReference,
    ])
  })
  it('round-trips window commitments and rejected-only baselines', () => {
    const score = producerWindowScore()
    const saved = JSON.stringify(score)
    expect(DerivedScore.parse(score)).toEqual(score)
    expect(DerivedScore.parse(JSON.parse(saved))).toEqual(score)
    expect(JSON.stringify(score)).toBe(saved)
    expect(score.baseline.components[0]).not.toHaveProperty('input_revisions')
    expect(score.baseline.components[1]).not.toHaveProperty('as_of')
    expect(score.baseline.components[1]).not.toHaveProperty('input_digest')
  })
  it('preserves archived rejected strap evidence without an as_of or measurement windows', () => {
    const score = producerRejectedStrapScore()
    const archive = { ...producerArchiveItem(), source_scope: 'strap-a', score }
    const saved = JSON.stringify(archive)
    const expected = { ...archive, score: { ...score, input_windows: [] } }
    expect(score).not.toHaveProperty('input_windows')
    expect(score.baseline.components[0]).not.toHaveProperty('as_of')
    expect(DerivedResultVersion.parse(archive)).toEqual(expected)
    expect(DerivedResultVersion.parse(JSON.parse(saved))).toEqual(expected)
    expect(JSON.stringify(archive)).toBe(saved)
  })
  it('validates exact window keys, digest identities and nonnegative integer counts', () => {
    const window = producerWindowScore().input_windows[0]!
    expect(DerivedInputWindow.parse(window)).toEqual(window)
    const empty = {
      ...window,
      count: 0,
      first_observed_at: null,
      last_observed_at: null,
      source_ids: [null, 'ring-a'],
      digest: 'A'.repeat(64),
    }
    expect(DerivedInputWindow.parse(empty)).toEqual(empty)
    for (const malformed of [
      { ...window, type: 'sample' },
      { ...window, metric: undefined },
      { ...window, first_observed_at: undefined },
      { ...window, last_observed_at: 1 },
      { ...window, count: -1 },
      { ...window, count: 1.5 },
      { ...window, source_ids: [1] },
      { ...window, digest: 'e'.repeat(63) },
      { ...window, digest: 'g'.repeat(64) },
      { ...window, input_revisions: [] },
    ])
      expect(
        DerivedScore.safeParse({ ...producerRhrScore(), input_windows: [malformed] }).success,
      ).toBe(false)
  })
  it('requires baseline revisions or digests and retains pooled rejection commitments', () => {
    const { baseline } = producerWindowScore()
    const selected = baseline.components[0]!
    const rejected = baseline.components[1]!
    const pooled = {
      ...selected,
      unqualified_input_digest: 'a'.repeat(64),
      unqualified_count: 1,
      producer_details: { captured: true },
    }
    const rejectedPool = { ...rejected, input_digest: 'b'.repeat(64) }
    expect(DerivedBaseline.parse({ ...baseline, components: [pooled, rejectedPool] })).toEqual({
      ...baseline,
      components: [pooled, rejectedPool],
    })
    for (const malformed of [
      { ...selected, input_digest: undefined },
      { ...selected, input_digest: 'g'.repeat(64) },
      { ...pooled, unqualified_input_digest: 'a'.repeat(63) },
      { ...pooled, unqualified_count: -1 },
      { ...rejected, unqualified_input_digest: undefined },
      { ...rejected, unqualified_count: undefined },
      { ...rejected, unqualified_count: 0.5 },
      { ...rejectedPool, input_digest: 'short' },
    ])
      expect(DerivedBaseline.safeParse({ ...baseline, components: [malformed] }).success).toBe(
        false,
      )
  })
  it('keeps scalar, boolean, number-series and stage-series inputs typed without coercion', () => {
    const stages = [
      { stage: 'rem', minutes: 20 },
      { stage: 'awake', minutes: 2 },
    ]
    for (const value of [null, -0.5, true, [55, 60], stages])
      expect(DerivedComponentValue.parse(value)).toEqual(value)
    expect(componentValueLabel(true)).toBe('Да')
    expect(componentValueLabel([55, 60])).toBe('Числовой ряд · 2 значений')
    expect(componentValueLabel(DerivedComponentValue.parse(stages))).toBe(
      'Стадии сна · 2 интервалов',
    )
    expect(DerivedComponentValue.safeParse([false]).success).toBe(false)
    expect(DerivedComponentValue.safeParse([{ stage: 'unknown', minutes: 2 }]).success).toBe(false)
    expect(DerivedComponentValue.safeParse([Number.NaN]).success).toBe(false)
    const synthetic = {
      id: 'breath',
      value: 16,
      unit: '',
      synthetic: true,
      reason: 'soul_reference_default',
    }
    expect(
      DerivedScore.parse({ ...producerRhrScore(), components: [synthetic] }).components,
    ).toEqual([synthetic])
  })
  it('accepts age-reference scores and versions with a closed reason contract', () => {
    const component = {
      id: 'ide_hrv_14d',
      value: 40,
      unit: 'ms',
      synthetic: true,
      reason: 'soul_age_reference',
    }
    const score = { ...producerRhrScore(), status: 'partial', components: [component] }
    expect(DerivedScore.parse(score).components).toEqual([component])
    const archive = { ...producerArchiveItem(), score }
    expect(DerivedResultVersion.parse(archive).score.components).toEqual([component])
    expect(DerivedResultVersion.parse(JSON.parse(JSON.stringify(archive)))).toEqual({
      ...archive,
      score: { ...score, input_windows: [] },
    })
    for (const reason of ['future_reason', null, 1])
      expect(
        DerivedScore.safeParse({ ...score, components: [{ ...component, reason }] }).success,
      ).toBe(false)
    expect(
      DerivedScore.safeParse({
        ...score,
        components: [{ ...component, value: [40] }],
      }).success,
    ).toBe(false)
    expect(
      DerivedScore.safeParse({
        ...score,
        components: [{ ...component, synthetic: false }],
      }).success,
    ).toBe(false)
  })
  it('preserves a missing-night selection context without inventing a window or point count', () => {
    const score = producerRhrScore()
    const baseline = {
      as_of: score.context.as_of,
      source_id: 'ring-a',
      components: [
        {
          id: 'night_hr',
          as_of: score.context.as_of,
          formula_version: 'soul-night-window-v1',
          selection_context: {
            algorithm_version: 'soul-night-window-v1',
            target: null,
            prior_windows: [],
            missing: ['explicit_window'],
            quality: [],
          },
        },
      ],
    }
    expect(DerivedScore.parse({ ...score, baseline }).baseline).toEqual(baseline)
  })
  it('does not turn absent inputs or an unqualified cached neutral value into a score', () => {
    expect(scoreValue(score())).toBeNull()
    expect(scoreValue({ ...score(), status: 'unqualified', value: 50 })).toBeNull()
    expect(scoreValue({ ...score(), status: 'available', value: 0 })).toBe(0)
    expect(scoreValue({ ...score(), status: 'partial', value: 42 })).toBe(42)
  })
  it('retains source-bound baselines, revisions and instant-series missingness in full immutable evidence', () => {
    const point = {
      ...score('ring-a'),
      context: { ...score('ring-a').context, evaluation: 'instant' },
    }
    const first = DerivedScore.parse({
      ...score('ring-a'),
      details: { series_count: 1, series: [point] },
    })
    const second = DerivedScore.parse(score('ring-b'))
    expect([first, second].map((item) => item.baseline?.source_id)).toEqual(['ring-a', 'ring-b'])
    expect(first.input_revisions[0]!.revision).toBe(2)
    expect(first.details?.series?.[0]?.missing).toContain('session_anchor')
    expect(first.coverage.kind).toBe('input_presence')
  })
  it('requires an explicit archived summary mode and rejects fake empty full-detail fields', () => {
    const summary = producerSummary()
    expect(DerivedScoreSummary.parse(summary)).toEqual(summary)
    expect(
      DerivedScoreSummary.safeParse({
        ...summary,
        components: [],
        input_revisions: [],
        baseline: null,
      }).success,
    ).toBe(false)
    expect(
      DerivedScoreSummary.safeParse({
        ...summary,
        definition_snapshot: {
          ...summary.definition_snapshot,
          formula_version: 'different-formula',
        },
      }).success,
    ).toBe(false)
    expect(
      DerivedScoresPage.safeParse({ ...producerSummaryPage(), details_mode: undefined }).success,
    ).toBe(false)
    expect(DerivedScoresPage.parse(producerSummaryPage([])).as_of).toBeNull()
  })
})
