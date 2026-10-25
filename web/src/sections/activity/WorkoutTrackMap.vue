<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { computed, ref, watch } from 'vue'
import { Button } from '@/components/ui/button'
import {
  projectSegment,
  trackView,
  visibleTiles,
  drawableSegments,
  type TrackPoint,
  type TrackSegment,
} from './workoutTrack'

const { t } = useI18n()
const props = defineProps<{ segments: TrackSegment[] }>()
const drawable = computed(() => drawableSegments(props.segments))
const points = computed(() => drawable.value.flatMap((segment) => segment.points))
const view = ref<{ zoom: number; x: number; y: number } | null>(null)
watch(
  points,
  (value) => {
    view.value = trackView(value)
  },
  { immediate: true },
)
const tiles = computed(() => (view.value ? visibleTiles(view.value) : []))
function zoom(direction: number) {
  if (!view.value) return
  const level = Math.max(1, Math.min(19, view.value.zoom + direction))
  const scale = 2 ** (level - view.value.zoom)
  view.value = { zoom: level, x: view.value.x * scale, y: view.value.y * scale }
}
function pan(x: number, y: number) {
  if (view.value) view.value = { ...view.value, x: view.value.x + x, y: view.value.y + y }
}
function position(point: TrackPoint) {
  const center = view.value
  if (!center) return { x: 0, y: 0 }
  const projected = projectSegment([point], center.zoom, center.x)[0]!
  return { x: projected.x - center.x + 320, y: projected.y - center.y + 180 }
}
function polyline(segment: { points: TrackPoint[] }) {
  const center = view.value
  return center
    ? projectSegment(segment.points, center.zoom, center.x)
        .map((projected) => `${projected.x - center.x + 320},${projected.y - center.y + 180}`)
        .join(' ')
    : ''
}
</script>

<template>
  <div v-if="view" class="space-y-2" data-testid="workout-track-map">
    <div class="flex flex-wrap gap-2">
      <Button size="sm" variant="outline" :aria-label="t('workoutTrack.zoomIn')" @click="zoom(1)"
        >+</Button
      >
      <Button size="sm" variant="outline" :aria-label="t('workoutTrack.zoomOut')" @click="zoom(-1)"
        >−</Button
      >
      <Button
        size="sm"
        variant="outline"
        :aria-label="t('workoutTrack.panLeft')"
        @click="pan(-160, 0)"
        >←</Button
      >
      <Button size="sm" variant="outline" :aria-label="t('workoutTrack.panUp')" @click="pan(0, -90)"
        >↑</Button
      >
      <Button
        size="sm"
        variant="outline"
        :aria-label="t('workoutTrack.panDown')"
        @click="pan(0, 90)"
        >↓</Button
      >
      <Button
        size="sm"
        variant="outline"
        :aria-label="t('workoutTrack.panRight')"
        @click="pan(160, 0)"
        >→</Button
      >
      <Button size="sm" variant="ghost" @click="view = trackView(points)">{{
        t('workoutTrack.fit')
      }}</Button>
    </div>
    <svg
      viewBox="0 0 640 360"
      class="bg-muted w-full overflow-hidden rounded-xl border"
      role="img"
      :aria-label="t('workoutTrack.mapLabel')"
    >
      <image
        v-for="tile in tiles"
        :key="tile.key"
        :href="tile.href"
        :x="tile.x"
        :y="tile.y"
        width="256"
        height="256"
      />
      <polyline
        v-for="(segment, index) in drawable"
        :key="index"
        :points="polyline(segment)"
        fill="none"
        :class="
          segment.quality !== 'valid'
            ? 'stroke-muted-foreground'
            : segment.context === 'recovery'
              ? 'stroke-warning'
              : 'stroke-primary'
        "
        stroke-width="4"
        stroke-linecap="round"
        stroke-linejoin="round"
        :stroke-dasharray="segment.context === 'recovery' ? '8 6' : undefined"
      >
        <title>
          {{ t(`workoutTrack.contexts.${segment.context}`) }}
          · {{ segment.quality }}
        </title>
      </polyline>
      <circle
        v-for="(segment, index) in drawable.filter((value) => value.points.length === 1)"
        :key="`point-${index}`"
        :cx="position(segment.points[0]!).x"
        :cy="position(segment.points[0]!).y"
        r="4"
        :class="
          segment.quality !== 'valid'
            ? 'fill-muted-foreground'
            : segment.context === 'recovery'
              ? 'fill-warning'
              : 'fill-primary'
        "
      />
    </svg>
    <p class="text-muted-foreground text-xs">
      {{ t('workoutTrack.mapExplanation') }} {{ t('workoutTrack.mapAttributionPrefix') }}
      <a
        href="https://www.openstreetmap.org/copyright"
        target="_blank"
        rel="noopener noreferrer"
        class="text-primary underline"
        >{{ t('workoutTrack.mapAttributionName') }}</a
      >
      {{ t('workoutTrack.mapAttributionSuffix') }}
    </p>
  </div>
</template>
