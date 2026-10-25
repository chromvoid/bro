<script setup lang="ts">
import { CircleAlert, Clock } from '@lucide/vue'
import { computed, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import FieldError from '@/components/FieldError.vue'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { isTimeZone } from '@/core/dates'
import { errorText, retryAfterSeconds } from '@/core/errors'
import { ApiError } from '@/core/http'
import { useSession } from '@/stores/session'
import { LOGIN_PATTERN, MIN_PASSWORD, browserTimeZone, timeZones, type AuthLink } from './links'
import PasswordInput from './PasswordInput.vue'

const props = defineProps<{ link: AuthLink }>()
const emit = defineEmits<{ expired: []; redeemed: [login: string | null, password: string] }>()

const { t } = useI18n()
const session = useSession()
const invite = computed(() => props.link.kind === 'invite')
const form = reactive({
  name: '',
  login: '',
  password: '',
  repeat: '',
  timezone: browserTimeZone(),
})
const zones = timeZones()
const touched = ref(false)
const busy = ref(false)
const serverError = ref('')
const loginTaken = ref(false)
const wait = ref(0)

const problems = computed(() => ({
  name: invite.value && !form.name.trim() ? t('auth.nameRequired') : '',
  login: invite.value && !LOGIN_PATTERN.test(form.login.trim()) ? t('auth.loginRule') : '',
  password:
    form.password.length < MIN_PASSWORD ? t('auth.passwordRule', { count: MIN_PASSWORD }) : '',
  repeat: form.repeat !== form.password ? t('auth.repeatMismatch') : '',
  // Any zone the browser knows, not only the list's: Chromium leaves its own «UTC» out of it.
  timezone: invite.value && !isTimeZone(form.timezone) ? t('auth.timezoneUnknown') : '',
}))
const valid = computed(() => Object.values(problems.value).every((problem) => !problem))

async function submit() {
  touched.value = true
  if (!valid.value || busy.value) return
  busy.value = true
  serverError.value = ''
  loginTaken.value = false
  try {
    await session.redeem({
      token: props.link.token,
      password: form.password,
      ...(invite.value
        ? { login: form.login.trim(), name: form.name.trim(), timezone: form.timezone }
        : {}),
    })
    emit('redeemed', invite.value ? form.login.trim() : null, form.password)
  } catch (error) {
    const seconds = retryAfterSeconds(error)
    if (seconds) wait.value = seconds
    else if (error instanceof ApiError && error.status === 409) loginTaken.value = true
    // The fields were checked here, so the server's refusal is about the link itself.
    else if (
      error instanceof ApiError &&
      error.status === 422 &&
      error.serverMessage?.startsWith('Ссылка')
    )
      emit('expired')
    else serverError.value = errorText(error, t)
  } finally {
    busy.value = false
  }
}

const shown = (field: keyof typeof problems.value) => (touched.value ? problems.value[field] : '')
</script>

<template>
  <div class="space-y-6">
    <div class="space-y-1.5">
      <h1 class="text-2xl font-semibold tracking-tight">
        {{ invite ? t('auth.inviteTitle') : t('auth.resetTitle') }}
      </h1>
      <p class="text-muted-foreground">{{ invite ? t('auth.inviteHint') : t('auth.resetHint') }}</p>
    </div>

    <form class="space-y-4" novalidate @submit.prevent="submit">
      <template v-if="invite">
        <div class="space-y-2">
          <Label for="redeem-name">{{ t('auth.name') }}</Label>
          <Input
            id="redeem-name"
            v-model="form.name"
            autocomplete="name"
            maxlength="100"
            :aria-invalid="!!shown('name') || undefined"
          />
          <p v-if="shown('name')" class="text-destructive text-sm">{{ shown('name') }}</p>
        </div>
        <div class="space-y-2">
          <Label for="redeem-login">{{ t('auth.login') }}</Label>
          <Input
            id="redeem-login"
            v-model="form.login"
            autocomplete="username"
            autocapitalize="none"
            spellcheck="false"
            maxlength="40"
            aria-describedby="redeem-login-hint"
            :aria-invalid="!!shown('login') || loginTaken || undefined"
          />
          <FieldError v-if="shown('login') || loginTaken" id="redeem-login-hint">{{
            loginTaken ? t('auth.loginTaken') : t('auth.ruleBroken', { rule: t('auth.loginRule') })
          }}</FieldError>
          <p v-else id="redeem-login-hint" class="text-muted-foreground text-sm">
            {{ t('auth.loginRule') }}
          </p>
        </div>
      </template>
      <div class="space-y-2">
        <Label for="redeem-password">{{ t('auth.newPassword') }}</Label>
        <PasswordInput
          id="redeem-password"
          v-model="form.password"
          autocomplete="new-password"
          describedby="redeem-password-hint"
          :invalid="!!shown('password')"
        />
        <FieldError v-if="shown('password')" id="redeem-password-hint">{{
          t('auth.ruleBroken', { rule: t('auth.passwordRule', { count: MIN_PASSWORD }) })
        }}</FieldError>
        <p v-else id="redeem-password-hint" class="text-muted-foreground text-sm">
          {{ t('auth.passwordRule', { count: MIN_PASSWORD }) }}
        </p>
      </div>
      <div class="space-y-2">
        <Label for="redeem-repeat">{{ t('auth.repeatPassword') }}</Label>
        <PasswordInput
          id="redeem-repeat"
          v-model="form.repeat"
          autocomplete="new-password"
          :invalid="!!shown('repeat')"
        />
        <p v-if="shown('repeat')" class="text-destructive text-sm">{{ shown('repeat') }}</p>
      </div>
      <div v-if="invite" class="space-y-2">
        <Label for="redeem-timezone">{{ t('auth.timezone') }}</Label>
        <Input
          id="redeem-timezone"
          v-model="form.timezone"
          list="redeem-timezones"
          autocomplete="off"
          :aria-invalid="!!shown('timezone') || undefined"
        />
        <datalist id="redeem-timezones">
          <option v-for="zone in zones" :key="zone" :value="zone" />
        </datalist>
        <p :class="['text-sm', shown('timezone') ? 'text-destructive' : 'text-muted-foreground']">
          {{ shown('timezone') || t('auth.timezoneHint') }}
        </p>
      </div>

      <Alert v-if="wait" variant="warning" role="status">
        <Clock />
        <AlertTitle>{{ t('auth.pauseTitle') }}</AlertTitle>
        <AlertDescription>{{ t('auth.pauseText', { seconds: wait }) }}</AlertDescription>
      </Alert>
      <Alert v-if="serverError" variant="destructive" role="alert">
        <CircleAlert />
        <AlertTitle>{{ t('auth.failedTitle') }}</AlertTitle>
        <AlertDescription>{{ serverError }}</AlertDescription>
      </Alert>

      <Button type="submit" size="lg" class="w-full" :disabled="busy">
        {{ invite ? t('auth.acceptInvite') : t('auth.savePassword') }}
      </Button>
      <p class="text-muted-foreground text-sm">{{ t('auth.oneTimeLink') }}</p>
    </form>
  </div>
</template>
