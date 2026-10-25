<script setup lang="ts">
import 'leaflet/dist/leaflet.css'
import type { Map as LeafletMap } from 'leaflet'
import { useResizeObserver } from '@vueuse/core'
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import locationPlacesMessages from '@/locales/location-places-ru.json'
import type { Day } from './api'

/**
 * A day's track and stays on OpenStreetMap (docs/location-weather-spec.md §5.3). Tiles come from this
 * server (/v1/maps/osm), so the page's CSP and referrer policy stay as they are; Leaflet styles its
 * elements through the CSSOM, and the track's colours are palette tokens set by class.
 *
 * Leaflet measures its container once: a map made before the container has a size fits the day into
 * nothing, and one whose container changes size without a window resize leaves grey edges. So the map
 * is made once the container has a size and is told of every change after that.
 */
const props = defineProps<{ day: Day }>()
const { t } = useI18n({ useScope: 'local', messages: { ru: locationPlacesMessages } })
const element = ref<HTMLElement | null>(null)
let leaflet: typeof import('leaflet') | null = null
let map: LeafletMap | null = null

function draw() {
  const L = leaflet
  const container = element.value
  if (!L || !container?.clientWidth || !container.clientHeight) return
  map?.remove()
  map = L.map(container, { zoomControl: true })
  map.attributionControl.setPrefix(false)
  L.tileLayer('/v1/maps/osm/{z}/{x}/{y}.png', {
    maxZoom: 19,
    attribution: t('places.attribution'),
  }).addTo(map)
  const track = props.day.track.map(([latitude, longitude]) => L.latLng(latitude, longitude))
  const stays = props.day.stays.map((stay) => L.latLng(stay.latitude, stay.longitude))
  if (track.length > 1) L.polyline(track, { className: 'stroke-chart-1', weight: 4 }).addTo(map)
  for (const stay of stays) {
    L.circleMarker(stay, { radius: 7, className: 'stroke-chart-1 fill-chart-1' }).addTo(map)
  }
  const all = [...track, ...stays]
  if (all.length) map.fitBounds(L.latLngBounds(all), { padding: [24, 24], maxZoom: 16 })
}

onMounted(async () => {
  leaflet = await import('leaflet')
  draw()
})
watch(() => props.day, draw)
useResizeObserver(element, () => (map ? map.invalidateSize() : draw()))
onBeforeUnmount(() => {
  map?.remove()
  map = null
})
</script>

<template>
  <div
    ref="element"
    class="isolate h-72 overflow-hidden rounded-3xl border"
    role="img"
    :aria-label="t('places.map')"
    data-testid="places-map"
  />
</template>
