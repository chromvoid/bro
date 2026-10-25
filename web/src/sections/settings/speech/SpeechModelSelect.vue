<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import { NativeSelect } from '@/components/ui/native-select'
import { usableModel, type SpeechCatalogState } from './api'

const props = defineProps<{
  id: string
  label: string
  capability: 'asr' | 'tts'
  catalog?: SpeechCatalogState
  modelValue: string | null
  disabled?: boolean
  refreshable?: boolean
  excludedModel?: string
}>()
const emit = defineEmits<{ 'update:modelValue': [value: string | null]; refresh: [] }>()
const { t } = useI18n()
const models = computed(
  () =>
    props.catalog?.models.filter((model) => model.capabilities.includes(props.capability)) ?? [],
)
const selected = computed(() => models.value.find((model) => model.id === props.modelValue))
const ready = computed(() => props.catalog?.phase === 'ready')
</script>

<template>
  <div class="space-y-2">
    <Label :for="id">{{ label }}</Label>
    <NativeSelect
      :id="id"
      :key="models.map((model) => model.id).join('|')"
      :model-value="modelValue ?? ''"
      :disabled="disabled || !ready || !models.length"
      :aria-busy="catalog?.phase === 'loading'"
      @update:model-value="emit('update:modelValue', $event ?? null)"
    >
      <option v-if="!modelValue" value="" disabled>{{ t('speechSettings.selectModel') }}</option>
      <option v-else-if="!selected" :value="modelValue" disabled>{{ modelValue }}</option>
      <option
        v-for="model in models"
        :key="model.id"
        :value="model.id"
        :disabled="model.id === excludedModel || !usableModel(model, capability)"
      >
        {{ model.name
        }}{{
          model.id === excludedModel
            ? ` · ${t('speechSettings.primaryModelShort')}`
            : capability === 'tts' && !model.voices.length
              ? ` · ${t('speechSettings.voicesMissingShort')}`
              : !usableModel(model, capability)
                ? ` · ${t('speechSettings.modelUnavailableShort')}`
                : ''
        }}
      </option>
    </NativeSelect>
    <p v-if="catalog?.phase === 'loading'" class="text-muted-foreground text-sm" role="status">
      {{ t('speechSettings.modelsLoading') }}
    </p>
    <p v-else-if="catalog?.failure" class="text-destructive text-sm" role="alert">
      {{ t(catalog.failure) }}
    </p>
    <template v-else-if="ready">
      <p
        v-if="
          excludedModel &&
          (modelValue === excludedModel ||
            (!modelValue &&
              !models.some(
                (model) => model.id !== excludedModel && usableModel(model, capability),
              )))
        "
        class="text-warning text-sm"
        role="status"
      >
        {{ t('speechSettings.duplicateBackupModel') }}
      </p>
      <p v-else-if="modelValue && !selected" class="text-warning text-sm" role="status">
        {{ t('speechSettings.unavailableModel') }}
      </p>
      <p v-else-if="!models.length" class="text-warning text-sm" role="status">
        {{ t(`speechSettings.noModelsFor.${capability}`) }}
      </p>
      <p
        v-else-if="selected && !usableModel(selected, capability)"
        class="text-warning text-sm"
        role="status"
      >
        {{
          t(
            capability === 'tts' && !selected.voices.length
              ? 'speechSettings.voicesMissing'
              : 'speechSettings.unavailableModel',
          )
        }}
      </p>
      <p
        v-if="selected?.asr_mode === 'file' && capability === 'asr'"
        class="text-muted-foreground text-sm"
      >
        {{ t('speechSettings.fileAsrHint') }}
      </p>
    </template>
    <Button
      v-if="refreshable"
      type="button"
      variant="outline"
      :disabled="disabled || catalog?.phase === 'loading'"
      @click="emit('refresh')"
      >{{ t('speechSettings.refreshModels') }}</Button
    >
  </div>
</template>
