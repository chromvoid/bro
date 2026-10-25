<script setup lang="ts">
import { CircleAlert, Clock, KeyRound } from '@lucide/vue'
import { useIntervalFn } from '@vueuse/core'
import { computed, nextTick, ref, useTemplateRef } from 'vue'
import { useI18n } from 'vue-i18n'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { errorText, retryAfterSeconds } from '@/core/errors'
import { ApiError } from '@/core/http'
import { useSession } from '@/stores/session'
import PasswordInput from './PasswordInput.vue'

defineProps<{ notice?: 'reset-done' }>()
const emit = defineEmits<{ signedIn: []; restore: [] }>()

const { t } = useI18n()
const session = useSession()
const login = ref('')
const password = ref('')
const busy = ref(false)
const failure = ref<'credentials' | 'other' | null>(null)
const message = ref('')
const wait = ref(0)
const passwordField = useTemplateRef<HTMLElement>('password-field')
const { pause, resume } = useIntervalFn(
  () => {
    wait.value = Math.max(0, wait.value - 1)
    if (!wait.value) pause()
  },
  1000,
  { immediate: false },
)
const blocked = computed(() => wait.value > 0)

async function submit() {
  if (busy.value || blocked.value) return
  busy.value = true
  failure.value = null
  try {
    await session.signIn(login.value.trim(), password.value)
    if (session.state === 'ready') emit('signedIn')
    else {
      failure.value = 'other'
      message.value = t('errors.server')
    }
  } catch (error) {
    const seconds = retryAfterSeconds(error)
    if (seconds) {
      wait.value = seconds
      resume()
    } else if (error instanceof ApiError && error.status === 401) {
      // Keep the login; clear only the password and return focus to it.
      failure.value = 'credentials'
      password.value = ''
      await nextTick()
      passwordField.value?.querySelector('input')?.focus()
    } else {
      failure.value = 'other'
      message.value = errorText(error, t)
    }
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="space-y-6">
    <div class="space-y-1.5">
      <h1 class="text-2xl font-semibold tracking-tight">{{ t('auth.welcomeBack') }}</h1>
      <p class="text-muted-foreground">{{ t('auth.signInHint') }}</p>
    </div>

    <Alert v-if="session.expired" variant="warning">
      <Clock />
      <AlertTitle>{{ t('auth.expiredTitle') }}</AlertTitle>
      <AlertDescription>{{ t('auth.expiredText') }}</AlertDescription>
    </Alert>
    <Alert v-if="notice === 'reset-done'" variant="success">
      <KeyRound />
      <AlertTitle>{{ t('auth.resetDoneTitle') }}</AlertTitle>
      <AlertDescription>{{ t('auth.resetDoneText') }}</AlertDescription>
    </Alert>

    <form class="space-y-4" novalidate @submit.prevent="submit">
      <div class="space-y-2">
        <Label for="signin-login">{{ t('auth.login') }}</Label>
        <Input
          id="signin-login"
          v-model="login"
          autocomplete="username"
          autocapitalize="none"
          spellcheck="false"
          required
          maxlength="40"
        />
      </div>
      <div ref="password-field" class="space-y-2">
        <Label for="signin-password">{{ t('auth.password') }}</Label>
        <PasswordInput
          id="signin-password"
          v-model="password"
          autocomplete="current-password"
          :invalid="failure === 'credentials'"
          :describedby="failure ? 'signin-error' : undefined"
        />
      </div>

      <Alert v-if="blocked" id="signin-error" variant="warning" role="status">
        <Clock />
        <AlertTitle>{{ t('auth.pauseTitle') }}</AlertTitle>
        <AlertDescription>{{ t('auth.pauseText', { seconds: wait }) }}</AlertDescription>
      </Alert>
      <Alert v-else-if="failure" id="signin-error" variant="destructive" role="alert">
        <CircleAlert />
        <AlertTitle>{{
          failure === 'credentials' ? t('auth.checkTitle') : t('auth.failedTitle')
        }}</AlertTitle>
        <AlertDescription>{{
          failure === 'credentials' ? t('auth.checkText') : message
        }}</AlertDescription>
      </Alert>

      <Button
        type="submit"
        size="lg"
        class="w-full"
        :disabled="busy || blocked || !login || !password"
      >
        {{ busy ? t('auth.signingIn') : t('auth.signIn') }}
      </Button>
    </form>

    <Button variant="link" class="h-auto px-0" @click="emit('restore')">{{
      t('auth.forgot')
    }}</Button>
  </div>
</template>
