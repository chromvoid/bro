<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import Panel from '@/components/Panel.vue'
import { cn } from '@/lib/utils'
import StatusActionButton from './StatusActionButton.vue'
import { TONE } from './tone'
import type { ShellStatus } from './useShellStatus'

/** Every status item in full: what happened, what it means, what can be done. */
const props = defineProps<{ status: ShellStatus }>()
const { t } = useI18n()
function close() {
  props.status.setOpen(false)
}
</script>

<template>
  <Panel
    :open="status.open.value"
    :title="t('status.title')"
    :autofocus="false"
    @update:open="status.setOpen"
  >
    <ul class="space-y-3" data-testid="status-sheet">
      <li
        v-for="item in status.items.value"
        :key="item.id"
        class="bg-card space-y-3 rounded-2xl border p-4"
        :data-testid="item.testid"
      >
        <div class="flex items-start gap-3">
          <component
            :is="item.icon"
            :class="cn('mt-0.5 size-5 shrink-0', TONE[item.tone])"
            aria-hidden="true"
          />
          <div class="min-w-0 flex-1 space-y-1">
            <p class="font-medium">{{ item.line }}</p>
            <p v-if="item.detail" class="text-muted-foreground text-sm">{{ item.detail }}</p>
          </div>
        </div>
        <div v-if="item.actions.length" class="flex flex-wrap gap-2 ps-8">
          <StatusActionButton
            v-for="action in item.actions"
            :key="action.label"
            :action="action"
            @done="close"
          />
        </div>
      </li>
    </ul>
  </Panel>
</template>
