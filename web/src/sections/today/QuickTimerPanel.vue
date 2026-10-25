<script setup lang="ts">
import { ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { toast } from 'vue-sonner'
import Panel from '@/components/Panel.vue'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { errorText } from '@/core/errors'
import { useLedger } from '@/core/operations'
import { TIMERS } from '@/sections/tasks/api'

/** A timer from «Сегодня» in one tap: a preset starts it; the name is optional. */
const open = defineModel<boolean>('open', { required: true })
const { t } = useI18n()
const ledger = useLedger(TIMERS)
const presets = [1, 5, 10, 15, 30, 60]
const label = ref('')
const busy = ref(false)
watch(open, (value) => value && (label.value = ''))

async function start(minutes: number) {
  if (busy.value) return
  busy.value = true
  try {
    const payload: Record<string, unknown> = { duration_seconds: minutes * 60 }
    if (label.value.trim()) payload.label = label.value.trim()
    await ledger.operate({ kind: 'timer.start', payload })
    toast.success(t('tasks.started'))
    open.value = false
  } catch (error) {
    toast.error(errorText(error, t))
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <Panel v-model:open="open" :title="t('tasks.newTimer')">
    <div class="space-y-2">
      <Label for="quick-timer-label">{{ t('tasks.label') }}</Label>
      <Input id="quick-timer-label" v-model="label" maxlength="60" />
    </div>
    <div class="grid grid-cols-3 gap-2" role="group" :aria-label="t('tasks.presets')">
      <Button
        v-for="preset in presets"
        :key="preset"
        variant="outline"
        size="lg"
        :disabled="busy"
        @click="start(preset)"
      >
        {{ t('tasks.minutesShort', { count: preset }) }}
      </Button>
    </div>
  </Panel>
</template>
