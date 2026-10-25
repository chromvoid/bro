<script setup lang="ts">
import { Info } from '@lucide/vue'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

/**
 * What a card's figures rest on (partial history, a schematic figure, how instruments differ),
 * folded under one «ⓘ О данных» line: the card shows its data first, and the caveats stay one tap
 * away instead of piling up in small grey print. Notes that ask for an action stay on the card.
 */
const props = defineProps<{ notes: Array<string | false | null | undefined> }>()
const { t } = useI18n()
const shown = computed(() => props.notes.filter((note): note is string => !!note))
</script>

<template>
  <details v-if="shown.length" class="text-sm" data-testid="data-notes">
    <summary
      class="text-muted-foreground hover:text-foreground flex min-h-11 cursor-pointer items-center gap-1.5 before:hidden"
    >
      <Info class="size-4 shrink-0" aria-hidden="true" />{{ t('common.dataNotes') }}
    </summary>
    <ul class="text-muted-foreground space-y-1.5 pb-1">
      <li v-for="note in shown" :key="note">{{ note }}</li>
    </ul>
  </details>
</template>
