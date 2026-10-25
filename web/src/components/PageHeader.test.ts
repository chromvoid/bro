import { mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, describe, expect, it } from 'vitest'
import { h } from 'vue'
import { i18n } from '@/i18n'
import PageHeader from './PageHeader.vue'

const mounted: VueWrapper[] = []
afterEach(() => mounted.splice(0).forEach((wrapper) => wrapper.unmount()))

function header(props: Record<string, unknown>, slots: Record<string, () => unknown> = {}) {
  const wrapper = mount(PageHeader, {
    props: { title: 'Питание', ...props },
    slots,
    global: {
      plugins: [i18n],
      stubs: {
        RouterLink: { props: ['to'], template: '<a><slot /></a>' },
        NotificationBell: { template: '<a data-testid="notifications-bell" />' },
        ProfileMenu: { template: '<button data-testid="profile-menu" />' },
      },
    },
  })
  mounted.push(wrapper)
  return wrapper
}

describe('page header', () => {
  it('puts the app-wide buttons after the screen actions on a top-level screen', () => {
    const wrapper = header({}, { actions: () => h('button', { 'data-testid': 'own' }) })
    const actions = wrapper.get('[data-testid="header-actions"]')
    expect(
      actions.findAll('[data-testid]').map((element) => element.attributes('data-testid')),
    ).toEqual(['own', 'notifications-bell', 'profile-menu'])
    expect(wrapper.get('h1').classes()).toContain('truncate')
  })

  it('shows only the way back and the screen actions on a subpage', () => {
    const wrapper = header({ back: { name: 'settings' }, title: 'Очень длинное название списка' })
    expect(wrapper.find('[data-testid="devices-control"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="notifications-bell"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="profile-menu"]').exists()).toBe(false)
    expect(wrapper.get('a').attributes('aria-label')).toBe('Назад')
    expect(wrapper.get('h1').classes()).toContain('line-clamp-2')
    expect(wrapper.get('h1').attributes('title')).toBe('Очень длинное название списка')
  })

  it('keeps the bell off the notifications screen and puts rare actions behind «…»', () => {
    const wrapper = header({ bare: true }, { menu: () => h('div', 'Экспорт') })
    expect(wrapper.find('[data-testid="notifications-bell"]').exists()).toBe(false)
    expect(wrapper.find('[aria-label="Ещё"]').exists()).toBe(true)
    expect(header({}).find('[aria-label="Ещё"]').exists()).toBe(false)
  })

  it('writes the description on its own line under the row', () => {
    const wrapper = header({ description: 'Занятия и движение' })
    expect(
      wrapper.get('[data-testid="header-actions"]').element.contains(wrapper.get('p').element),
    ).toBe(false)
    expect(wrapper.get('p').text()).toBe('Занятия и движение')
  })
})
