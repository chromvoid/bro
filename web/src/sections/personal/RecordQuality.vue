<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { recordQualityMessages } from './recordQuality'

const props = defineProps<{ flags?: string[] | null }>()
const { t } = useI18n()
const messages = computed(() => recordQualityMessages(props.flags))
</script>

<template>
  <div v-if="messages.length" class="text-muted-foreground space-y-1" data-testid="record-quality">
    <ul class="space-y-1" data-testid="record-quality-explanations">
      <li v-for="(message, index) in messages" :key="index">{{ t(message.messageKey) }}</li>
    </ul>
    <details data-testid="record-quality-codes">
      <summary class="min-h-11 cursor-pointer py-2">{{ t('recordQuality.codes') }}</summary>
      <ul class="space-y-1 break-all">
        <li v-for="(message, index) in messages" :key="index">
          <code>{{ message.code }}</code>
        </li>
      </ul>
    </details>
  </div>
</template>
