<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useModalOcclusion } from '@/components/ui/modalLayers'
import type {
  BodyLayer,
  BodyRenderer,
  BodyRendererMode,
  BodyRendererState,
  BodyScenario,
  BodyView,
  BodyViewState,
} from './bodyGpu'
import { neutralBodyVisual, type BodyVisualState } from './bodyVisual'
import type { BodyCharacterId } from './api'
import { bodyAsset } from './bodyCatalog'

const props = withDefaults(
  defineProps<{
    visual?: BodyVisualState
    characterId?: BodyCharacterId
    layer?: BodyLayer
    paused?: boolean
    animate?: boolean
    scenario?: BodyScenario
    comparing?: boolean
  }>(),
  {
    characterId: 'sculpture',
    layer: 'composition',
    paused: false,
    animate: true,
    scenario: 'idle',
    comparing: false,
  },
)
const emit = defineEmits<{
  renderer: [value: BodyRendererMode]
  'motion-state': [value: BodyRendererState]
  'reduced-motion': [value: boolean]
  loading: [value: boolean]
}>()
const visual = computed<BodyVisualState>(() => props.visual ?? neutralBodyVisual())
const asset = computed(() => bodyAsset(visual.value.template, props.characterId))
const poster = computed(() => asset.value.posterUrl)
const canvas = ref<HTMLCanvasElement>()
const stage = ref<HTMLElement>()
const modalOccluded = useModalOcclusion()
const enhanced = ref(false)
const canvasKey = ref(0)
let renderer: BodyRenderer | null = null
let resizeObserver: ResizeObserver | undefined
let visibilityObserver: IntersectionObserver | undefined
let motionPreference: MediaQueryList | undefined
let inView = false
let unmounted = false
let initializing = false
let abort: AbortController | null = null
let view: BodyViewState | undefined
let pointer: { id: number; x: number; y: number; yaw: number; horizontal: boolean } | null = null
const active = () => inView && !document.hidden && !modalOccluded.value
const motion = () => props.animate && !motionPreference?.matches
let motionStateKey = ''
const updateMotionState = (state: BodyRendererState) => {
  // The UI describes scenes, not individual animation frames.
  const key = JSON.stringify([
    state.scenario,
    state.activeScenario,
    state.queuedScenario,
    state.playing,
    state.paused,
    state.motionSource,
  ])
  if (key === motionStateKey) return
  motionStateKey = key
  emit('motion-state', state)
}
const showPoster = () => {
  motionStateKey = ''
  enhanced.value = false
  emit('renderer', 'poster')
}
const fallback = () => {
  showPoster()
  emit('loading', false)
}
const start = async () => {
  if (unmounted || initializing || renderer || !active() || !canvas.value) return
  initializing = true
  emit('loading', true)
  const controller = new AbortController()
  abort = controller
  try {
    const { createBodyRenderer } = await import('./bodyGpu')
    if (controller.signal.aborted || unmounted) return
    const instance = await createBodyRenderer(canvas.value, {
      visual: visual.value,
      characterId: props.characterId,
      initialView: view,
      layer: props.layer,
      motion: motion(),
      scenario: props.scenario,
      paused: props.paused,
      comparison: props.comparing,
      onState: (state) => {
        if (abort === controller && !controller.signal.aborted && !unmounted)
          updateMotionState(state)
      },
      active: active(),
      signal: controller.signal,
      onFailure: () => {
        if (abort === controller && !controller.signal.aborted && !unmounted) fallback()
      },
    })
    if (controller.signal.aborted || unmounted) {
      instance?.destroy()
      return
    }
    renderer = instance
    if (!renderer) return
    renderer.setVisual(visual.value)
    renderer.setLayer(props.layer)
    renderer.setMotion(motion())
    renderer.setPaused(props.paused)
    renderer.setScenario(props.scenario)
    renderer.setComparison(props.comparing)
    renderer.setActive(active())
    enhanced.value = true
    emit('renderer', renderer.mode)
  } catch {
    if (!controller.signal.aborted) fallback()
  } finally {
    if (abort === controller) {
      initializing = false
      if (!controller.signal.aborted && !unmounted) emit('loading', false)
    }
  }
}
const visibilityChanged = () => {
  renderer?.setActive(active())
  if (active()) void start()
}
watch(modalOccluded, visibilityChanged)
const motionChanged = () => {
  emit('reduced-motion', motionPreference?.matches ?? false)
  renderer?.setMotion(motion())
}

onMounted(() => {
  emit('loading', true)
  emit('renderer', 'poster')
  motionPreference = window.matchMedia?.('(prefers-reduced-motion: reduce)')
  motionPreference?.addEventListener('change', motionChanged)
  motionChanged()
  document.addEventListener('visibilitychange', visibilityChanged)
  if (!stage.value) return
  if (typeof ResizeObserver !== 'undefined') {
    resizeObserver = new ResizeObserver(() => renderer?.resize())
    resizeObserver.observe(stage.value)
  }
  if (typeof IntersectionObserver !== 'undefined') {
    visibilityObserver = new IntersectionObserver(
      ([entry]) => {
        inView = entry?.isIntersecting ?? false
        visibilityChanged()
      },
      { threshold: 0.05 },
    )
    visibilityObserver.observe(stage.value)
  } else {
    inView = true
    visibilityChanged()
  }
})
watch(visual, (value) => renderer?.setVisual(value))
watch(asset, async (value, previous) => {
  if (value === previous) return
  view = renderer?.getViewState() ?? view
  pointer = null
  abort?.abort()
  renderer?.destroy()
  renderer = null
  initializing = false
  emit('loading', true)
  showPoster()
  canvasKey.value++
  await nextTick()
  void start()
})
function setView(value: BodyView) {
  pointer = null
  renderer?.setView(value)
}
defineExpose({ setView })

watch(
  () => props.layer,
  (layer) => renderer?.setLayer(layer),
)
watch(() => props.animate, motionChanged)
watch(
  () => props.paused,
  (value) => renderer?.setPaused(value),
)
watch(
  () => props.scenario,
  (value) => renderer?.setScenario(value),
)
watch(
  () => props.comparing,
  (value) => renderer?.setComparison(value),
)

function pointerDown(event: PointerEvent) {
  if (!enhanced.value || event.button !== 0 || !event.isPrimary) return
  const yaw = renderer?.getViewState().yaw ?? view?.yaw ?? 0.13
  pointer = { id: event.pointerId, x: event.clientX, y: event.clientY, yaw, horizontal: false }
}
function pointerMove(event: PointerEvent) {
  if (!pointer || pointer.id !== event.pointerId) return
  const dx = event.clientX - pointer.x
  const dy = event.clientY - pointer.y
  if (!pointer.horizontal) {
    if (Math.abs(dy) > 8 && Math.abs(dy) > Math.abs(dx)) {
      pointer = null
      return
    }
    if (Math.abs(dx) <= 8) return
    // The preset may have progressed while the gesture was being classified.
    pointer.yaw = renderer?.getViewState().yaw ?? pointer.yaw
    pointer.horizontal = true
    canvas.value?.setPointerCapture?.(event.pointerId)
  }
  renderer?.setYaw(pointer.yaw + dx * 0.006)
}
function pointerEnd(event: PointerEvent) {
  if (pointer?.id !== event.pointerId) return
  if (canvas.value?.hasPointerCapture?.(event.pointerId)) {
    canvas.value.releasePointerCapture(event.pointerId)
  }
  pointer = null
}
onBeforeUnmount(() => {
  unmounted = true
  abort?.abort()
  renderer?.destroy()
  resizeObserver?.disconnect()
  visibilityObserver?.disconnect()
  motionPreference?.removeEventListener('change', motionChanged)
  document.removeEventListener('visibilitychange', visibilityChanged)
})
</script>

<template>
  <div
    ref="stage"
    class="body-figure"
    :class="[`body-figure--${layer}`, { 'body-figure--gpu': enhanced }]"
    aria-hidden="true"
    data-testid="body-figure"
    :data-character-id="asset.characterId"
  >
    <div class="body-figure__aura" />
    <img
      class="body-figure__poster"
      :src="poster"
      alt=""
      loading="lazy"
      decoding="async"
      draggable="false"
    />
    <canvas
      :key="canvasKey"
      ref="canvas"
      class="body-figure__canvas"
      :data-character-id="asset.characterId"
      @pointerdown="pointerDown"
      @pointermove="pointerMove"
      @pointerup="pointerEnd"
      @pointercancel="pointerEnd"
      @lostpointercapture="pointerEnd"
    />
  </div>
</template>

<style scoped>
.body-figure {
  position: relative;
  width: 100%;
  height: 100%;
  min-height: 380px;
  isolation: isolate;
  contain: layout paint;
}
.body-figure__aura {
  position: absolute;
  inset: 10% 2%;
  z-index: -1;
  border-radius: 50%;
  background: radial-gradient(ellipse, var(--body-aura), transparent 69%);
}
.body-figure__poster,
.body-figure__canvas {
  position: absolute;
  inset: 0;
  display: block;
  width: 100%;
  height: 100%;
}
.body-figure__poster {
  object-fit: contain;
}
.body-figure__canvas {
  visibility: hidden;
  touch-action: pan-y;
  cursor: grab;
}
.body-figure__canvas:active {
  cursor: grabbing;
}
.body-figure--gpu .body-figure__canvas {
  visibility: visible;
}
.body-figure--gpu .body-figure__poster {
  visibility: hidden;
}
</style>
