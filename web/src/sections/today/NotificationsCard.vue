<script setup lang="ts">
import { BellRing } from '@lucide/vue'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { isOpenProblem } from '@/sections/notifications/model'
import { useNotifications } from '@/sections/notifications/useNotifications'

/** Open important and urgent problems; the card is absent when there are none. */
const { t } = useI18n()
const { items } = useNotifications()
const loud = computed(() => items.value.filter(isOpenProblem))
</script>

<template>
  <RouterLink
    v-if="loud.length"
    :to="{ name: 'notifications' }"
    class="bg-warning-surface block space-y-2 rounded-3xl p-5"
    data-testid="today-notifications"
  >
    <h2 class="flex items-center gap-2 font-semibold">
      <BellRing class="text-warning size-5" />{{ t('today.notifications') }}
    </h2>
    <ul class="space-y-1">
      <li v-for="item in loud.slice(0, 3)" :key="item.id" class="break-words">{{ item.title }}</li>
    </ul>
    <p v-if="loud.length > 3" class="text-muted-foreground text-sm">
      {{ t('notifications.more', { count: loud.length - 3 }) }}
    </p>
  </RouterLink>
</template>
