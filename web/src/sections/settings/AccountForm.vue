<script setup lang="ts">
import { computed, reactive, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { toast } from 'vue-sonner'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { NativeSelect } from '@/components/ui/native-select'
import { api } from '@/core/client'
import { isTimeZone } from '@/core/dates'
import { errorText } from '@/core/errors'
import { timeZones } from '@/sections/auth/links'
import { useSession } from '@/stores/session'

/** S08: the name, language and time zone the account uses everywhere (dates of the diary too). */
const emit = defineEmits<{ saved: [] }>()
const { t } = useI18n()
const session = useSession()
const zones = timeZones()
const LANGUAGES = [
  { value: 'ru', label: 'Русский' },
  { value: 'en', label: 'English' },
]
const form = reactive({ name: '', locale: 'ru', timezone: '', busy: false, error: '' })
// Keep a language the server already has even when this client does not list it.
const languages = computed(() =>
  LANGUAGES.some((language) => language.value === session.me?.locale) || !session.me
    ? LANGUAGES
    : [...LANGUAGES, { value: session.me.locale, label: session.me.locale }],
)
watch(
  () => session.me,
  (me) => {
    if (!me) return
    Object.assign(form, { name: me.name, locale: me.locale, timezone: me.timezone, error: '' })
  },
  { immediate: true },
)

async function save() {
  form.error = !form.name.trim()
    ? t('settings.nameRequired')
    : !isTimeZone(form.timezone)
      ? t('personal.zoneInvalid')
      : ''
  if (form.error || form.busy) return
  form.busy = true
  try {
    // The server takes all three fields; the last save wins.
    await api.post('/v1/me', {
      name: form.name.trim(),
      locale: form.locale,
      timezone: form.timezone,
    })
    await session.load()
    toast.success(t('settings.saved'))
    emit('saved')
  } catch (error) {
    toast.error(errorText(error, t))
  } finally {
    form.busy = false
  }
}
</script>

<template>
  <form class="space-y-4" novalidate @submit.prevent="save">
    <div class="space-y-2">
      <Label for="account-name">{{ t('settings.name') }}</Label>
      <Input id="account-name" v-model="form.name" maxlength="100" autocomplete="name" />
    </div>
    <div class="space-y-2">
      <Label for="account-locale">{{ t('settings.locale') }}</Label>
      <NativeSelect id="account-locale" v-model="form.locale">
        <option v-for="language in languages" :key="language.value" :value="language.value">
          {{ language.label }}
        </option>
      </NativeSelect>
    </div>
    <div class="space-y-2">
      <Label for="account-timezone">{{ t('settings.timezone') }}</Label>
      <Input
        id="account-timezone"
        v-model="form.timezone"
        list="account-timezones"
        autocomplete="off"
      />
      <datalist id="account-timezones">
        <option v-for="zone in zones" :key="zone" :value="zone" />
      </datalist>
      <p class="text-muted-foreground text-sm">{{ t('settings.timezoneHint') }}</p>
    </div>
    <p v-if="form.error" class="text-destructive text-sm" role="alert">{{ form.error }}</p>
    <Button type="submit" size="lg" class="w-full" :disabled="form.busy">
      {{ t('common.save') }}
    </Button>
    <RouterLink
      v-if="session.can('personal.read')"
      :to="{ name: 'personal-profile' }"
      class="text-primary block text-sm underline-offset-4 hover:underline"
    >
      {{ t('settings.bodyElsewhere') }}
    </RouterLink>
  </form>
</template>
