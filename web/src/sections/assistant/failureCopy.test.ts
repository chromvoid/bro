import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import { i18n } from '@/i18n'
import ExchangeItem from './ExchangeItem.vue'
import { toExchange } from './model'

const global = {
  plugins: [i18n],
  stubs: {
    ReplyBlock: true,
    ActivityPanel: true,
    Button: { template: '<button><slot /></button>' },
    RouterLink: { props: ['to'], template: '<a><slot /></a>' },
  },
}

describe('failure reasons and structured outcomes', () => {
  for (const effects of ['none', 'confirmed'] as const)
    it.each([
      null,
      'harness_interrupted',
      'model_unavailable',
      'model_auth_required',
      'model_identity_changed',
      'model_refused',
      'model_content_filtered',
      'model_context_limit',
      'step_limit',
      'turn_deadline',
    ])(
      'keeps %s failure copy free of recovery instructions when effects are ' + effects,
      (error) => {
        const exchange = toExchange({
          id: 'message',
          created_at: '2026-10-05T09:00:00Z',
          text: 'Создай задачу',
          state: 'failed',
          error,
          turns: [
            {
              id: 'turn',
              state: 'failed',
              result: {
                execution: { effects, completion: 'failed', safe_to_resubmit: effects === 'none' },
              },
            },
          ],
        })
        const wrapper = mount(ExchangeItem, { props: { exchange, zone: 'UTC' }, global })
        const status = wrapper.get('[data-testid="failed"]')
        expect(status.text()).not.toContain('Проверьте')
        expect(status.text()).not.toContain('Попробуйте')
        expect(status.text()).not.toContain('Ничего не записано')
        expect(
          status.findAll('button').some((button) => button.text() === 'Проверить результат'),
        ).toBe(false)
        if (effects === 'none') {
          expect(status.text()).toContain('Изменений нет.')
          expect(status.text()).toContain('Изменить запрос')
        } else {
          expect(status.text()).toContain('Подтверждённые изменения сохранены.')
          expect(status.text()).not.toContain('Изменений нет.')
          expect(status.text()).not.toContain('Изменить запрос')
        }
      },
    )

  it.each(['outcome_pending', 'harness_interrupted'])(
    'retains result checking for an unknown outcome after %s',
    async (error) => {
      const exchange = toExchange({
        id: 'message',
        created_at: '2026-10-05T09:00:00Z',
        text: 'Создай задачу',
        state: 'failed',
        error,
        turns: [
          {
            id: 'turn',
            state: 'failed',
            result: {
              execution: { effects: 'unknown', completion: 'partial', safe_to_resubmit: true },
            },
          },
        ],
      })
      const wrapper = mount(ExchangeItem, { props: { exchange, zone: 'UTC' }, global })
      const status = wrapper.get('[data-testid="failed"]')
      expect(status.text()).toContain('Результат изменений пока неизвестен')
      expect(status.text()).not.toContain('Изменений нет.')
      expect(status.text()).not.toContain('Изменить запрос')
      if (error === 'outcome_pending')
        expect(status.text()).toContain('Проверьте результат — сервер')
      const check = status
        .findAll('button')
        .find((button) => button.text() === 'Проверить результат')!
      await check.trigger('click')
      expect(wrapper.emitted('check')).toEqual([[exchange.replies[0]]])
      expect(wrapper.emitted('restore')).toBeUndefined()
    },
  )
})
