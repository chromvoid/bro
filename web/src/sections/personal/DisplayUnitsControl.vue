<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { Button } from '@/components/ui/button'
import messages from '@/locales/display-units-ru.json'
import type { Profile, Overview } from './api'
import { Profile as ProfileSchema } from './api'
import { usePersonal } from './usePersonal'
import { useQueryClient } from '@tanstack/vue-query'
import type { DisplayUnits } from './displayUnits'

const props = defineProps<{ profile: Profile; canWrite: boolean }>()
const { t } = useI18n({ useScope: 'local', messages: { ru: messages } })
const personal = usePersonal()
const client = useQueryClient()
const busy = ref(false)
const selected = computed(() => props.profile.display_units)
async function save(display_units: DisplayUnits) {
  if (!props.canWrite || busy.value || selected.value === display_units) return
  const owner = props.profile.user_id
  const revision = props.profile.revision
  busy.value = true
  try {
    const receipt = await personal.operate(
      'profile.update',
      { display_units },
      { [owner]: revision },
    )
    const profile = ProfileSchema.parse((receipt as { profile: unknown }).profile)
    if (owner !== props.profile.user_id || profile.user_id !== owner) return
    client.setQueriesData<Overview>({ queryKey: ['personal', 'overview'] }, (overview) =>
      overview &&
      overview.profile.user_id === owner &&
      overview.profile.revision <= profile.revision
        ? { ...overview, profile }
        : overview,
    )
  } catch (error) {
    personal.failed(error)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <section class="bg-card space-y-3 rounded-3xl border p-5" data-testid="display-units-control">
    <h2 class="font-semibold">{{ t('displayUnits.title') }}</h2>
    <p class="text-muted-foreground text-sm">
      {{
        t(selected === 'imperial' ? 'displayUnits.imperialSummary' : 'displayUnits.metricSummary')
      }}
      {{ t('displayUnits.hint') }}
    </p>
    <div
      v-if="canWrite"
      class="flex flex-wrap gap-2"
      role="group"
      :aria-label="t('displayUnits.title')"
    >
      <Button
        size="sm"
        variant="outline"
        :aria-pressed="selected === 'metric'"
        :disabled="busy"
        @click="save('metric')"
        >{{ t('displayUnits.metric') }}</Button
      >
      <Button
        size="sm"
        variant="outline"
        :aria-pressed="selected === 'imperial'"
        :disabled="busy"
        @click="save('imperial')"
        >{{ t('displayUnits.imperial') }}</Button
      >
    </div>
  </section>
</template>
