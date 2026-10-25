<script setup lang="ts">
import { CircleAlert } from '@lucide/vue'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { Button } from '@/components/ui/button'
import { useSession } from '@/stores/session'
import { resetLabel } from './limits'

/**
 * S10: the daily limit ran out. Says when it resets in the user's time, points to what still
 * works by hand, and never sends the kept draft on its own after the reset.
 */
defineProps<{ kind: 'assistant' | 'research' | 'dictation_ms' | 'tts_chars' }>()
const emit = defineEmits<{ close: [] }>()
const { t } = useI18n()
const session = useSession()
const when = computed(() => resetLabel(session.me?.timezone ?? 'UTC'))
</script>

<template>
  <div class="bg-warning-surface space-y-3 rounded-2xl p-4" role="alert" data-testid="limit-notice">
    <p class="flex items-start gap-2 font-medium">
      <CircleAlert class="text-warning mt-0.5 size-4 shrink-0" />{{ t(`limits.reached.${kind}`) }}
    </p>
    <p class="text-sm">{{ t('limits.resetAt', { when }) }}</p>
    <p class="text-muted-foreground text-sm">{{ t('limits.manual') }}</p>
    <div class="flex flex-wrap gap-2">
      <Button v-if="session.can('nutrition.write')" size="sm" as-child>
        <RouterLink :to="{ name: 'food' }">{{ t('limits.addFood') }}</RouterLink>
      </Button>
      <Button v-if="session.can('tasks.read')" size="sm" variant="outline" as-child>
        <RouterLink :to="{ name: 'tasks' }">{{ t('limits.openTasks') }}</RouterLink>
      </Button>
      <Button size="sm" variant="ghost" as-child>
        <RouterLink :to="{ name: 'settings', hash: '#limits' }">{{ t('limits.see') }}</RouterLink>
      </Button>
      <Button size="sm" variant="ghost" @click="emit('close')">{{ t('common.close') }}</Button>
    </div>
  </div>
</template>
