<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import localeMessages from '@/locales/derived-scores-ru.json'
import { derivedMissingMessages } from './derivedMetadata'

const props = defineProps<{ codes: string[] }>()
const { t } = useI18n({
  useScope: 'local',
  messages: { ru: localeMessages },
  missingWarn: false,
  fallbackWarn: false,
})
const messages = computed(() => derivedMissingMessages(props.codes, t))
</script>

<template>
  <div v-if="messages.length" class="text-warning space-y-1 text-sm" data-testid="derived-missing">
    <p>{{ t('derivedMissingInputs.title') }}</p>
    <ul class="space-y-1" data-testid="derived-missing-explanations">
      <li v-for="(message, index) in messages" :key="index">{{ message.label }}</li>
    </ul>
    <details class="text-muted-foreground text-xs" data-testid="derived-missing-codes">
      <summary class="min-h-11 cursor-pointer py-2">
        {{ t('derivedMissingInputs.codesTitle') }}
      </summary>
      <ul class="space-y-1 break-all">
        <li v-for="(message, index) in messages" :key="index">
          <code>{{ message.code }}</code>
        </li>
      </ul>
    </details>
  </div>
</template>
