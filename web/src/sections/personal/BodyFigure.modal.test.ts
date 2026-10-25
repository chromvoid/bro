import { DOMWrapper, flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h, ref, type Component, type VNodeChild } from 'vue'
import Panel from '@/components/Panel.vue'
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogScrollContent,
  DialogTitle,
} from '@/components/ui/dialog'
import {
  Sheet,
  SheetClose,
  SheetContent,
  SheetDescription,
  SheetTitle,
} from '@/components/ui/sheet'
import BodyFigure from './BodyFigure.vue'
import type { BodyRenderer } from './bodyGpu'

const { create } = vi.hoisted(() => ({ create: vi.fn() }))
vi.mock('./bodyGpu', () => ({ createBodyRenderer: create }))
vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (key: string) => key }) }))

const mounted: VueWrapper[] = []
const intersections = new Map<Element, IntersectionObserverCallback>()
const renderers = new Map<string, BodyRenderer>()

function makeRenderer(): BodyRenderer {
  return {
    mode: 'webgl',
    setLayer: vi.fn(),
    setVisual: vi.fn(),
    setYaw: vi.fn(),
    setView: vi.fn(),
    getViewState: vi.fn(() => ({ yaw: 0.13, polar: 1.4, radius: 5 })),
    setActive: vi.fn(),
    setMotion: vi.fn(),
    setPaused: vi.fn(),
    setScenario: vi.fn(),
    setComparison: vi.fn(),
    resize: vi.fn(),
    destroy: vi.fn(),
  }
}

function figure(name: string) {
  return h(BodyFigure, { 'data-figure': name })
}

function rendererFor(name: string) {
  const renderer = renderers.get(name)
  expect(renderer, `renderer for ${name}`).toBeDefined()
  return renderer!
}

function mountPage(render: () => VNodeChild) {
  const page = defineComponent({ setup: () => render })
  const wrapper = mount(page, { attachTo: document.body })
  mounted.push(wrapper)
  return wrapper
}

async function showFigures() {
  await flushPromises()
  for (const [target, callback] of intersections) {
    callback(
      [{ target, isIntersecting: true } as IntersectionObserverEntry],
      {} as IntersectionObserver,
    )
  }
  await flushPromises()
}

beforeEach(() => {
  intersections.clear()
  renderers.clear()
  create.mockReset().mockImplementation((canvas: HTMLCanvasElement) => {
    const renderer = makeRenderer()
    renderers.set(canvas.closest('[data-figure]')!.getAttribute('data-figure')!, renderer)
    return Promise.resolve(renderer)
  })
  vi.spyOn(window, 'matchMedia').mockImplementation(
    () => Object.assign(new EventTarget(), { matches: false }) as unknown as MediaQueryList,
  )
  vi.spyOn(document, 'hidden', 'get').mockReturnValue(false)
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe = vi.fn()
      unobserve = vi.fn()
      disconnect = vi.fn()
    },
  )
  vi.stubGlobal(
    'IntersectionObserver',
    class {
      private readonly targets = new Set<Element>()
      constructor(private readonly callback: IntersectionObserverCallback) {}
      observe = (target: Element) => {
        this.targets.add(target)
        intersections.set(target, this.callback)
      }
      unobserve = (target: Element) => {
        this.targets.delete(target)
        intersections.delete(target)
      }
      disconnect = () => {
        for (const target of this.targets) intersections.delete(target)
        this.targets.clear()
      }
    },
  )
})

afterEach(async () => {
  for (const wrapper of mounted.splice(0)) wrapper.unmount()
  await flushPromises()
  document.body.replaceChildren()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

interface ModalVariant {
  name: string
  root: Component
  content: Component
  title: Component
  description: Component
  close: Component
}

const modalVariants: ModalVariant[] = [
  {
    name: 'sheet',
    root: Sheet,
    content: SheetContent,
    title: SheetTitle,
    description: SheetDescription,
    close: SheetClose,
  },
  {
    name: 'dialog',
    root: Dialog,
    content: DialogContent,
    title: DialogTitle,
    description: DialogDescription,
    close: DialogClose,
  },
  {
    name: 'scrollable dialog',
    root: Dialog,
    content: DialogScrollContent,
    title: DialogTitle,
    description: DialogDescription,
    close: DialogClose,
  },
]

describe('body figure behind modal panels', () => {
  it.each(modalVariants)(
    'pauses behind an open $name, keeps its own figure active, and resumes on close',
    async ({ root, content, title, description, close }) => {
      const open = ref(false)
      mountPage(() => [
        figure('background'),
        h(
          root,
          {
            open: open.value,
            'onUpdate:open': (value: boolean) => (open.value = value),
          },
          () =>
            h(content, {}, () => [
              h(title, {}, () => 'Body details'),
              h(description, {}, () => 'Body composition'),
              figure('inside'),
              h(close, { asChild: true }, () => h('button', { 'data-close-modal': '' }, 'Close')),
            ]),
        ),
      ])
      await showFigures()
      const background = rendererFor('background')
      expect(background.setActive).toHaveBeenLastCalledWith(true)

      open.value = true
      await showFigures()
      expect(background.setActive).toHaveBeenLastCalledWith(false)
      expect(rendererFor('inside').setActive).toHaveBeenLastCalledWith(true)

      const closeButton = document.querySelector<HTMLButtonElement>('[data-close-modal]')!
      await new DOMWrapper(closeButton).trigger('click')
      await flushPromises()
      expect(open.value).toBe(false)
      expect(background.setActive).toHaveBeenLastCalledWith(true)
    },
  )

  it.each([false, true])(
    'treats Panel as a modal only when it is not docked (docked=%s)',
    async (docked) => {
      const open = ref(false)
      mountPage(() => [
        figure('background'),
        h(
          Panel,
          {
            open: open.value,
            docked,
            autofocus: false,
            title: 'Body details',
            description: 'Body composition',
            'onUpdate:open': (value: boolean) => (open.value = value),
          },
          () => figure('inside'),
        ),
      ])
      await showFigures()
      const background = rendererFor('background')
      expect(background.setActive).toHaveBeenLastCalledWith(true)

      open.value = true
      await showFigures()
      expect(background.setActive).toHaveBeenLastCalledWith(docked)
      expect(rendererFor('inside').setActive).toHaveBeenLastCalledWith(true)

      const closeButton = document.querySelector<HTMLButtonElement>(
        'button[aria-label="common.close"]',
      )!
      await new DOMWrapper(closeButton).trigger('click')
      await flushPromises()
      expect(open.value).toBe(false)
      expect(background.setActive).toHaveBeenLastCalledWith(true)
    },
  )

  it('tracks the top nested modal and removes open layers when their components unmount', async () => {
    const sheetOpen = ref(false)
    const dialogOpen = ref(false)
    const sheetMounted = ref(true)
    const dialogMounted = ref(true)
    mountPage(() => [
      figure('background'),
      sheetMounted.value
        ? h(Sheet, { open: sheetOpen.value }, () =>
            h(SheetContent, {}, () => [
              h(SheetTitle, {}, () => 'Body details'),
              h(SheetDescription, {}, () => 'Body composition'),
              figure('sheet'),
              dialogMounted.value
                ? h(Dialog, { open: dialogOpen.value }, () =>
                    h(DialogContent, {}, () => [
                      h(DialogTitle, {}, () => 'Comparison'),
                      h(DialogDescription, {}, () => 'Compare body composition'),
                      figure('dialog'),
                    ]),
                  )
                : null,
            ]),
          )
        : null,
    ])
    await showFigures()
    const background = rendererFor('background')

    sheetOpen.value = true
    await showFigures()
    const sheet = rendererFor('sheet')
    expect(background.setActive).toHaveBeenLastCalledWith(false)
    expect(sheet.setActive).toHaveBeenLastCalledWith(true)

    dialogOpen.value = true
    await showFigures()
    expect(background.setActive).toHaveBeenLastCalledWith(false)
    expect(sheet.setActive).toHaveBeenLastCalledWith(false)
    expect(rendererFor('dialog').setActive).toHaveBeenLastCalledWith(true)

    dialogOpen.value = false
    await flushPromises()
    expect(background.setActive).toHaveBeenLastCalledWith(false)
    expect(sheet.setActive).toHaveBeenLastCalledWith(true)

    dialogOpen.value = true
    await showFigures()
    const dialog = rendererFor('dialog')
    dialogMounted.value = false
    await flushPromises()
    expect(dialog.destroy).toHaveBeenCalledOnce()
    expect(sheet.setActive).toHaveBeenLastCalledWith(true)
    expect(background.setActive).toHaveBeenLastCalledWith(false)

    sheetMounted.value = false
    await flushPromises()
    expect(sheet.destroy).toHaveBeenCalledOnce()
    expect(background.setActive).toHaveBeenLastCalledWith(true)
  })

  it('keeps a renderer inactive when its creation finishes behind an open modal', async () => {
    const background = makeRenderer()
    let finish!: (renderer: BodyRenderer) => void
    create.mockImplementationOnce(() => new Promise<BodyRenderer>((resolve) => (finish = resolve)))
    const open = ref(false)
    mountPage(() => [
      figure('background'),
      h(Dialog, { open: open.value }, () =>
        h(DialogContent, {}, () => [
          h(DialogTitle, {}, () => 'Body details'),
          h(DialogDescription, {}, () => 'Body composition'),
        ]),
      ),
    ])
    await showFigures()
    expect(create).toHaveBeenCalledOnce()
    expect(create.mock.calls[0]![1].active).toBe(true)

    open.value = true
    await flushPromises()
    finish(background)
    await flushPromises()
    expect(background.setActive).toHaveBeenLastCalledWith(false)

    open.value = false
    await flushPromises()
    expect(background.setActive).toHaveBeenLastCalledWith(true)
  })
})
