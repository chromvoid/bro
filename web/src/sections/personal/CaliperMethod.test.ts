import { mount } from '@vue/test-utils'
import { expect, it } from 'vitest'
import { createI18n } from 'vue-i18n'
import ru from '@/locales/ru.json'
import CaliperMethod from './CaliperMethod.vue'

it('labels the male locations guide and keeps it separate from the female protocol', async () => {
  const wrapper = mount(CaliperMethod, {
    props: { gender: 'male' },
    global: { plugins: [createI18n({ legacy: false, locale: 'ru', messages: { ru } })] },
  })
  const guide = wrapper.get('[data-testid="caliper-male-guide"]')
  expect(guide.text()).toContain('Три точки мужского протокола')
  const image = guide.get('img')
  expect(image.attributes('alt')).toContain('три точки на правой стороне тела')
  expect(image.attributes('alt')).toContain('направление длинной оси складки')
  expect(image.attributes('alt')).toContain('в 2 см справа от пупка')
  const source = image.attributes('src')
  expect(source).toMatch(/^\/(?!\/)/)
  expect(source).not.toContain('data:')
  expect(guide.get('a').attributes('href')).toBe(source)
  expect(guide.get('a').attributes('target')).toBe('_blank')

  await wrapper.setProps({ gender: 'female' })
  expect(wrapper.find('[data-testid="caliper-male-guide"]').exists()).toBe(false)
  expect(wrapper.get('dl').text()).toContain('Трицепс')
  expect(wrapper.text()).toContain('у женщин — трицепс, бок над тазом и бедро')
  await wrapper.setProps({ gender: null })
  expect(wrapper.get('[data-testid="caliper-male-guide"]').text()).toContain('мужского протокола')
})
