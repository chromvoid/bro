<script setup lang="ts">
import { X } from '@lucide/vue'
import { useMediaQuery } from '@vueuse/core'
import { computed, nextTick, onBeforeUnmount, ref, useId, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { Button } from '@/components/ui/button'
import {
  Sheet,
  SheetClose,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
} from '@/components/ui/sheet'

/**
 * A task panel: a bottom sheet on phones, a side panel on wider screens. `docked`, on a wide
 * page, makes it a column of the page instead: the list beside it stays usable. The `footer` slot
 * stays at the bottom edge while the body scrolls (the actions of an edit), above the gesture bar.
 */
defineOptions({ inheritAttrs: false })
const props = withDefaults(
  defineProps<{
    title: string
    description?: string
    /**
     * Focus the first field on open. Off for panels that open to read first: on a phone the
     * keyboard would cover what the person came to see. Focus then stays on the panel itself.
     */
    autofocus?: boolean
    /** A column of the page, not a dialog over it; the page decides when there is room. */
    docked?: boolean
  }>(),
  { autofocus: true, docked: false },
)
const open = defineModel<boolean>('open', { required: true })
const { t } = useI18n()
const wide = useMediaQuery('(min-width: 768px)')

/** Without autofocus the panel itself takes focus: reading starts at its title, no keyboard. */
function openFocus(event: Event) {
  if (props.autofocus) return
  event.preventDefault()
  ;(event.target as HTMLElement | null)?.focus({ preventScroll: true })
}
const side = computed(() => (wide.value ? 'right' : 'bottom'))

/** A toast over the panel (the undo after «+») is not a click outside: the panel stays open. */
function keepForToast(event: Event) {
  const detail = (event as CustomEvent<{ originalEvent?: Event }>).detail
  const target = detail?.originalEvent?.target ?? event.target
  if (target instanceof Element && target.closest('[data-sonner-toaster]')) event.preventDefault()
}

// The docked column: focus goes into it when it opens and back to where it was when it closes,
// also when the page removes the panel together with its item.
const titleId = useId()
const column = ref<HTMLElement | null>(null)
const body = ref<HTMLElement | null>(null)
/** Switching cards moves only this panel to its beginning; the workspace keeps its position. */
function resetScroll() {
  const container = props.docked ? column.value : body.value
  if (container) container.scrollTop = 0
}
defineExpose({ resetScroll })
let opener: HTMLElement | null = null
function giveBack() {
  const target = opener
  opener = null
  void nextTick(() => {
    const lost = !document.activeElement || document.activeElement === document.body
    if (lost && target?.isConnected) target.focus({ preventScroll: true })
  })
}
watch(
  () => props.docked && open.value,
  async (shown, was) => {
    if (shown && !was) {
      opener = document.activeElement instanceof HTMLElement ? document.activeElement : null
      await nextTick()
      const field = props.autofocus
        ? column.value?.querySelector<HTMLElement>('input:not([type=hidden]), textarea, select')
        : null
      ;(field ?? column.value)?.focus({ preventScroll: true })
    } else if (!shown && was) giveBack()
  },
  // A panel can be created open: the page mounts it with the item.
  { immediate: true },
)
onBeforeUnmount(() => {
  if (props.docked && open.value) giveBack()
})
</script>

<template>
  <aside
    v-if="docked && open"
    ref="column"
    v-bind="$attrs"
    :aria-labelledby="titleId"
    tabindex="-1"
    class="bg-card sticky top-[calc(var(--app-top)+1rem)] max-h-[calc(100dvh-var(--app-top)-2rem)] space-y-4 overflow-y-auto rounded-3xl border p-4 outline-none"
    @keydown.esc.stop="open = false"
  >
    <div class="flex flex-wrap items-start gap-2">
      <div class="min-w-0 flex-1 basis-48">
        <h2 :id="titleId" class="text-xl leading-tight font-semibold break-words">
          <slot name="title">{{ title }}</slot>
        </h2>
        <p v-if="description" class="text-muted-foreground mt-1 text-sm">{{ description }}</p>
      </div>
      <div class="ms-auto flex max-w-full shrink-0 flex-wrap items-start gap-2">
        <slot name="actions" />
        <Button
          variant="ghost"
          size="icon-sm"
          class="-mt-1 -mr-1 min-h-11 min-w-11 shrink-0"
          :aria-label="t('common.close')"
          @click="open = false"
          ><X
        /></Button>
      </div>
    </div>
    <slot />
    <!-- Reaches the panel's edge through its padding, which a sticky box otherwise keeps. -->
    <div
      v-if="$slots.footer"
      data-slot="panel-footer"
      class="bg-card sticky -bottom-4 z-10 -mx-4 -mb-4 flex gap-2 border-t px-4 py-3"
    >
      <slot name="footer" />
    </div>
  </aside>
  <Sheet v-else v-model:open="open">
    <!-- gap-0: the header's own padding parts it from the content; the sheet's gap would double it. -->
    <SheetContent
      v-bind="$attrs"
      :side="side"
      :show-close-button="false"
      :class="
        side === 'bottom'
          ? 'max-h-[calc(100dvh-var(--inset-top)-1rem)] gap-0 overflow-hidden rounded-t-3xl'
          : 'w-full gap-0 overflow-hidden sm:max-w-md'
      "
      @open-auto-focus="openFocus"
      @interact-outside="keepForToast"
    >
      <SheetHeader class="bg-popover shrink-0 flex-row flex-wrap items-start gap-2 text-left">
        <!-- A rem basis gives a large title its own row before the actions squeeze it thin. -->
        <div class="min-w-0 flex-1 basis-48">
          <SheetTitle class="text-xl break-words"
            ><slot name="title">{{ title }}</slot></SheetTitle
          >
          <SheetDescription v-if="description">{{ description }}</SheetDescription>
        </div>
        <div class="ms-auto flex max-w-full shrink-0 flex-wrap items-start gap-2">
          <slot name="actions" />
          <SheetClose as-child>
            <Button
              variant="ghost"
              size="icon"
              class="-mt-1 -mr-1 min-h-11 min-w-11 shrink-0"
              :aria-label="t('common.close')"
              ><X
            /></Button>
          </SheetClose>
        </div>
      </SheetHeader>
      <!-- The scrollport ends at the header and footer, so focused fields cannot hide below them. -->
      <div
        ref="body"
        data-slot="panel-body"
        class="min-h-0 flex-1 scroll-py-2 space-y-4 overflow-y-auto px-4 pb-6"
      >
        <slot />
      </div>
      <!-- A bottom sheet pads itself by the gesture bar: the footer covers that strip too and
           keeps its buttons above it. -->
      <div
        v-if="$slots.footer"
        data-slot="panel-footer"
        :class="[
          'bg-popover flex shrink-0 gap-2 border-t px-4 pt-3',
          side === 'bottom'
            ? '-mb-(--inset-bottom) pb-[calc(0.75rem+var(--inset-bottom))]'
            : 'pb-3',
        ]"
      >
        <slot name="footer" />
      </div>
    </SheetContent>
  </Sheet>
</template>
