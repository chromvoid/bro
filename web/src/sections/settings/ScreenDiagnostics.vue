<script setup lang="ts">
import { useEventListener, useMediaQuery } from '@vueuse/core'
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useSafeArea } from '@/core/safeArea'
import { useShell } from '@/core/shell'
import { theme } from '@/core/theme'

/**
 * What the page sees of the screen and of its shell, for checking the frame on a real phone: a
 * release build has no remote inspector, a screenshot of this card does the job.
 */
const { t } = useI18n()
const { insets, measure } = useSafeArea()
const shell = useShell()
const systemDark = useMediaQuery('(prefers-color-scheme: dark)')

const viewport = ref(read())
function read() {
  return {
    width: window.innerWidth,
    height: window.innerHeight,
    visual: Math.round(window.visualViewport?.height ?? window.innerHeight),
  }
}
function update() {
  viewport.value = read()
  measure()
}
useEventListener(window, 'resize', update, { passive: true })
if (window.visualViewport) useEventListener(window.visualViewport, 'resize', update)

const rows = computed(() => {
  const { top, right, bottom, left } = insets.value
  const capabilities = shell.capabilities.value
  return [
    ['insets', [top, right, bottom, left].map((value) => `${value}`).join(' · ')],
    ['viewport', `${viewport.value.width} × ${viewport.value.height}`],
    ['visual', `${viewport.value.visual}`],
    ['scheme', systemDark.value ? 'dark' : 'light'],
    ['theme', t(`theme.${theme.value}`)],
    ['shell', capabilities ? capabilities.platform : t('capabilities.screen.browser')],
  ] as const
})
</script>

<template>
  <section class="space-y-2" aria-labelledby="screen-title" data-testid="screen-diagnostics">
    <h2 id="screen-title" class="font-semibold">{{ t('capabilities.screen.title') }}</h2>
    <dl class="bg-card divide-y rounded-3xl border">
      <div
        v-for="[key, value] in rows"
        :key="key"
        class="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1 px-4 py-3"
      >
        <dt class="text-muted-foreground text-sm">{{ t(`capabilities.screen.${key}`) }}</dt>
        <dd class="font-medium tabular-nums" :data-testid="`screen-${key}`">{{ value }}</dd>
      </div>
    </dl>
  </section>
</template>
