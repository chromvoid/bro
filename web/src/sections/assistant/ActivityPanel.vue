<script setup lang="ts">
import { useIntersectionObserver } from '@vueuse/core'
import { computed, inject, onScopeDispose, ref, toRef, useTemplateRef, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import MarkdownText from '@/components/MarkdownText'
import { useDeveloperMode } from '@/core/developer'
import { stepLabel } from './activity'
import { conversationScroller } from './follow'
import { useActivity } from './useActivity'
import UsageDetails from './UsageDetails.vue'

const props = defineProps<{ messageId: string; queued: boolean }>()
const emit = defineEmits<{ settled: [] }>()
const { t } = useI18n()
// Steps with durations and model usage are for an administrator who asked for them.
const developer = useDeveloperMode()
const element = useTemplateRef<HTMLElement>('element')
const visible = ref(false)
// Measured against the conversation's scroller: a margin around the page would stop at its edge.
useIntersectionObserver(
  element,
  ([entry]) => {
    visible.value = !!entry?.isIntersecting
  },
  { root: inject(conversationScroller, undefined), rootMargin: '160px' },
)
const enabled = computed(() => visible.value && !props.queued)
const {
  snapshot,
  preview: text,
  connection,
} = useActivity(toRef(props, 'messageId'), enabled, () => emit('settled'))
// Live, or ended and waiting for the saved reply to replace this panel.
const preview = computed(() =>
  ['connected', 'ended'].includes(connection.value) ? text.value : '',
)
const live = computed(() => connection.value === 'connected' && snapshot.value?.status === 'active')
const phase = computed(() => {
  if (live.value && snapshot.value) {
    if (snapshot.value.phase === 'tool') {
      const step = snapshot.value.steps.findLast(
        (step) => step.status === 'running' && step.kind === 'tool',
      )
      if (step) return stepLabel(step)
    }
    if (snapshot.value.phase) return `assistant.activity.${snapshot.value.phase}`
    return 'assistant.processing'
  }
  if (connection.value === 'ended') return 'assistant.activity.verifying'
  // The progress stream is a convenience: while it connects, waits for the run to start or is
  // away, the person only needs to know that the request is in work. The answer itself arrives
  // with the conversation's own events.
  return props.queued ? 'assistant.queued' : 'assistant.processing'
})
const elapsed = ref(0)
let receivedAt = 0
watch(snapshot, (value) => {
  receivedAt = Date.now()
  elapsed.value = value?.elapsed_ms ?? 0
})
const timer = setInterval(() => {
  if (snapshot.value?.status === 'active' && connection.value === 'connected')
    elapsed.value = snapshot.value.elapsed_ms + Math.max(0, Date.now() - receivedAt)
}, 1000)
onScopeDispose(() => clearInterval(timer))
</script>

<template>
  <div ref="element" class="max-w-[min(92%,40rem)] space-y-2" data-testid="activity">
    <!-- Public text is escaped; never announce each token or infer a receipt from it. -->
    <!-- Looks like the answer it becomes and starts where its text will: the stage line and the
         details go under it, so the saved reply takes its place without a jump. -->
    <div v-if="preview" aria-live="off" data-testid="live-answer">
      <MarkdownText :text="preview" />
    </div>
    <div class="text-muted-foreground flex flex-wrap items-center gap-x-2 gap-y-1 text-sm">
      <span
        class="bg-primary size-1.5 shrink-0 rounded-full"
        :class="{ 'motion-safe:animate-pulse': live }"
        aria-hidden="true"
      />
      <span role="status" :class="live && 'shimmer'" data-testid="processing">{{ t(phase) }}</span>
      <span
        v-if="live && snapshot?.started_at"
        class="tabular-nums"
        data-testid="activity-elapsed"
        >{{ t('assistant.activity.seconds', { value: Math.floor(elapsed / 1000) }) }}</span
      >
    </div>
    <details
      v-if="developer && snapshot && (snapshot.steps.length || snapshot.usage?.calls)"
      class="text-muted-foreground text-xs"
    >
      <summary class="cursor-pointer py-1">{{ t('assistant.activity.details') }}</summary>
      <ol class="my-2 space-y-1.5" data-testid="activity-steps">
        <li
          v-for="step in snapshot.steps"
          :key="step.id"
          class="flex flex-wrap items-baseline gap-x-2"
        >
          <span>{{ t(stepLabel(step)) }}</span>
          <span>{{ t(`assistant.activity.step.${step.status}`) }}</span>
          <span v-if="step.duration_ms !== null" class="tabular-nums">{{
            t('assistant.activity.seconds', { value: (step.duration_ms / 1000).toFixed(1) })
          }}</span>
        </li>
      </ol>
      <UsageDetails v-if="snapshot.usage?.calls" :usage="snapshot.usage" />
    </details>
  </div>
</template>
