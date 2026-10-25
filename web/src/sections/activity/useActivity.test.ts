import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, ref } from 'vue'
import { describe, expect, it, vi } from 'vitest'
import { useExerciseProgress } from './useActivity'

const mocks = vi.hoisted(() => ({ get: vi.fn() }))
vi.mock('@/core/client', () => ({ api: { get: mocks.get } }))
describe('activity read contract', () => {
  it('reads progress through the public progress endpoint with an exercise ID', async () => {
    mocks.get.mockResolvedValue({
      exercise: { id: 'exercise', revision: 1, name: 'Bench', load_basis: 'total' },
      from: '2026-10-01',
      to: '2026-10-05',
      series: [],
      daily: [],
    })
    mount(
      defineComponent({
        setup() {
          useExerciseProgress(
            ref('exercise'),
            ref({ from: '2026-10-01', to: '2026-10-05', kind: 'walking' }),
          )
          return () => null
        },
      }),
      { global: { plugins: [[VueQueryPlugin, { queryClient: new QueryClient() }]] } },
    )
    await flushPromises()
    expect(mocks.get).toHaveBeenCalledWith(
      '/v1/activity/progress?exercise_id=exercise&from=2026-10-01&to=2026-10-05',
      expect.any(AbortSignal),
    )
  })
})
