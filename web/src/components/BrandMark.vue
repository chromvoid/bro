<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import marks from '@/assets/bro/moods.json'
import { playMerge } from './brandMerge'
import { assistantMood, MERGE_MS, type Mood } from './brandMood'

/**
 * `mood` fixes the mouth, for a mark outside the chat (a smile beside «День заполнен»); without it
 * the mark follows the assistant's state.
 */
const props = defineProps<{ mood?: Mood }>()
const mood = computed(() => props.mood ?? assistantMood.value)

// The mouth on screen trails the state while the dots flow into ")" (an answer is ready).
const shown = ref<Mood>(mood.value)
const merging = ref(false)
const instant = ref(false)
const pieces: (Element | null)[] = []
let timer: ReturnType<typeof setTimeout> | undefined
let stopMerge = () => {}

function still() {
  return window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false
}

watch(mood, (next, previous) => {
  clearTimeout(timer)
  stopMerge()
  if (previous === 'thinking' && (next === 'ok' || next === 'done') && !still()) {
    merging.value = true
    stopMerge = playMerge(
      marks.merge.flatMap((piece, index) => {
        const path = pieces[index]
        return path ? [{ path, ...piece }] : []
      }),
    )
    timer = setTimeout(() => {
      stopMerge()
      // The pieces now draw ")" exactly: swap in the smile itself without a fade, then go on (BD).
      instant.value = true
      merging.value = false
      shown.value = 'ok'
      requestAnimationFrame(() =>
        requestAnimationFrame(() => {
          instant.value = false
          shown.value = mood.value
        }),
      )
    }, MERGE_MS)
    return
  }
  merging.value = false
  shown.value = next
})
onBeforeUnmount(() => {
  clearTimeout(timer)
  stopMerge()
})
</script>

<template>
  <!-- The Bro) mark on its tile; the mouth shows the assistant's state (brandMood.ts). -->
  <svg
    viewBox="0 0 256 256"
    aria-hidden="true"
    focusable="false"
    data-slot="brand-mark"
    :data-mood="mood"
    class="overflow-hidden rounded-xl"
  >
    <rect class="tile" width="256" height="256" />
    <g class="mark" transform="translate(25.6 25.6) scale(0.8)">
      <path :d="marks.b" fill-rule="evenodd" />
      <path :d="marks.ok" class="mouth" :class="{ shown: !merging && shown === 'ok', instant }" />
      <g class="mouth dots" :class="{ shown: !merging && shown === 'thinking' }">
        <path v-for="dot in marks.thinking" :key="dot" :d="dot" class="dot" />
      </g>
      <path :d="marks.done" class="mouth" :class="{ shown: !merging && shown === 'done' }" />
      <path :d="marks.oops" class="mouth" :class="{ shown: !merging && shown === 'oops' }" />
      <!-- Each dot stretches into a third of the smile; the thirds overlap into ")". playMerge
           moves their points; d stays bound to the dot, which never changes, so renders keep
           what it drew. -->
      <g class="merge" :class="{ on: merging }">
        <path
          v-for="(piece, index) in marks.merge"
          :key="index"
          :ref="(element) => (pieces[index] = element as Element | null)"
          :d="piece.from"
        />
      </g>
    </g>
  </svg>
</template>

<style scoped>
.tile {
  fill: var(--icon-background);
}
.mark {
  fill: var(--icon-foreground);
}
.mouth {
  opacity: 0;
  transition: opacity 160ms ease-out;
}
.mouth.shown {
  opacity: 1;
}
.mouth.instant {
  transition: none;
}
.merge {
  visibility: hidden;
}
.merge.on {
  visibility: visible;
}
/* The dots breathe one after another; with reduced motion they simply stay lit. */
.dots.shown .dot {
  animation: brand-mark-dot 1.2s ease-in-out infinite;
}
.dots.shown .dot:nth-child(2) {
  animation-delay: 0.2s;
}
.dots.shown .dot:nth-child(3) {
  animation-delay: 0.4s;
}
@keyframes brand-mark-dot {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.3;
  }
}
</style>
