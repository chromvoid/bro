import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h, nextTick, ref } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { i18n } from '@/i18n'
import ProfileSheet from './ProfileSheet.vue'

const context = vi.hoisted(() => ({
  askLogout: vi.fn(),
  applyTheme: vi.fn(),
}))
vi.mock('./useProfileMenu', async (original) => {
  const { computed, ref } = await import('vue')
  const { House, Settings, Users } = await import('@lucide/vue')
  return {
    ...(await original<typeof import('./useProfileMenu')>()),
    useProfileMenu: () => ({
      session: { me: { name: 'Тестовый владелец', login: 'owner' } },
      plugins: { entries: [{ installation: { installation_id: 'trading', title: 'Торговля' } }] },
      circle: ref(2),
      initials: computed(() => 'ТВ'),
      links: computed(() => [
        { name: 'circle', label: 'nav.circle', icon: Users },
        { name: 'settings', label: 'nav.settings', icon: Settings },
        { name: 'today', label: 'nav.today', icon: House },
      ]),
      askLogout: context.askLogout,
    }),
  }
})
vi.mock('@/core/theme', async (original) => ({
  ...(await original<typeof import('@/core/theme')>()),
  applyTheme: context.applyTheme,
}))

// The sheet itself is a Panel; here it renders its slot in place.
const PanelStub = defineComponent({
  props: { open: Boolean, title: String, description: String },
  setup(props, { slots }) {
    return () =>
      props.open
        ? h(
            'section',
            { 'data-title': props.title, 'data-description': props.description },
            slots.default?.(),
          )
        : null
  },
})

const mounted: VueWrapper[] = []
afterEach(() => mounted.splice(0).forEach((wrapper) => wrapper.unmount()))
beforeEach(() => {
  vi.clearAllMocks()
})

async function sheet() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: ['today', 'circle', 'settings', 'plugin'].map((name) => ({
      path: name === 'plugin' ? '/plugins/:installation' : `/${name}`,
      name,
      component: { template: '<div />' },
    })),
  })
  await router.push('/settings')
  const open = ref(true)
  const wrapper = mount(ProfileSheet, {
    props: { open: open.value, 'onUpdate:open': (value: boolean) => (open.value = value) },
    global: { plugins: [i18n, router], stubs: { Panel: PanelStub } },
  })
  mounted.push(wrapper)
  return { wrapper, router, open }
}

describe('profile sheet', () => {
  it('shows the person, the sections as tiles with the current one marked, and plugins', async () => {
    const { wrapper } = await sheet()
    const panel = wrapper.get('section')
    expect(panel.attributes('data-title')).toBe('Тестовый владелец')
    expect(panel.attributes('data-description')).toBe('@owner')
    const tiles = wrapper.findAll('nav a')
    expect(tiles.map((tile) => tile.text())).toEqual([
      'Новое в Круге: 2Круг',
      'Настройки',
      'Сегодня',
      'Торговля',
    ])
    expect(tiles[1]!.attributes('aria-current')).toBe('page')
  })

  it('closes when a tile leads to another screen', async () => {
    const { wrapper, router, open } = await sheet()
    await router.push('/circle')
    await flushPromises()
    expect(open.value).toBe(false)
    expect(wrapper.emitted('update:open')?.at(-1)).toEqual([false])
  })

  it('closes before signing out', async () => {
    const { wrapper } = await sheet()
    await wrapper.get('[data-testid="logout"]').trigger('click')
    await nextTick()
    expect(wrapper.emitted('update:open')?.at(-1)).toEqual([false])
    expect(context.askLogout).toHaveBeenCalledOnce()
  })

  it('chooses the theme from three equal segments', async () => {
    const { wrapper } = await sheet()
    const segments = wrapper.findAll('[role="radio"]')
    expect(segments.map((segment) => segment.text())).toEqual(['Система', 'Светлая', 'Тёмная'])
    await segments[2]!.trigger('click')
    expect(context.applyTheme).toHaveBeenCalledWith('dark')
  })
})
