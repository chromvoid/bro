import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { z } from '@/core/zod'
import UsageDetails from './UsageDetails.vue'
import { formatUsageNumber, parseUsage, UsageField, UsageSummary, usageRows } from './usage'

vi.mock('vue-i18n', () => ({
  useI18n: () => ({
    locale: { value: 'ru-RU' },
    t: (key: string, values?: { value: string }) => (values ? `${values.value} мс` : key),
  }),
}))

describe('public model usage', () => {
  it('preserves measured zero and does not infer missing counts or totals', () => {
    const summary = UsageSummary.parse({
      calls: 1,
      input_total_tokens: 12,
      cache_read_tokens: 0,
      output_tokens: 4,
      total_tokens: null,
    })
    expect(summary.input_uncached_tokens).toBeNull()
    expect(summary.cache_read_tokens).toBe(0)
    expect(summary.cache_write_tokens).toBeNull()
    expect(summary.total_tokens).toBeNull()
    expect(summary.model_duration_ms).toBeNull()
    expect(formatUsageNumber(null, 'ru-RU')).toBeNull()
    expect(formatUsageNumber(0, 'ru-RU')).toBe('0')
  })

  it('keeps reported totals and summed call time instead of reconstructing them', () => {
    const summary = UsageSummary.parse({
      calls: 2,
      input_total_tokens: 10,
      input_uncached_tokens: 3,
      cache_read_tokens: 7,
      output_tokens: 2,
      total_tokens: 19,
      model_duration_ms: 1010.5,
      first_text_ms: 800,
      first_public_text_ms: 119197,
    })
    expect(summary.total_tokens).toBe(19)
    expect(usageRows(summary).find((row) => row.field === 'model_duration_ms')).toMatchObject({
      label: 'modelDuration',
      unit: 'milliseconds',
      value: 1010.5,
    })
    expect(summary.first_public_text_ms).toBe(119197)
    // One call's own time to first text is not shown as the reply's.
    expect(summary).not.toHaveProperty('first_text_ms')
    expect(usageRows(summary).find((row) => row.label === 'firstPublicText')?.value).toBe(119197)
  })

  it('projects an allowlist without provider metadata or arbitrary raw metrics', () => {
    const summary = parseUsage({
      calls: 0,
      cost: 17,
      raw: { private_provider_data: 'private' },
      provider: 'private',
      account_id: 'private',
    })!
    expect(Object.keys(summary)).toHaveLength(9)
    expect(summary).not.toHaveProperty('raw')
    expect(summary).not.toHaveProperty('provider')
    expect(summary).not.toHaveProperty('account_id')
    expect(usageRows(summary).some((row) => String(row.field) === 'cost')).toBe(false)
  })

  it('treats invalid or missing optional metrics as unavailable instead of breaking details', () => {
    for (const value of [
      undefined,
      null,
      [],
      'metrics',
      { calls: '2' },
      { calls: 0.5 },
      { total_tokens: -1 },
      { output_tokens: Infinity },
      { calls: NaN },
      { model_duration_ms: -1 },
      { first_public_text_ms: Infinity },
      { input_total_tokens: Number.MAX_SAFE_INTEGER + 1 },
    ]) {
      expect(parseUsage(value)).toBeNull()
    }
    expect(usageRows(null)).toEqual([])
    expect(UsageSummary.parse({}).calls).toBeNull()
  })

  it('shows unknown values distinctly from zero in the reusable definition list', () => {
    const wrapper = mount(UsageDetails, {
      props: {
        usage: UsageSummary.parse({ calls: 0, output_tokens: 0, first_public_text_ms: 0 }),
      },
    })
    expect(wrapper.get('dl').attributes('aria-label')).toBe('assistant.usage.title')
    expect(wrapper.get('h3').text()).toBe('assistant.usage.title')
    expect(wrapper.text()).toContain('assistant.usage.note')
    expect(wrapper.get('[data-usage-metric="calls"] dd').text()).toBe('0')
    expect(wrapper.get('[data-usage-metric="output_tokens"] dd').text()).toBe('0')
    expect(wrapper.get('[data-usage-metric="first_public_text_ms"] dd').text()).toBe('0 мс')
    expect(wrapper.get('[data-usage-metric="total_tokens"] dd').text()).toBe(
      'assistant.usage.unknown',
    )
  })

  it('renders no invented metrics when the turn has no usage summary', () => {
    const wrapper = mount(UsageDetails, { props: { usage: null } })
    expect(wrapper.find('dl').exists()).toBe(false)
  })

  it('preserves durable receipts in legacy envelopes and when optional metrics are malformed', () => {
    const envelope = z.object({ usage: UsageField, receipts: z.array(z.string()) })
    expect(envelope.parse({ receipts: ['saved'] })).toEqual({
      usage: null,
      receipts: ['saved'],
    })
    expect(envelope.parse({ usage: { total_tokens: 'unknown' }, receipts: ['saved'] })).toEqual({
      usage: null,
      receipts: ['saved'],
    })
  })
})
