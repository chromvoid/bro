<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { Button } from '@/components/ui/button'
import type { SpeechCatalogState } from './api'

const props = defineProps<{ id: string; catalog?: SpeechCatalogState; disabled?: boolean }>()
const emit = defineEmits<{ refresh: [] }>()
const { t } = useI18n()
const counts = computed(() => ({
  total: props.catalog?.models.length ?? 0,
  asr: props.catalog?.models.filter((model) => model.capabilities.includes('asr')).length ?? 0,
  tts: props.catalog?.models.filter((model) => model.capabilities.includes('tts')).length ?? 0,
}))
</script>

<template>
  <div class="space-y-2 px-4 pb-4" :data-testid="`speech-catalog-${id}`">
    <p v-if="catalog?.phase === 'loading'" class="text-muted-foreground text-sm" role="status">
      {{ t('speechSettings.modelsLoading') }}
    </p>
    <p v-else-if="catalog?.failure" class="text-destructive text-sm" role="alert">
      {{ t(catalog.failure) }}
    </p>
    <template v-else-if="catalog?.phase === 'ready'">
      <details v-if="counts.total" class="text-sm">
        <summary class="text-muted-foreground cursor-pointer">
          {{ t('speechSettings.modelCounts', counts) }}
        </summary>
        <ul class="mt-3 max-h-64 space-y-2 overflow-y-auto">
          <li v-for="model in catalog.models" :key="model.id">
            <p class="font-medium">{{ model.name }}</p>
            <p class="text-muted-foreground">
              {{
                model.capabilities
                  .map((capability) => t(`speechSettings.${capability}Capability`))
                  .join(' · ')
              }}
            </p>
            <p
              v-if="model.capabilities.includes('tts') && !model.voices.length"
              class="text-warning"
            >
              {{ t('speechSettings.voicesMissing') }}
            </p>
            <p v-else-if="!model.selectable" class="text-warning">
              {{ t('speechSettings.unavailableModel') }}
            </p>
          </li>
        </ul>
      </details>
      <p v-else class="text-warning text-sm" role="status">{{ t('speechSettings.modelsEmpty') }}</p>
    </template>
    <Button
      type="button"
      variant="outline"
      :disabled="disabled || catalog?.phase === 'loading'"
      @click="emit('refresh')"
      >{{ t('speechSettings.refreshModels') }}</Button
    >
  </div>
</template>
