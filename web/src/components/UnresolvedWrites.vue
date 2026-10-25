<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { toast } from 'vue-sonner'
import UnknownOutcome from '@/components/state/UnknownOutcome.vue'
import { Button } from '@/components/ui/button'
import { errorText } from '@/core/errors'
import type { Submission } from '@/core/journal'

/** Writes whose outcome is unknown: check first, resend the same request only on request. */
const props = defineProps<{
  entries: Submission[]
  describe: (entry: Submission) => string
  check: (key: string) => Promise<unknown>
  resend: (key: string) => Promise<unknown>
  abandon: (key: string) => Promise<unknown>
}>()
const { t } = useI18n()
const busy = ref<string | null>(null)

async function run(key: string, task: (key: string) => Promise<unknown>) {
  busy.value = key
  try {
    await task(key)
  } catch (error) {
    toast.error(errorText(error, t))
  } finally {
    busy.value = null
  }
}
</script>

<template>
  <div v-if="props.entries.length" class="space-y-3" data-testid="unresolved-writes">
    <div v-for="entry in props.entries" :key="entry.key" class="space-y-2">
      <UnknownOutcome
        :description="props.describe(entry)"
        :checking="busy === entry.key"
        can-resend
        @check="run(entry.key, props.check)"
        @resend="run(entry.key, props.resend)"
      />
      <Button
        size="sm"
        variant="ghost"
        :disabled="busy === entry.key"
        @click="run(entry.key, props.abandon)"
      >
        {{ t('outcome.abandon') }}
      </Button>
    </div>
  </div>
</template>
