<script setup lang="ts">
import { useDocumentVisibility } from '@vueuse/core'
import { Blocks } from '@lucide/vue'
import { computed, markRaw, onBeforeUnmount, shallowRef, watch, type Component } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/state/EmptyState.vue'
import ErrorState from '@/components/state/ErrorState.vue'
import LoadingState from '@/components/state/LoadingState.vue'
import type { PluginUi } from './api'
import type { PlatformPluginHost } from './host'
import { usePlugins } from './store'

/**
 * A plugin's screen: the module the catalog allows, or a fallback that explains why there is
 * none. The module is in the foreground only while this screen is shown and the page is visible.
 */
const { t } = useI18n()
const route = useRoute()
const plugins = usePlugins()
if (plugins.state === 'idle' || plugins.state === 'error') void plugins.load()

const id = computed(() => String(route.params.installation ?? ''))
const entry = computed(
  () => plugins.entries.find((item) => item.installation.installation_id === id.value) ?? null,
)
const host = shallowRef<PlatformPluginHost | null>(null)
const view = shallowRef<Component | null>(null)
const failed = shallowRef<unknown>(null)
// A catalog refresh recomputes the entries; only a different module or installation reloads.
watch(
  [() => entry.value?.ui ?? null, id],
  async ([ui]: [PluginUi | null, string]) => {
    const next = ui ? plugins.host(id.value) : null
    if (host.value !== next) host.value?.visibility(false)
    host.value = next
    view.value = null
    failed.value = null
    if (!ui) return
    try {
      const loaded = await ui.component()
      view.value = markRaw('default' in loaded ? loaded.default : loaded)
    } catch (error) {
      // A chunk of an older build can be gone after a deploy.
      failed.value = error
    }
  },
  { immediate: true },
)

const visibility = useDocumentVisibility()
watch(
  () => [host.value, visibility.value] as const,
  ([current, visible]) => current?.visibility(visible === 'visible'),
  { immediate: true },
)
onBeforeUnmount(() => host.value?.visibility(false))
</script>

<template>
  <PageHeader
    :title="entry?.installation.title ?? t('plugins.title')"
    :description="entry?.installation.description ?? undefined"
    width="5xl"
  />
  <!-- A plugin cannot widen the app: its content is clipped to the column (clip keeps sticky). -->
  <div class="mx-auto max-w-5xl min-w-0 space-y-5 overflow-x-clip px-4 pb-8 md:px-8">
    <LoadingState v-if="plugins.state === 'loading' && !entry" :rows="3" />
    <ErrorState
      v-else-if="plugins.state === 'error' && !entry"
      :error="plugins.error"
      @retry="plugins.load()"
    />
    <EmptyState
      v-else-if="plugins.state === 'ready' && !entry"
      :icon="Blocks"
      :title="t('plugins.missing')"
      :description="t('plugins.missingText')"
    />
    <EmptyState
      v-else-if="entry && entry.fallback"
      :icon="Blocks"
      :title="t(`plugins.fallback.${entry.fallback}`)"
      :description="
        [entry.fallback === 'unavailable' ? entry.installation.note : null, t('plugins.others')]
          .filter(Boolean)
          .join(' ')
      "
      data-testid="plugin-fallback"
    />
    <ErrorState v-else-if="failed" :error="failed" @retry="$router.go(0)" />
    <component :is="view" v-else-if="view && host" :host="host" />
    <LoadingState v-else-if="entry" :rows="3" />
  </div>
</template>
