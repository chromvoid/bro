<script setup lang="ts">
import { LifeBuoy, LinkIcon } from '@lucide/vue'
import { useEventListener } from '@vueuse/core'
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { Button } from '@/components/ui/button'
import { useSession } from '@/stores/session'
import AuthLayout from './AuthLayout.vue'
import { readAuthLink, safeNext, type AuthLink } from './links'
import RedeemForm from './RedeemForm.vue'
import SignInForm from './SignInForm.vue'

type Mode = 'signin' | 'restore' | 'redeem' | 'expired' | 'reset-done'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const session = useSession()
const pendingDeletion = ref(false)
try {
  pendingDeletion.value =
    !!sessionStorage.getItem('assistant.account-deletion-pending') ||
    !!sessionStorage.getItem('assistant.account-deletion')
} catch {
  /* Storage may be disabled. */
}

const link = ref<AuthLink | null>(null)
const mode = ref<Mode>('signin')

/** Takes an invitation or reset link from the address, then removes the one-time token from it. */
function acceptLink() {
  const found = readAuthLink(location.hash)
  if (location.hash) history.replaceState(history.state, '', location.pathname + location.search)
  if (!found) return
  link.value = found
  mode.value = 'redeem'
}
acceptLink()
// A link opened while this page is already showing changes only the fragment.
useEventListener(window, 'hashchange', acceptLink)

function proceed() {
  void router.replace(safeNext(route.query.next) ?? { name: 'today' })
}

async function redeemed(login: string | null, password: string) {
  if (!login) {
    mode.value = 'reset-done'
    return
  }
  // A new account signs straight in and starts with the first-run screen.
  await session.signIn(login, password).catch(() => undefined)
  if (session.state === 'ready') await router.replace({ name: 'welcome' })
  else mode.value = 'signin'
}

onMounted(async () => {
  if (link.value) return
  await session.ready()
  if (session.state === 'ready') proceed()
})
</script>

<template>
  <AuthLayout>
    <RouterLink
      v-if="pendingDeletion"
      :to="{ name: 'account-deletion' }"
      class="text-primary block text-sm underline"
      >{{ t('accountDeletion.check') }}</RouterLink
    >
    <SignInForm
      v-if="mode === 'signin' || mode === 'reset-done'"
      :notice="mode === 'reset-done' ? 'reset-done' : undefined"
      @signed-in="proceed"
      @restore="mode = 'restore'"
    />

    <div v-else-if="mode === 'restore'" class="space-y-6">
      <div class="bg-accent text-accent-foreground grid size-12 place-items-center rounded-2xl">
        <LifeBuoy class="size-6" />
      </div>
      <div class="space-y-2">
        <h1 class="text-2xl font-semibold tracking-tight">{{ t('auth.restoreTitle') }}</h1>
        <p class="text-muted-foreground">{{ t('auth.restoreText') }}</p>
        <p class="text-muted-foreground">{{ t('auth.restoreLink') }}</p>
      </div>
      <Button size="lg" variant="outline" class="w-full" @click="mode = 'signin'">{{
        t('auth.backToSignIn')
      }}</Button>
    </div>

    <RedeemForm
      v-else-if="mode === 'redeem' && link"
      :link="link"
      @expired="mode = 'expired'"
      @redeemed="redeemed"
    />

    <div v-else class="space-y-6" role="alert">
      <div class="bg-warning-surface text-warning grid size-12 place-items-center rounded-2xl">
        <LinkIcon class="size-6" />
      </div>
      <div class="space-y-2">
        <h1 class="text-2xl font-semibold tracking-tight">{{ t('auth.linkExpiredTitle') }}</h1>
        <p class="text-muted-foreground">{{ t('auth.linkExpiredText') }}</p>
      </div>
      <Button size="lg" class="w-full" @click="mode = 'signin'">{{
        t('auth.backToSignIn')
      }}</Button>
    </div>
  </AuthLayout>
</template>
