import { enableAutoUnmount, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick, ref } from 'vue'
import { Task } from './api'
import TaskBoard from './TaskBoard.vue'
import TaskMoveSheet from './TaskMoveSheet.vue'

const mocks = vi.hoisted(() => ({
  room: vi.fn(),
  media: vi.fn(),
  pages: vi.fn(),
  members: vi.fn(),
}))
vi.mock('vue-i18n', async (original) => ({
  ...(await original<typeof import('vue-i18n')>()),
  useI18n: () => ({
    t: (key: string, values?: Record<string, string>) =>
      values ? `${key}(${Object.values(values).join(',')})` : key,
  }),
}))
vi.mock('vue-router', async (original) => ({
  ...(await original<typeof import('vue-router')>()),
  useRoute: () => ({ query: {} }),
  useRouter: () => ({ replace: vi.fn() }),
}))
vi.mock('@vueuse/core', async (original) => ({
  ...(await original<typeof import('@vueuse/core')>()),
  useMediaQuery: mocks.media,
  useElementBounding: () => ({ top: ref(-1) }),
}))
vi.mock('@/core/layout', () => ({ useRoomFor: mocks.room }))
vi.mock('@/sections/circle/useCircle', () => ({ useMembers: mocks.members }))
vi.mock('./useTaskBoardPages', () => ({ useTaskBoardPages: mocks.pages }))
vi.mock('./useTaskActions', () => ({
  useTaskActions: () => ({ completion: { cancel: vi.fn() } }),
}))

enableAutoUnmount(afterEach)
let frameId = 0
const frames = new Map<number, FrameRequestCallback>()
const allColumns = ref(false)
const wide = ref(false)

beforeEach(() => {
  allColumns.value = false
  wide.value = false
  mocks.room.mockReturnValue(allColumns)
  mocks.media.mockReturnValue(wide)
  mocks.members.mockReturnValue({ data: ref({ members: [] }) })
  mocks.pages.mockReturnValue({
    data: ref(undefined),
    isFetching: ref(false),
    isLoadingError: ref(false),
    isPending: ref(false),
    error: ref(null),
    loadingMore: {},
    moreError: {},
    refetch: vi.fn(),
  })
  vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => {
    frames.set(++frameId, callback)
    return frameId
  })
  vi.stubGlobal('cancelAnimationFrame', (id: number) => frames.delete(id))
})
afterEach(() => {
  frames.clear()
  vi.unstubAllGlobals()
})

async function renderFrame() {
  const pending = [...frames.values()]
  frames.clear()
  for (const callback of pending) callback(0)
  await nextTick()
}

function screen(extra: { progress?: { done: number; total: number } } = {}) {
  const wrapper = mount(TaskBoard, {
    props: {
      listId: 'project',
      sort: 'manual',
      q: '',
      today: '2026-10-08',
      spaceId: null,
      canWrite: true,
      ...extra,
    },
    global: {
      stubs: {
        Button: { template: '<button><slot /></button>' },
        VueDraggable: { template: '<div><slot /></div>' },
        Panel: true,
        RefreshFailed: true,
        ErrorState: true,
        TaskCompletionDialog: true,
      },
    },
  })
  const board = wrapper.get<HTMLElement>('[data-testid="task-board"]')
  let left = 0
  let width = 390
  Object.defineProperty(board.element, 'scrollLeft', { get: () => left, configurable: true })
  vi.spyOn(board.element, 'getBoundingClientRect').mockImplementation(
    () => new DOMRect(0, 0, width, 900),
  )
  for (const [index, column] of board.findAll('section').entries())
    vi.spyOn(column.element, 'getBoundingClientRect').mockImplementation(
      () => new DOMRect(16 + index * 312 - left, 0, 300, 900),
    )
  // The browser can restore a different position after resizing or another programmatic scroll.
  board.element.scrollTo = vi.fn()
  return {
    wrapper,
    board,
    jumps: () => wrapper.findAll('nav button'),
    async position(offset: number) {
      left = offset
      await board.trigger('scroll')
      await renderFrame()
    },
    resize(nextWidth: number) {
      width = nextWidth
      if (nextWidth > 1000) left = 0
    },
  }
}

describe('board column navigation', () => {
  it('follows a later scroll after a no-op jump that emitted no scrollend', async () => {
    const view = screen()
    await view.jumps()[0]!.trigger('click')
    await renderFrame()
    await view.position(312)
    expect(view.jumps()[1]!.attributes('aria-current')).toBe('true')
    expect(
      view.wrapper.get('[data-testid="board-floating-add"] button').attributes('aria-label'),
    ).toBe('tasks.board.addIn(tasks.states.in_progress)')
  })

  it('jumps immediately and follows a later programmatic scroll without scrollend', async () => {
    const view = screen()
    await view.jumps()[3]!.trigger('click')
    expect(view.board.element.scrollTo).toHaveBeenLastCalledWith({ left: 936, behavior: 'auto' })
    await view.position(312)
    expect(view.jumps()[1]!.attributes('aria-current')).toBe('true')
    expect(
      view.wrapper.get('[data-testid="board-floating-add"] button').attributes('aria-label'),
    ).toBe('tasks.board.addIn(tasks.states.in_progress)')
    await view.position(936)
    expect(view.jumps()[3]!.attributes('aria-current')).toBe('true')
    expect(view.wrapper.find('[data-testid="board-floating-add"]').exists()).toBe(false)
  })

  it('reconciles the mobile action with the actual column after narrowing the workspace', async () => {
    const view = screen()
    await view.position(312)
    view.resize(1440)
    allColumns.value = true
    wide.value = true
    await nextTick()
    view.resize(390)
    allColumns.value = false
    wide.value = false
    await nextTick()
    await nextTick()
    // The browser retained the first column while the resize-triggered jump was interrupted.
    await renderFrame()
    expect(view.jumps()[0]!.attributes('aria-current')).toBe('true')
    expect(
      view.wrapper.get('[data-testid="board-floating-add"] button').attributes('aria-label'),
    ).toBe('tasks.board.addIn(tasks.states.todo)')
  })

  it('shows all four states as equal tabs and opens the move sheet for a card on a phone', async () => {
    const task = Task.parse({
      id: 'a',
      title: 'Плитка',
      status: 'open',
      state: 'todo',
      revision: 1,
    })
    const column = (items: Task[], total: number) => ({ items, total, next_cursor: null })
    mocks.pages.mockReturnValue({
      ...mocks.pages(),
      data: ref({
        columns: {
          todo: column([task], 1),
          in_progress: column([], 0),
          waiting: column([], 0),
          done: column([], 2),
        },
        readAtByTask: {},
      }),
    })
    const view = screen()
    const tabs = view.jumps()
    expect(tabs).toHaveLength(4)
    expect(tabs[0]!.text()).toContain('tasks.board.short.todo')
    expect(tabs[0]!.text()).toContain('1')
    expect(tabs[3]!.text()).toContain('tasks.board.short.done')
    expect(tabs[3]!.attributes('title')).toBe('tasks.board.doneWeek')
    await view.wrapper.get('button[aria-label="tasks.board.moveTask(Плитка)"]').trigger('click')
    const sheet = view.wrapper.getComponent(TaskMoveSheet)
    expect(sheet.props('task')).toMatchObject({ id: 'a' })
    expect(sheet.props('column')).toBe('todo')
    expect(sheet.props('counts')).toEqual({ todo: 1, in_progress: 0, waiting: 0, done: 2 })
    expect(sheet.props('queueMode')).toBe('manual')
  })

  it("puts the project's progress under «Готово» on a phone", async () => {
    const view = screen({ progress: { done: 11, total: 14 } })
    const done = view.jumps()[3]!
    expect(done.text()).toContain('11/14')
    expect(done.get('.sr-only').text()).toBe('tasks.projectProgress(11,14)')
    wide.value = true
    await nextTick()
    expect(view.jumps()[3]!.text()).not.toContain('11/14')
  })

  it('names the column on the phone add button and folds it while the page goes down', async () => {
    const view = screen()
    const add = () => view.wrapper.get('[data-testid="board-floating-add"] button')
    expect(add().text()).toBe('tasks.board.add')
    await view.position(312)
    expect(add().text()).toBe('tasks.board.addIn(tasks.states.in_progress)')
    const page = document.documentElement
    const scroll = async (top: number) => {
      Object.defineProperty(page, 'scrollTop', { value: top, configurable: true })
      window.dispatchEvent(new Event('scroll'))
      await nextTick()
    }
    try {
      await scroll(200)
      expect(add().attributes('data-folded')).toBe('true')
      expect(add().text()).toBe('')
      expect(add().attributes('aria-label')).toBe('tasks.board.addIn(tasks.states.in_progress)')
      await scroll(150)
      expect(add().attributes('data-folded')).toBeUndefined()
    } finally {
      delete (page as { scrollTop?: number }).scrollTop
    }
  })
})
