<script setup lang="ts">
import { Eye, EyeOff } from '@lucide/vue'
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { Input } from '@/components/ui/input'

defineProps<{ id: string; autocomplete: string; invalid?: boolean; describedby?: string }>()
const model = defineModel<string>({ required: true })
const visible = ref(false)
const { t } = useI18n()
</script>

<template>
  <div class="relative">
    <Input
      :id="id"
      v-model="model"
      :type="visible ? 'text' : 'password'"
      :autocomplete="autocomplete"
      :aria-invalid="invalid || undefined"
      :aria-describedby="describedby"
      maxlength="256"
      class="pr-12"
    />
    <button
      type="button"
      class="text-muted-foreground hover:text-foreground absolute inset-y-0 right-0 grid w-11 place-items-center rounded-r-xl"
      :aria-label="visible ? t('auth.hidePassword') : t('auth.showPassword')"
      :aria-pressed="visible"
      @click="visible = !visible"
    >
      <EyeOff v-if="visible" class="size-5" />
      <Eye v-else class="size-5" />
    </button>
  </div>
</template>
