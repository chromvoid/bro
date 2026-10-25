<script setup lang="ts">
import { TriangleAlert } from '@lucide/vue'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAttention } from '@/core/attention'

/** H05: writes whose outcome is unknown, grouped by the section where they are settled. */
const { t, te } = useI18n()
const { entries, bySection } = useAttention()
const ROUTES: Record<string, string> = {
  nutrition: 'food',
  tasks: 'tasks',
  timers: 'timers',
  personal: 'personal',
  conversation: 'assistant',
}
const sections = computed(() =>
  [...bySection.value.entries()].map(([section, items]) => ({
    section,
    count: items.length,
    route: ROUTES[section] ?? 'today',
    label: te(`today.sections.${section}`) ? t(`today.sections.${section}`) : section,
  })),
)
</script>

<template>
  <section
    v-if="entries.length"
    class="bg-warning-surface space-y-3 rounded-3xl p-5"
    role="status"
    data-testid="attention"
  >
    <h2 class="flex items-center gap-2 font-semibold">
      <TriangleAlert class="text-warning size-5" />{{ t('today.attention') }}
    </h2>
    <p class="text-muted-foreground text-sm">
      {{ t('today.attentionText', { count: entries.length }, entries.length) }}
    </p>
    <ul class="flex flex-wrap gap-2">
      <li v-for="item in sections" :key="item.section">
        <RouterLink
          :to="{ name: item.route }"
          class="bg-card inline-flex h-11 items-center gap-2 rounded-xl border px-4 text-sm font-medium"
        >
          {{ item.label }} · {{ item.count }}
        </RouterLink>
      </li>
    </ul>
  </section>
</template>
