<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { z } from '@/core/zod'
import AuthLayout from '@/sections/auth/AuthLayout.vue'
const { t } = useI18n()
const status = ref('checking')
const error = ref(false)
const controller = new AbortController()
let timer: ReturnType<typeof setTimeout> | undefined
const Receipt = z.object({
  deletion_id: z.string(),
  receipt_token: z.string(),
  status_url: z.string().regex(/^\/v1\/account-deletions\/[A-Za-z0-9-]+$/),
})
const Recovery = z.object({ key: z.string(), space_successors: z.record(z.string(), z.string()) })
async function readReceipt() {
  const saved =
    history.state?.deletionReceipt ?? sessionStorage.getItem('assistant.account-deletion')
  if (saved) return Receipt.parse(JSON.parse(saved))
  const recovery = Recovery.parse(
    JSON.parse(sessionStorage.getItem('assistant.account-deletion-pending') ?? 'null'),
  )
  // Empty passwords cannot admit new deletions. This only recovers an existing receipt
  // matching the original cookie, key and succession choices after a lost response.
  const response = await fetch('/v1/me', {
    method: 'DELETE',
    credentials: 'same-origin',
    cache: 'no-store',
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': recovery.key },
    body: JSON.stringify({ password: '', space_successors: recovery.space_successors }),
    signal: controller.signal,
  })
  if (!response.ok) throw new Error('deletion_not_confirmed')
  const receipt = Receipt.parse(await response.json())
  sessionStorage.setItem('assistant.account-deletion', JSON.stringify(receipt))
  sessionStorage.removeItem('assistant.account-deletion-pending')
  return receipt
}
async function refresh() {
  try {
    const receipt = await readReceipt()
    const response = await fetch(receipt.status_url, {
      credentials: 'omit',
      cache: 'no-store',
      headers: { Authorization: `Bearer ${receipt.receipt_token}` },
      signal: controller.signal,
    })
    if (!response.ok) throw new Error('status_unavailable')
    const value = z
      .object({ status: z.enum(['pending', 'running', 'completed']) })
      .parse(await response.json())
    status.value = value.status
    error.value = false
    if (value.status === 'completed') sessionStorage.removeItem('assistant.account-deletion')
  } catch {
    if (!controller.signal.aborted) error.value = true
  }
  if (!controller.signal.aborted && status.value !== 'completed')
    timer = setTimeout(() => void refresh(), 5_000)
}
onMounted(refresh)
onBeforeUnmount(() => {
  controller.abort()
  clearTimeout(timer)
})
</script>
<template>
  <AuthLayout>
    <h1 class="text-xl font-semibold">{{ t('accountDeletion.title') }}</h1>
    <p role="status">
      {{
        t(
          status === 'completed'
            ? 'accountDeletion.completed'
            : status === 'checking'
              ? 'accountDeletion.checking'
              : 'accountDeletion.pending',
        )
      }}
    </p>
    <p v-if="error" class="text-muted-foreground text-sm">{{ t('accountDeletion.retry') }}</p>
    <RouterLink
      v-if="status === 'completed'"
      :to="{ name: 'login' }"
      class="text-primary underline"
      >{{ t('auth.title') }}</RouterLink
    >
  </AuthLayout>
</template>
