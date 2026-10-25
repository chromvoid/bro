<script setup lang="ts">
import { RouterView, useRouter } from 'vue-router'
import { watch, onBeforeUnmount } from 'vue'
import { useEventListener } from '@vueuse/core'
import { useSession } from '@/stores/session'
import { speechPlayback } from '@/speech/playback'
import { Toaster } from '@/components/ui/sonner'
import { i18n } from '@/i18n'
import { useShell } from '@/core/shell'
import { nativeNotifications } from '@/sections/notifications/native'
import { nativeAmbientAudio } from '@/ambient/native'
// The toast close button is read aloud: «Закрыть», not the library's English default.
const toastOptions = {
  classes: { toast: 'rounded-2xl' },
  closeButtonAriaLabel: i18n.global.t('common.close'),
}
const session = useSession()
const router = useRouter()
const native = nativeNotifications()
const ambient = nativeAmbientAudio()
const shell = useShell()
watch(
  () =>
    [
      session.me?.id,
      session.me?.device_id,
      session.me?.session_expires_at,
      session.state === 'ready',
      session.nativeSessionLapsed,
    ] as const,
  () => {
    if (session.state === 'ready' || session.state === 'signed-out') {
      void native.account(session.state === 'ready' ? session.me : null).catch(() => undefined)
      const identity = session.state === 'ready' ? session.me : null
      void (
        identity
          ? ambient.account(identity)
          : ambient.account(null, { preserveQueue: session.nativeSessionLapsed === true })
      ).catch(() => undefined)
    }
  },
  { immediate: true, flush: 'sync' },
)
const stopNative = shell.on((event) => {
  if (event.event === 'shell.foreground') {
    refreshForeground()
    return
  }
  void native.event(event).catch(() => undefined)
  void ambient.event(event).catch(() => undefined)
})
watch(native.opened, (opened) => {
  if (opened && router.resolve(opened.link).name !== 'not-found') void router.push(opened.link)
})
function refreshForeground() {
  if (router.currentRoute.value.name === 'join') return
  void ambient.refreshState().catch(() => undefined)
  void session
    .load()
    .then(() => native.refreshState())
    .then(() => native.sync())
    .catch(() => undefined)
}
useEventListener(document, 'visibilitychange', () => {
  if (document.visibilityState === 'visible') refreshForeground()
})
useEventListener(window, 'pageshow', () => {
  void native.sync().catch(() => undefined)
  void ambient.refreshState().catch(() => undefined)
})
watch(
  () => [session.me?.id, session.state] as const,
  ([id, state]) => speechPlayback.account(state === 'ready' ? id : undefined),
  { immediate: true, flush: 'sync' },
)
useEventListener(window, 'pagehide', () => speechPlayback.stop())
onBeforeUnmount(() => {
  speechPlayback.account(undefined)
  stopNative()
  void native.account(null).catch(() => undefined)
})
</script>

<template>
  <RouterView />
  <Toaster position="top-center" rich-colors close-button :toast-options="toastOptions" />
</template>
