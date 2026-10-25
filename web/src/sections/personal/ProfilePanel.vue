<script setup lang="ts">
import { nextTick, ref, useId } from 'vue'
import { useI18n } from 'vue-i18n'
import Panel from '@/components/Panel.vue'
import { Button } from '@/components/ui/button'
import messages from '@/locales/personal-forms-ru.json'
import type { Profile } from './api'
import type { ProfileField } from './profile'
import ProfileForm from './ProfileForm.vue'

const props = withDefaults(
  defineProps<{
    profile: Profile
    today: string
    focusField?: ProfileField
    readOnly?: boolean
    autofocus?: boolean
  }>(),
  { readOnly: false, autofocus: true },
)
const open = defineModel<boolean>('open', { required: true })
const { t } = useI18n({
  useScope: 'local',
  messages: { ru: messages },
  missingWarn: false,
  fallbackWarn: false,
})
const form = ref<InstanceType<typeof ProfileForm> | null>(null)
const formId = `profile-form-${useId()}`
async function focusRequested() {
  if (!props.focusField || props.readOnly) return
  await nextTick()
  form.value?.focus(props.focusField)
}
</script>

<template>
  <Panel
    v-model:open="open"
    :title="t('personal.bodyProfileTitle')"
    :description="t('personal.profileHint')"
    :autofocus="!readOnly && autofocus && !focusField"
    @open-auto-focus="focusRequested"
  >
    <ProfileForm
      v-if="open"
      ref="form"
      :profile="profile"
      :today="today"
      :focus-field="focusField"
      :read-only="readOnly"
      :form-id="formId"
      external-actions
      @saved="open = false"
    />
    <template v-if="!readOnly" #footer>
      <Button
        type="submit"
        :form="formId"
        size="lg"
        class="flex-1 whitespace-normal"
        :disabled="form?.busy"
      >
        {{ form?.busy ? t('personalForms.saving') : t('personal.saveProfile') }}
      </Button>
      <Button
        v-if="form?.dirty"
        type="button"
        variant="outline"
        :disabled="form?.busy"
        @click="form?.reset()"
      >
        {{ t('personal.resetProfile') }}
      </Button>
    </template>
  </Panel>
</template>
