import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { defineComponent, h, ref } from 'vue'
import Panel from './Panel.vue'

vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (key: string) => key }) }))

/** A row that opens the panel, as a page does: the panel is created open with its item. */
const Page = defineComponent({
  setup() {
    const open = ref(false)
    return () => [
      h('button', { id: 'row', onClick: () => (open.value = true) }, 'Позвонить врачу'),
      open.value
        ? h(
            Panel,
            {
              title: 'Позвонить врачу',
              docked: true,
              autofocus: false,
              open: open.value,
              'onUpdate:open': (value: boolean) => (open.value = value),
              'data-testid': 'task-panel',
            },
            () => h('p', 'Подробности'),
          )
        : null,
    ]
  },
})

describe('panel docked beside a list', () => {
  it('is a labelled column of the page, not a dialog, and carries the attributes it is given', () => {
    const wrapper = mount(Panel, {
      props: { title: 'Позвонить врачу', open: true, docked: true },
      attrs: { 'data-testid': 'task-panel' },
      slots: { default: '<p>Подробности</p>' },
    })
    const column = wrapper.get('aside')
    expect(column.attributes('data-testid')).toBe('task-panel')
    expect(wrapper.get(`#${column.attributes('aria-labelledby')}`).text()).toBe('Позвонить врачу')
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    expect(column.text()).toContain('Подробности')
  })

  it('closes from its button and from Escape', async () => {
    const props = { title: 'Задача', open: true, docked: true }
    const byButton = mount(Panel, { props })
    await byButton.get('button[aria-label="common.close"]').trigger('click')
    expect(byButton.emitted('update:open')).toEqual([[false]])
    const byKey = mount(Panel, { props })
    await byKey.get('aside').trigger('keydown', { key: 'Escape' })
    expect(byKey.emitted('update:open')).toEqual([[false]])
  })

  it('takes focus when it opens and gives it back to the row when it closes', async () => {
    const wrapper = mount(Page, { attachTo: document.body })
    const row = wrapper.get('#row')
    ;(row.element as HTMLElement).focus()
    await row.trigger('click')
    await flushPromises()
    expect(document.activeElement?.tagName).toBe('ASIDE')
    await wrapper.get('aside').trigger('keydown', { key: 'Escape' })
    await flushPromises()
    expect(wrapper.find('aside').exists()).toBe(false)
    expect(document.activeElement).toBe(row.element)
    wrapper.unmount()
  })

  it('keeps header actions available and resets its own scroll without moving the page', () => {
    const outside = document.createElement('div')
    outside.scrollTop = 180
    const wrapper = mount(Panel, {
      props: { title: 'Задача', open: true, docked: true },
      slots: { actions: '<button>Действия задачи</button>', default: '<p>Подробности</p>' },
    })
    const column = wrapper.get('aside').element as HTMLElement
    column.scrollTop = 320
    wrapper.vm.resetScroll()
    expect(column.scrollTop).toBe(0)
    expect(outside.scrollTop).toBe(180)
    expect(wrapper.findAll('button').map((button) => button.text())).toContain('Действия задачи')
    wrapper.unmount()
  })

  it('resets only the modal body scroll on a card change', () => {
    const outside = document.createElement('div')
    outside.scrollTop = 180
    const wrapper = mount(Panel, {
      props: { title: 'Задача', open: true },
      global: {
        stubs: {
          Sheet: { template: '<div><slot /></div>' },
          SheetContent: { template: '<section data-testid="modal-frame"><slot /></section>' },
          SheetHeader: { template: '<header><slot /></header>' },
          SheetTitle: { template: '<h2><slot /></h2>' },
          SheetClose: { template: '<span><slot /></span>' },
        },
      },
      slots: {
        actions: '<button>Действия задачи</button>',
        default: '<p>Подробности</p>',
        footer: '<button>Сохранить</button>',
      },
    })
    const frame = wrapper.get('[data-testid="modal-frame"]').element as HTMLElement
    const scroller = wrapper.get('[data-slot="panel-body"]').element as HTMLElement
    frame.scrollTop = 40
    scroller.scrollTop = 220
    wrapper.vm.resetScroll()
    expect(scroller.scrollTop).toBe(0)
    expect(frame.scrollTop).toBe(40)
    expect(outside.scrollTop).toBe(180)
    expect(wrapper.get('header').text()).toContain('Действия задачи')
    expect(wrapper.get('[data-slot="panel-footer"]').text()).toBe('Сохранить')
    wrapper.unmount()
  })
})
