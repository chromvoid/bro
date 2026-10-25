<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { NativeSelect } from '@/components/ui/native-select'
import { api } from '@/core/client'
import { errorText } from '@/core/errors'
import { ApiError } from '@/core/http'
import { z } from '@/core/zod'
import { useSession } from '@/stores/session'

const { t } = useI18n()
const router = useRouter()
const session = useSession()
const options = ref<{
  last_admin?: boolean
  spaces: Array<{ id: string; name: string; successors: Array<{ id: string; name: string }> }>
} | null>(null)
const password = ref('')
const successors = reactive<Record<string, string>>({})
const busy = ref(false)
const error = ref('')
const uncertain = ref(false)
const key = crypto.randomUUID()
let requestBody: string | undefined
onMounted(async () => {
  try {
    options.value = await api.get('/v1/me/account-deletion-options')
  } catch (reason) {
    error.value = errorText(reason, t)
  }
})
async function remove() {
  if (busy.value || !options.value || !password.value) return
  busy.value = true
  error.value = ''
  try {
    requestBody ??= JSON.stringify({
      password: password.value,
      space_successors: Object.fromEntries(Object.entries(successors).filter(([, id]) => id)),
    })
    // Persist only the narrow recovery proof, never the password or a second deletion command.
    try {
      sessionStorage.setItem(
        'assistant.account-deletion-pending',
        JSON.stringify({
          key,
          space_successors: (
            JSON.parse(requestBody) as { space_successors: Record<string, string> }
          ).space_successors,
        }),
      )
    } catch {
      /* Recovery remains possible with this open form. */
    }
    const { data } = await api.send('DELETE', '/v1/me', requestBody, {
      key,
      expectedUser: session.me?.id,
    })
    const receipt = z
      .object({
        deletion_id: z.string(),
        status: z.string(),
        receipt_token: z.string(),
        status_url: z.string(),
      })
      .parse(data)
    password.value = ''
    requestBody = undefined
    try {
      sessionStorage.removeItem('assistant.account-deletion-pending')
      sessionStorage.setItem('assistant.account-deletion', JSON.stringify(receipt))
    } catch {
      /* The public receipt can still be displayed in this page. */
    }
    await session.logout()
    await router.replace({
      name: 'account-deletion',
      state: { deletionReceipt: JSON.stringify(receipt) },
    })
  } catch (reason) {
    uncertain.value = !(reason instanceof ApiError && reason.status < 500)
    if (!uncertain.value) {
      requestBody = undefined
      try {
        sessionStorage.removeItem('assistant.account-deletion-pending')
      } catch {
        /* Storage is optional. */
      }
    }
    error.value = errorText(reason, t)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <form class="space-y-4" @submit.prevent="remove">
    <p>{{ t('accountDeletion.description') }}</p>
    <p v-if="options?.last_admin" class="text-warning text-sm">
      {{ t('accountDeletion.lastAdmin') }}
    </p>
    <div v-for="space in options?.spaces" :key="space.id" class="space-y-2">
      <Label :for="`successor-${space.id}`">{{
        t('accountDeletion.successor', { name: space.name })
      }}</Label>
      <NativeSelect
        :id="`successor-${space.id}`"
        v-model="successors[space.id]"
        :disabled="busy || uncertain"
      >
        <option value="">{{ t('accountDeletion.archive') }}</option>
        <option v-for="candidate in space.successors" :key="candidate.id" :value="candidate.id">
          {{ candidate.name }}
        </option>
      </NativeSelect>
    </div>
    <div class="space-y-2">
      <Label for="deletion-password">{{ t('accountDeletion.password') }}</Label>
      <Input
        id="deletion-password"
        v-model="password"
        type="password"
        autocomplete="current-password"
        :disabled="busy || uncertain"
      />
    </div>
    <p v-if="error" class="text-destructive text-sm" role="alert">{{ error }}</p>
    <RouterLink
      v-if="uncertain"
      :to="{ name: 'account-deletion' }"
      class="text-primary block underline"
      >{{ t('accountDeletion.check') }}</RouterLink
    >
    <Button
      type="submit"
      variant="destructive"
      class="w-full"
      :disabled="busy || !options || !password"
      >{{ t('accountDeletion.confirm') }}</Button
    >
  </form>
</template>
