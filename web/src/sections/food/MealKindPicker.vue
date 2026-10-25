<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import SegmentedControl from '@/components/SegmentedControl.vue'
import { MEAL_KINDS, type MealKind } from './api'

/** Which meal the food is for; nothing chosen logs it without a kind. */
const kind = defineModel<MealKind | ''>({ required: true })
const { t } = useI18n()
const segments = computed(() =>
  MEAL_KINDS.map((value) => ({ value, label: t(`food.mealKinds.${value}`) })),
)
</script>

<template>
  <div class="space-y-2">
    <p class="text-sm font-medium" aria-hidden="true">{{ t('food.mealKind') }}</p>
    <SegmentedControl
      :segments="segments"
      :label="t('food.mealKind')"
      :model-value="kind"
      stretch
      @update:model-value="kind = $event as MealKind"
    />
  </div>
</template>
