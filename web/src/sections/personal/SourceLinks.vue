<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { toast } from 'vue-sonner'
import { Button } from '@/components/ui/button'
import type { ResolvedPage } from './resolutionContract'
import type { SourceEntry } from './sources'
import { usePersonal } from './usePersonal'

/**
 * One physical device delivered through two connections, such as a ring paired again: the
 * person confirms it, and proven copies of one reading then count once.
 */
const props = defineProps<{
  source: SourceEntry
  sources: SourceEntry[]
  identities: NonNullable<ResolvedPage['source_identities']>
  names: Map<string, string>
  canWrite: boolean
}>()
const { t } = useI18n()
const personal = usePersonal()
const busy = ref(false)
const physical = computed(() => {
  const identity = props.identities[props.source.id]
  return identity?.confirmed ? (identity.physical_id ?? null) : null
})
const linked = computed(() =>
  physical.value
    ? props.sources.filter(
        (item) =>
          item.id !== props.source.id &&
          props.identities[item.id]?.confirmed &&
          props.identities[item.id]?.physical_id === physical.value,
      )
    : [],
)
/** Only connections of the same kind of device can be one device. */
const candidates = computed(() =>
  props.sources.filter(
    (item) =>
      item.id !== props.source.id &&
      item.device.kind === props.source.device.kind &&
      !linked.value.some((other) => other.id === item.id),
  ),
)
const name = (item: SourceEntry) => props.names.get(item.id) ?? item.name

async function link(other: SourceEntry) {
  if (busy.value) return
  busy.value = true
  const ids = [props.source.id, other.id]
  try {
    await personal.operate(
      'resolution.source.link',
      {
        source_ids: ids,
        identity_revisions: Object.fromEntries(
          ids.map((id) => [id, props.identities[id]?.revision ?? 0]),
        ),
      },
      { [props.source.id]: props.source.revision, [other.id]: other.revision },
    )
    toast.success(t('resolution.linked'))
  } catch (error) {
    personal.failed(error)
  } finally {
    busy.value = false
  }
}
async function unlink() {
  if (busy.value) return
  busy.value = true
  try {
    await personal.operate(
      'resolution.source.unlink',
      {
        source_id: props.source.id,
        identity_revision: props.identities[props.source.id]?.revision ?? 0,
      },
      { [props.source.id]: props.source.revision },
    )
    toast.success(t('resolution.unlinked'))
  } catch (error) {
    personal.failed(error)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div
    v-if="linked.length || (canWrite && candidates.length)"
    class="space-y-2 text-sm"
    data-testid="source-links"
  >
    <p class="font-medium">{{ t('resolution.linksTitle') }}</p>
    <template v-if="linked.length">
      <p class="text-muted-foreground">{{ linked.map(name).join(' · ') }}</p>
      <Button v-if="canWrite" size="sm" variant="ghost" :disabled="busy" @click="unlink">{{
        t('resolution.unlink')
      }}</Button>
    </template>
    <template v-else>
      <Button
        v-for="other in candidates"
        :key="other.id"
        size="sm"
        variant="outline"
        :disabled="busy"
        @click="link(other)"
        >{{ t('resolution.link') }}: {{ name(other) }}</Button
      >
    </template>
  </div>
</template>
