import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { describe, expect, it, vi } from 'vitest'
import { useSession } from '@/stores/session'
import { createI18n } from 'vue-i18n'
import ru from '@/locales/ru.json'
import { Observation, Profile, type Kind } from './api'
import { bodyAnalyses } from './body'
import BodyAtlas from './BodyAtlas.vue'

function record(kind: Kind, value: string, scan = 'one') {
  return Observation.parse({
    id: kind + scan,
    source_id: 'source',
    source_name: 'Synthetic InBody',
    kind,
    start_at: `2026-09-${scan === 'one' ? '10' : '01'}T09:00:00Z`,
    end_at: '2026-09-10T09:00:00Z',
    timezone: 'UTC',
    method: 'device_report',
    origin: { record_id: scan },
    data: { value },
    revision: 1,
  })
}
const profile = Profile.parse({
  user_id: 'owner',
  timezone: 'UTC',
  height_cm: '200',
  gender: 'female',
  gender_origin: 'manual',
  revision: 1,
})
function show(
  records: Observation[],
  extra: Record<string, unknown> = {},
  characterId?: 'sculpture' | 'spartan',
) {
  const analyses = bodyAnalyses(records)
  const pinia = createPinia()
  const queries = new QueryClient({
    defaultOptions: { queries: { retry: false, staleTime: Infinity } },
  })
  if (characterId) {
    useSession(pinia).me = {
      id: 'owner',
      login: 'owner',
      name: 'Synthetic',
      locale: 'ru',
      timezone: 'UTC',
      system_admin: false,
      revision: 1,
      scopes: ['personal.read', 'personal.write'],
    }
    queries.setQueryData(['personal', 'body-presentation', 'owner'], {
      character_id: characterId,
      revision: 2,
    })
  }
  return mount(BodyAtlas, {
    props: { analysis: analyses[0]!, analyses, today: '2026-09-30', timezone: 'UTC', ...extra },
    global: {
      plugins: [
        pinia,
        [VueQueryPlugin, { queryClient: queries }],
        createI18n({ legacy: false, locale: 'ru', messages: { ru } }),
      ],
      stubs: { BodyFigure: true },
    },
  })
}

describe('body atlas', () => {
  it('shows only selected scan values and same-source dated differences', () => {
    const current = { ...record('body_fat_percent', '21.1'), corrected: true }
    const wrapper = show([
      current,
      record('weight', '70'),
      record('body_fat_percent', '22.7', 'old'),
      record('body_water', '41', 'old'),
    ])
    expect(wrapper.get('[data-testid="atlas-body_fat_percent"]').text()).toContain('21,1 %')
    expect(wrapper.text()).toContain('−1,6 п. п.')
    expect(wrapper.text()).toContain('Ваша поправка')
    expect(wrapper.get('[data-testid="atlas-body_water"]').text()).toContain('значение не передано')
    expect(wrapper.get('[data-testid="atlas-body_water"]').text()).not.toContain('41')
  })
  it('switches layers and preserves missing/zero without manual motion controls', async () => {
    const wrapper = show([record('body_fat_percent', '0'), record('body_water', '42')])
    wrapper.getComponent({ name: 'BodyFigure' }).vm.$emit('renderer', 'webgpu')
    wrapper.getComponent({ name: 'BodyFigure' }).vm.$emit('loading', false)
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[data-testid="atlas-motion-toggle"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid^="atlas-scenario-"]').exists()).toBe(false)
    await wrapper
      .findAll('button')
      .find((b) => b.text() === 'Жир')!
      .trigger('click')
    expect(wrapper.get('[data-testid="body-atlas"]').attributes('data-layer')).toBe('fat')
    expect(wrapper.get('[data-testid="atlas-body_fat_percent"]').text()).toContain('0 %')
    expect(wrapper.get('[data-testid="atlas-body_fat_mass"]').text()).toContain('—')
    expect(wrapper.text()).toContain('Фигура иллюстрирует состав тела')
  })
  it('does not choose between conflicting normalized records', () => {
    const first = record('body_fat_percent', '22.7')
    const wrapper = show([first, { ...first, id: 'another', data: { value: '20' } }])
    const value = wrapper.get('[data-testid="atlas-body_fat_percent"]')
    expect(value.text()).toContain('Источники расходятся')
    expect(value.text()).not.toContain('22,7')
  })
  it('explains extreme figure limits without changing measured values or claiming a poster shape', async () => {
    const wrapper = show([record('body_fat_percent', '8'), record('skeletal_muscle_mass', '60')], {
      profile,
    })
    const figure = wrapper.getComponent({ name: 'BodyFigure' })
    expect(wrapper.find('[data-testid="atlas-calibration-hint"]').exists()).toBe(false)
    figure.vm.$emit('renderer', 'webgpu')
    figure.vm.$emit('loading', false)
    await wrapper.vm.$nextTick()
    const hint = wrapper.get('[data-testid="atlas-calibration-hint"]').text()
    expect(hint).toContain('Процент жира — ниже диапазона модели')
    expect(hint).toContain('Мышцы с учётом роста — выше диапазона модели')
    expect(hint).toContain('ближайшая крайняя форма')
    expect(hint).toContain('числа анализа не меняются')
    expect(wrapper.get('[data-testid="atlas-body_fat_percent"]').text()).toContain('8 %')
    expect(wrapper.get('[data-testid="atlas-skeletal_muscle_mass"]').text()).toContain('60 кг')
    expect(figure.props('visual')).toMatchObject({ fat: 0, muscle: 1 })
    figure.vm.$emit('renderer', 'poster')
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[data-testid="atlas-calibration-hint"]').exists()).toBe(false)
  })
  it('keeps both clamped comparison endpoints explicit when different scans have the same shape', async () => {
    const wrapper = show(
      [record('body_fat_percent', '55'), record('body_fat_percent', '45', 'old')],
      { profile },
    )
    const figure = wrapper.getComponent({ name: 'BodyFigure' })
    figure.vm.$emit('renderer', 'webgpu')
    figure.vm.$emit('loading', false)
    await wrapper.vm.$nextTick()
    await wrapper
      .findAll('button')
      .find((button) => button.text() === 'Сравнить анализы')!
      .trigger('click')
    const facts = wrapper.get('[data-testid="atlas-body_fat_percent"]').text()
    expect(facts).toContain('55 %')
    expect(facts).toContain('Было 45 %')
    for (const progress of ['0', '50', '100']) {
      await wrapper.get('#body-compare-progress').setValue(progress)
      const hint = wrapper.get('[data-testid="atlas-calibration-hint"]').text()
      expect(hint).toContain('Было: Процент жира — выше диапазона модели')
      expect(hint).toContain('Выбранный анализ: Процент жира — выше диапазона модели')
      expect(hint).toContain('часть изменений на фигуре может быть не видна')
      expect(figure.props('visual').fat).toBe(1)
      expect(wrapper.get('[data-testid="atlas-body_fat_percent"]').text()).toBe(facts)
    }
  })
  it('warns for an older out-of-range endpoint even at the selected in-range end', async () => {
    const wrapper = show(
      [record('body_fat_percent', '25'), record('body_fat_percent', '8', 'old')],
      { profile },
    )
    const figure = wrapper.getComponent({ name: 'BodyFigure' })
    figure.vm.$emit('renderer', 'webgpu')
    figure.vm.$emit('loading', false)
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[data-testid="atlas-calibration-hint"]').exists()).toBe(false)
    await wrapper
      .findAll('button')
      .find((button) => button.text() === 'Сравнить анализы')!
      .trigger('click')
    expect(wrapper.get('[data-testid="atlas-calibration-hint"]').text()).toContain(
      'Было: Процент жира — ниже диапазона модели',
    )
    expect(figure.props('visual').fat).toBe(0.5)
  })
  it('scrubs the figure while facts remain exact endpoints and missing axes stay selected', async () => {
    const wrapper = show(
      [
        record('body_fat_percent', '40'),
        record('skeletal_muscle_mass', '36'),
        record('body_fat_percent', '10', 'old'),
      ],
      { profile },
    )
    const figure = wrapper.getComponent({ name: 'BodyFigure' })
    expect(figure.props('visual')).toMatchObject({ template: 'female', fat: 1, muscle: 0.5 })
    await wrapper
      .findAll('button')
      .find((button) => button.text() === 'Сравнить анализы')!
      .trigger('click')
    expect(figure.props('comparing')).toBe(true)
    const facts = wrapper.get('[data-testid="atlas-body_fat_percent"]').text()
    expect(facts).toContain('40 %')
    expect(facts).toContain('Было 10 %')
    expect(facts).toContain('+30 п. п.')
    await wrapper.get('#body-compare-progress').setValue('0')
    expect(figure.props('visual')).toMatchObject({ fat: 0, muscle: 0.5 })
    await wrapper.get('#body-compare-progress').setValue('50')
    expect(figure.props('visual')).toMatchObject({ fat: 0.5, muscle: 0.5 })
    expect(wrapper.get('[data-testid="atlas-body_fat_percent"]').text()).toBe(facts)
    expect(wrapper.text()).toContain('Нет данных для перехода: мышцы')
    expect(wrapper.get('[data-testid="atlas-comparison-endpoints"]').text()).toContain('1 сентября')
    expect(wrapper.get('[data-testid="atlas-comparison-endpoints"]').text()).toContain(
      '10 сентября',
    )
    await wrapper.setProps({
      analysis: bodyAnalyses([record('body_fat_percent', '10', 'old')])[0]!,
    })
    expect(wrapper.find('#body-compare-progress').exists()).toBe(false)
    expect(figure.props('comparing')).toBe(false)
  })
  it('asks for missing profile data without writing a default and exposes static fallback', async () => {
    const wrapper = show([record('body_fat_percent', '25'), record('skeletal_muscle_mass', '36')], {
      profile: { ...profile, gender: null, gender_origin: 'unset', height_cm: null },
      canWrite: true,
    })
    const figure = wrapper.getComponent({ name: 'BodyFigure' })
    expect(figure.props('visual')).toMatchObject({ template: 'male', muscle: null })
    expect(wrapper.text()).toContain('Пол не указан')
    expect(wrapper.text()).toContain('Укажите рост')
    await wrapper
      .findAll('button')
      .find((button) => button.text() === 'Указать гендер')!
      .trigger('click')
    expect(wrapper.emitted('profile')).toEqual([[]])
    figure.vm.$emit('renderer', 'poster')
    figure.vm.$emit('loading', false)
    await wrapper.vm.$nextTick()
    expect(wrapper.text()).toContain('Статичная нейтральная иллюстрация')
  })
  it('shows loading instead of a fallback warning until the model succeeds or fails', async () => {
    const wrapper = show([record('body_fat_percent', '25')])
    const figure = wrapper.getComponent({ name: 'BodyFigure' })
    figure.vm.$emit('loading', true)
    figure.vm.$emit('renderer', 'poster')
    await wrapper.vm.$nextTick()
    expect(wrapper.get('[data-testid="atlas-motion-status"]').text()).toContain('Загружаем')
    expect(wrapper.text()).not.toContain('Статичная нейтральная иллюстрация')
    figure.vm.$emit('loading', false)
    await wrapper.vm.$nextTick()
    expect(wrapper.text()).toContain('Статичная нейтральная иллюстрация')
    figure.vm.$emit('loading', true)
    await wrapper.vm.$nextTick()
    expect(wrapper.text()).not.toContain('Статичная нейтральная иллюстрация')
    figure.vm.$emit('renderer', 'webgpu')
    figure.vm.$emit('loading', false)
    await wrapper.vm.$nextTick()
    expect(wrapper.get('[data-testid="atlas-motion-status"]').text()).toContain(
      'взгляд по сторонам',
    )
  })
  it('keeps comparison static and returns to automatic idle afterwards', async () => {
    const wrapper = show(
      [record('body_fat_percent', '25'), record('body_fat_percent', '20', 'old')],
      { profile },
    )
    const figure = wrapper.getComponent({ name: 'BodyFigure' })
    figure.vm.$emit('renderer', 'webgpu')
    figure.vm.$emit('loading', false)
    await wrapper.vm.$nextTick()
    const compare = wrapper
      .findAll('button')
      .find((button) => button.text() === 'Сравнить анализы')!
    await compare.trigger('click')
    expect(figure.props('comparing')).toBe(true)
    expect(wrapper.get('[data-testid="atlas-motion-status"]').text()).toContain('неподвижной позе')
    await compare.trigger('click')
    expect(figure.props('comparing')).toBe(false)
    expect(wrapper.get('[data-testid="atlas-motion-status"]').text()).toContain(
      'взгляд по сторонам',
    )
  })
  it('explains automatic movement suspension for reduced motion and fallback', async () => {
    const wrapper = show([record('body_fat_percent', '25')])
    const figure = wrapper.getComponent({ name: 'BodyFigure' })
    figure.vm.$emit('renderer', 'webgl')
    figure.vm.$emit('loading', false)
    await wrapper.vm.$nextTick()
    figure.vm.$emit('reduced-motion', true)
    await wrapper.vm.$nextTick()
    expect(wrapper.get('[data-testid="atlas-motion-status"]').text()).toContain(
      'уменьшения движения',
    )
    figure.vm.$emit('reduced-motion', false)
    figure.vm.$emit('renderer', 'poster')
    figure.vm.$emit('loading', false)
    await wrapper.vm.$nextTick()
    expect(wrapper.get('[data-testid="atlas-motion-status"]').text()).toContain(
      'статичном представлении',
    )
  })
  it('describes static fallback and stopped playback without promising lookaround', async () => {
    const wrapper = show([record('body_fat_percent', '25')])
    const figure = wrapper.getComponent({ name: 'BodyFigure' })
    figure.vm.$emit('renderer', 'webgpu')
    figure.vm.$emit('loading', false)
    const state = {
      scenario: 'idle',
      activeScenario: null,
      queuedScenario: null,
      phase: 'idle',
      progress: 0,
      playing: true,
      paused: false,
      motionSource: 'idle',
    }
    figure.vm.$emit('motion-state', state)
    await wrapper.vm.$nextTick()
    const status = () => wrapper.get('[data-testid="atlas-motion-status"]').text()
    expect(status()).toBe('Спокойная стойка и взгляд по сторонам.')
    for (const fallback of [
      { ...state, motionSource: 'static', playing: false },
      { ...state, playing: false },
    ]) {
      figure.vm.$emit('motion-state', fallback)
      await wrapper.vm.$nextTick()
      expect(status()).toBe('Спокойная стойка.')
    }
    figure.vm.$emit('motion-state', { ...state, paused: true, playing: false })
    await wrapper.vm.$nextTick()
    expect(status()).toBe('Движение на паузе.')
    figure.vm.$emit('motion-state', state)
    await wrapper.vm.$nextTick()
    expect(status()).toBe('Спокойная стойка и взгляд по сторонам.')
  })
  it('does not present an unavailable profile as saved empty fields', () => {
    const wrapper = show([record('body_fat_percent', '25')], { canWrite: true })
    expect(wrapper.find('[data-testid="atlas-profile-hint"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="atlas-body_fat_percent"]').text()).toContain('25 %')
  })
  it('shows an older endpoint even when the selected scan omits that axis', async () => {
    const wrapper = show(
      [
        record('body_fat_percent', '30'),
        record('body_fat_percent', '20', 'old'),
        record('skeletal_muscle_mass', '36', 'old'),
      ],
      { profile },
    )
    await wrapper
      .findAll('button')
      .find((button) => button.text() === 'Сравнить анализы')!
      .trigger('click')
    await wrapper.get('#body-compare-progress').setValue('0')
    expect(wrapper.getComponent({ name: 'BodyFigure' }).props('visual').muscle).toBeNull()
    const metric = wrapper.get('[data-testid="atlas-skeletal_muscle_mass"]').text()
    expect(metric).toContain('значение не передано')
    expect(metric).toContain('Было 36 кг')
  })
  it('shows the saved incompatible choice without changing it and disables the female Spartan option', () => {
    const wrapper = show([record('body_fat_percent', '25')], { profile, canWrite: true }, 'spartan')
    expect(wrapper.get('[data-testid="atlas-character-spartan"]').attributes('aria-pressed')).toBe(
      'true',
    )
    expect(
      wrapper.get('[data-testid="atlas-character-spartan"]').attributes('disabled'),
    ).toBeDefined()
    expect(wrapper.get('[data-testid="atlas-character-unavailable"]').text()).toContain(
      'выбор образа сохранён',
    )
    expect(wrapper.getComponent({ name: 'BodyFigure' }).props('characterId')).toBe('spartan')
    expect(wrapper.getComponent({ name: 'BodyFigure' }).props('visual').template).toBe('female')
  })
  it('exposes keyboard view controls for GPU rendering and disables them on a poster', async () => {
    const wrapper = show([record('body_fat_percent', '25')])
    const figure = wrapper.getComponent({ name: 'BodyFigure' })
    const setView = vi.fn()
    Object.assign(figure.vm, { setView })
    expect(wrapper.get('[data-testid="atlas-view-side"]').attributes('disabled')).toBeDefined()
    figure.vm.$emit('renderer', 'webgpu')
    figure.vm.$emit('loading', false)
    await wrapper.vm.$nextTick()
    await wrapper.get('[data-testid="atlas-view-side"]').trigger('click')
    expect(setView).toHaveBeenCalledWith('side')
    figure.vm.$emit('renderer', 'poster')
    await wrapper.vm.$nextTick()
    expect(wrapper.get('[data-testid="atlas-view-front"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-testid="atlas-view-back"]').attributes('disabled')).toBeDefined()
  })
})
