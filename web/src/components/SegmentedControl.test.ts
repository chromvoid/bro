import { mount, RouterLinkStub } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import SegmentedControl from './SegmentedControl.vue'

describe('segmented control', () => {
  it('is navigation when its segments are links, the current one marked for assistive tech', () => {
    const wrapper = mount(SegmentedControl, {
      props: {
        label: 'Дела',
        modelValue: 'timers',
        segments: [
          { value: 'tasks', label: 'Задачи', to: { name: 'tasks' } },
          { value: 'timers', label: 'Таймеры', to: { name: 'timers' } },
        ],
      },
      global: { stubs: { RouterLink: RouterLinkStub } },
    })
    expect(wrapper.get('nav').attributes('aria-label')).toBe('Дела')
    const links = wrapper.findAllComponents(RouterLinkStub)
    expect(links.map((link) => link.attributes('aria-current'))).toEqual([undefined, 'page'])
    expect(links[1]!.props('to')).toEqual({ name: 'timers' })
  })

  it('is a radio group bound with v-model otherwise', async () => {
    const wrapper = mount(SegmentedControl, {
      props: {
        label: 'Единица',
        modelValue: 'g',
        segments: [
          { value: 'g', label: 'г' },
          { value: 'ml', label: 'мл' },
        ],
      },
    })
    const radios = wrapper.findAll('[role="radio"]')
    expect(radios.map((radio) => radio.attributes('aria-checked'))).toEqual(['true', 'false'])
    await radios[1]!.trigger('click')
    expect(wrapper.emitted('update:modelValue')).toEqual([['ml']])
  })

  it('keeps every segment on one row and scrolls instead of wrapping', () => {
    const wrapper = mount(SegmentedControl, {
      props: {
        label: 'Раздел',
        modelValue: 'journal',
        segments: [
          { value: 'overview', label: 'Обзор' },
          { value: 'journal', label: 'Журнал' },
          { value: 'progress', label: 'Прогресс' },
          { value: 'exercises', label: 'Упражнения' },
        ],
      },
    })
    expect(wrapper.attributes('data-slot')).toBe('segmented')
    expect(wrapper.classes()).toContain('overflow-x-auto')
    const group = wrapper.get('[role="radiogroup"]')
    expect(group.classes()).not.toContain('flex-wrap')
    expect(group.classes()).toContain('w-max')
  })

  it('shows an icon before the label', () => {
    const wrapper = mount(SegmentedControl, {
      props: {
        label: 'Тема',
        stretch: true,
        segments: [{ value: 'dark', label: 'Тёмная', icon: { render: () => null, name: 'Moon' } }],
      },
    })
    expect(wrapper.get('[role="radio"]').text()).toBe('Тёмная')
    expect(wrapper.get('[role="radiogroup"]').classes()).toContain('auto-cols-fr')
  })
})
