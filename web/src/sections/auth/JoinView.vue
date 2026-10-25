<script setup lang="ts">
import { LinkIcon } from '@lucide/vue'
import { useEventListener } from '@vueuse/core'
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { Button } from '@/components/ui/button'
import { api } from '@/core/client'
import { z } from '@/core/zod'
import AuthLayout from './AuthLayout.vue'
import { browserAuthLink, readJoinLink, type JoinLink } from './links'

const { t } = useI18n()
const link = ref<JoinLink | null>(null)
const apk = ref<string | null>(null)
const host = computed(() => (link.value ? new URL(link.value.server).host : ''))
const browser = computed(() => (link.value ? browserAuthLink(link.value) : ''))
const Manifest = z.object({
  release: z.object({ version_code: z.number().int().positive() }).nullable(),
})

function acceptLink() {
  const hash = location.hash
  // Keep the accepted link when a later event sees the scrubbed address.
  if (!hash) return
  link.value = readJoinLink(hash)
  history.replaceState(history.state, '', location.pathname + location.search)
}
acceptLink()
useEventListener(window, 'hashchange', acceptLink)

onMounted(async () => {
  try {
    const { release } = Manifest.parse(await api.get('/v1/shell/android'))
    if (release) apk.value = `/v1/shell/android/${release.version_code}.apk`
  } catch {
    // The browser remains available when no Android release can be downloaded.
  }
})
</script>

<template>
  <AuthLayout>
    <div v-if="link" class="space-y-6">
      <div class="space-y-2">
        <h1 class="text-2xl font-semibold tracking-tight">
          {{ t(link.kind === 'invite' ? 'auth.joinTitle' : 'auth.joinResetTitle') }}
        </h1>
        <p class="text-muted-foreground wrap-anywhere">{{ host }}</p>
      </div>
      <div class="space-y-3">
        <Button
          v-if="apk"
          as="a"
          :href="apk"
          size="lg"
          class="h-auto min-h-11 w-full whitespace-normal"
        >
          {{ t('auth.downloadAndroid') }}
        </Button>
        <Button
          as="a"
          :href="browser"
          size="lg"
          variant="outline"
          class="h-auto min-h-11 w-full whitespace-normal"
        >
          {{ t('auth.continueInBrowser') }}
        </Button>
      </div>
    </div>
    <div v-else class="space-y-6" role="alert">
      <div class="bg-warning-surface text-warning grid size-12 place-items-center rounded-2xl">
        <LinkIcon class="size-6" />
      </div>
      <h1 class="text-2xl font-semibold tracking-tight">{{ t('auth.linkExpiredTitle') }}</h1>
    </div>
  </AuthLayout>
</template>
