/**
 * The conversation as a virtual list (TanStack Virtual) and the reader's place in it. Only the rows
 * near the screen exist, each measured as it mounts, and the rows on screen keep their place when
 * pages arrive. The newest message stays in view while the reader stays with it (useFollowBottom).
 * The list takes the reader back there, raises a sent question to the top, centres a message opened
 * from search, counts the answers that came while they read back and floats the day they read.
 */
import { defaultRangeExtractor, useVirtualizer, type Range } from '@tanstack/vue-virtual'
import { useEventListener, useResizeObserver } from '@vueuse/core'
import {
  computed,
  nextTick,
  onBeforeUnmount,
  reactive,
  ref,
  shallowRef,
  watch,
  type ComponentPublicInstance,
  type Ref,
} from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { toast } from 'vue-sonner'
import { errorText } from '@/core/errors'
import { estimateRow, rowDays, rowOf, type FeedRow } from './feedRows'
import { useFollowBottom } from './follow'
import { markSeen, unseenReplies, type SeenMark } from './unseen'
import type { useConversation } from './useConversation'

type ElementRef = Readonly<Ref<HTMLElement | null>>

export interface FeedOptions {
  /** The conversation's scroller and the content inside it. */
  scroller: ElementRef
  content: ElementRef
  /** The controls above the list, and the list itself. */
  lead: ElementRef
  feed: ElementRef
  rows: Readonly<Ref<FeedRow[]>>
  /** A message opened from search. */
  anchor: Readonly<Ref<string | null>>
  /** The loaded window reaches the newest message. */
  atHead: Readonly<Ref<boolean>>
  conversation: Pick<ReturnType<typeof useConversation>, 'history' | 'exchanges' | 'latest'>
}

// A sent question rises to the top of the conversation with a strip of the previous turn above it,
// and its answer grows below without moving the screen (NN/g; as ChatGPT and Claude do). The newest
// row keeps the room for that until it is filled; the reader's own scroll ends the hold
// (useFollowBottom). Away from the newest end, the conversation simply returns there.
const QUESTION_PEEK = 64
const ROW_GAP = 20 // the row's pt-5

const frame = () => new Promise((resolve) => requestAnimationFrame(resolve))

export function useFeedVirtualizer({
  scroller,
  content,
  lead,
  feed,
  rows,
  anchor,
  atHead,
  conversation,
}: FeedOptions) {
  const { t } = useI18n()
  const router = useRouter()
  const { history, exchanges } = conversation

  async function showLatest() {
    if (anchor.value) await router.replace({ name: 'assistant' })
    else if (!atHead.value) await conversation.latest()
    bottom.follow()
  }

  const questionRoom = ref(0)
  async function showQuestion() {
    if (anchor.value || !atHead.value) return showLatest()
    bottom.hold()
    await placeQuestion()
  }
  async function placeQuestion() {
    await nextTick()
    const element = scroller.value
    const list = feed.value
    const box = content.value
    if (!element || !list || !box) return
    const gap = rows.value.length > 1 ? ROW_GAP : 0
    // Whatever follows the list (padding, an unsent draft) stays below the room.
    const after = Math.max(
      0,
      box.getBoundingClientRect().bottom - list.getBoundingClientRect().bottom,
    )
    questionRoom.value = Math.max(0, element.clientHeight + gap - QUESTION_PEEK - after)
    await frame()
    keepQuestion()
  }
  /** Puts the newest row's question back under the strip of the previous turn. */
  function keepQuestion() {
    if (!questionRoom.value || !bottom.held.value) return
    const last = rows.value.length - 1
    const start = virtualizer.value.measurementsCache[last]?.start
    if (start === undefined) return
    const target = Math.max(0, start + (last > 0 ? ROW_GAP : 0) - QUESTION_PEEK)
    if (Math.abs((scroller.value?.scrollTop ?? target) - target) > 1)
      virtualizer.value.scrollToOffset(target)
  }
  // The keyboard closing after a send makes the conversation taller: place the question again.
  useResizeObserver(scroller, () => {
    if (questionRoom.value && bottom.held.value) void placeQuestion()
  })

  // Keep the newest message in view while the user stays at the bottom, never in a message
  // opened from search.
  const bottom = useFollowBottom(scroller, content, () => !!anchor.value || !atHead.value)
  // Answers that came while the reader was away from the newest messages: a count on «вниз».
  const away = computed(() => !anchor.value && !bottom.following.value && !bottom.atBottom.value)
  const seen = shallowRef<SeenMark | null>(null)
  watch(away, (value) => (seen.value = value ? markSeen(exchanges.value) : null), {
    immediate: true,
  })
  const unseen = computed(() =>
    seen.value && atHead.value ? unseenReplies(exchanges.value, seen.value) : 0,
  )
  // The day of the topmost message shows while the reader scrolls back, and fades a moment after.
  // Following the newest message (a growing answer scrolls too) shows nothing.
  const days = computed(() => rowDays(rows.value))
  const floatingDay = reactive({ label: '', visible: false })
  let dayTimer: ReturnType<typeof setTimeout> | undefined
  function scrolledThroughDays() {
    if (bottom.following.value) return void (floatingDay.visible = false)
    const top = scroller.value?.scrollTop ?? 0
    const item = virtualizer.value.getVirtualItems().find((virtual) => virtual.end > top)
    // The day's own heading at the top already says it.
    const label = item && rows.value[item.index]?.kind !== 'day' ? days.value[item.index] : ''
    if (!label) return void (floatingDay.visible = false)
    floatingDay.label = label
    floatingDay.visible = true
    clearTimeout(dayTimer)
    dayTimer = setTimeout(() => (floatingDay.visible = false), 1200)
  }
  useEventListener(scroller, 'scroll', scrolledThroughDays, { passive: true })
  onBeforeUnmount(() => clearTimeout(dayTimer))

  // A long history renders only the rows near the screen. The list starts below the controls
  // above it (scrollMargin). Rows keep their place by key when earlier pages arrive or a row above
  // the screen changes height (anchorTo: 'end'); following the newest message stays with
  // useFollowBottom.
  const scrollMargin = ref(0)
  function placeFeed() {
    scrollMargin.value = feed.value?.offsetTop ?? 0
  }
  // In the next frame: the list's offset changes the feed's height, and the content around it is
  // observed too; changing it inside this callback is a WebKit "ResizeObserver loop" error.
  useResizeObserver(lead, () => requestAnimationFrame(placeFeed))
  watch(feed, placeFeed, { flush: 'post' })
  // The row of a message opened from search always exists, so it can be found and centred.
  const anchorRow = computed(() => (anchor.value ? rowOf(rows.value, anchor.value) : -1))
  const virtualizer = useVirtualizer(
    computed(() => {
      const list = rows.value
      const keep = new Set([anchorRow.value].filter((index) => index >= 0))
      return {
        rangeExtractor: (range: Range) => {
          const indexes = defaultRangeExtractor(range)
          const missing = [...keep].filter((index) => !indexes.includes(index))
          return missing.length ? [...indexes, ...missing].sort((a, b) => a - b) : indexes
        },
        count: list.length,
        getScrollElement: () => scroller.value,
        estimateSize: (index: number) => estimateRow(list[index]),
        getItemKey: (index: number) => list[index]?.key ?? index,
        scrollMargin: scrollMargin.value,
        overscan: 8,
        anchorTo: 'end' as const,
        followOnAppend: false,
        // The list ends above the conversation's own bottom, so it would count a reader a few lines
        // up as "at the end" and pull them down with a growing answer. Never: useFollowBottom decides.
        scrollEndThreshold: -1,
        // Measured in the next frame: a row resized inside the observer's callback is a WebKit
        // "ResizeObserver loop" error.
        useAnimationFrameWithResizeObserver: true,
      }
    }),
  )
  // While the newest message is followed, useFollowBottom holds the bottom: corrections of the list
  // would come on top of it, and on iOS they arrive late, after the reader may have scrolled up.
  // Otherwise the list's own rule: a row measured for the first time above the screen, or a row
  // wholly above it that changed, not while scrolling up.
  virtualizer.value.shouldAdjustScrollPositionOnItemSizeChange = (item, _delta, instance) => {
    if (bottom.following.value) return false
    const top = instance.scrollOffset ?? 0
    return instance.itemSizeCache.has(item.key)
      ? item.end <= top && instance.scrollDirection !== 'backward'
      : item.start < top
  }
  // Rows above are measured late and a sent message turns into its saved exchange: while the
  // screen is held, the question keeps its place. Its own answer grows inside the room and moves
  // nothing.
  watch(
    () => virtualizer.value.getTotalSize(),
    () => void nextTick(keepQuestion),
    { flush: 'post' },
  )
  const visible = computed(() =>
    virtualizer.value.getVirtualItems().flatMap((virtual) => {
      const row = rows.value[virtual.index]
      return row ? [{ virtual, row }] : []
    }),
  )
  // A row mounted together with the list is not in the page yet and would measure 0 px. The list
  // keeps a measured size until the row's resize observer reports another, which in WebKit took up
  // to seconds: the rows lay on top of each other and everything below them moved down late.
  function measure(element: Element | ComponentPublicInstance | null) {
    if (!(element instanceof Element)) return
    if (element.isConnected) virtualizer.value.measureElement(element)
    else
      void nextTick(() => {
        if (element.isConnected) virtualizer.value.measureElement(element)
      })
  }
  // The conversation opens at its newest message, and leaving a message opened from search (its
  // button or Back) returns there; a message opened from search is centred once its page is
  // loaded. Only arriving at a conversation moves the reader: a refetch, even one recovering from a
  // failed one, leaves them where they are.
  watch(
    [anchor, () => history.data.value !== undefined],
    ([message, loaded]) => {
      if (!loaded) return
      if (message) void nextTick(() => reveal(message))
      else bottom.follow()
    },
    { immediate: true },
  )

  /** Centres a message opened from search, which may be a reply below a long request in its row.
   * The row is always rendered (anchorRow); once the rows around it are measured, it is centred
   * again. */
  async function reveal(message: string) {
    const exact = () => document.getElementById(`message-${message}`)
    for (let pass = 0; pass < 3; pass++) {
      await frame()
      exact()?.scrollIntoView({ block: 'center' })
      // iOS applies the list's corrections only once scrolling stops: centre again after them.
      for (let wait = 0; wait < 60 && virtualizer.value.isScrolling; wait++) await frame()
    }
  }

  /** The first message on screen and how far below the top of the screen its row starts. */
  function readingAnchor() {
    const top = scroller.value?.scrollTop ?? 0
    const virtual = virtualizer.value
      .getVirtualItems()
      .find((item) => item.end > top && rows.value[item.index]?.kind !== 'day')
    const row = virtual && rows.value[virtual.index]
    return virtual && row ? { key: row.key, offset: virtual.start - top } : null
  }

  async function loadNewer() {
    // Newer rows join below the screen; the oldest page leaves the window above it, and the list
    // keeps the rows on screen where they are.
    const result = await history.fetchPreviousPage()
    if (result.isError) toast.error(errorText(result.error, t))
  }

  // A failed page of earlier messages is said beside the button that retries it: a toast at the
  // top of a phone covers that button.
  const earlierFailed = ref('')
  async function loadEarlier() {
    // Older messages are read where they are, even in a conversation shorter than the screen. The
    // list alone would hold its first row in place, which is a day heading the new rows join.
    bottom.release()
    earlierFailed.value = ''
    const reading = readingAnchor()
    const result = await history.fetchNextPage()
    if (result.isError) earlierFailed.value = errorText(result.error, t)
    await nextTick()
    const index = reading ? rows.value.findIndex((row) => row.key === reading.key) : -1
    const start = virtualizer.value.measurementsCache[index]?.start
    if (reading && start !== undefined) virtualizer.value.scrollToOffset(start - reading.offset)
  }

  return {
    virtualizer,
    /** The rows near the screen, each with its place in the list. */
    visible,
    measure,
    scrollMargin,
    /** The room kept below a raised question for its answer; 0 while none is. */
    questionRoom,
    floatingDay,
    earlierFailed,
    /** The reader is away from the newest messages; the answers that came meanwhile. */
    away,
    unseen,
    showLatest,
    showQuestion,
    loadNewer,
    loadEarlier,
  }
}
