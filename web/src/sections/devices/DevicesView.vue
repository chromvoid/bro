<script setup lang="ts">
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { Bot, ChevronRight, Globe, MonitorSmartphone, Plus, Smartphone } from '@lucide/vue'
import { computed, reactive, ref, shallowRef } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { toast } from 'vue-sonner'
import HeaderAction from '@/components/HeaderAction.vue'
import PageHeader from '@/components/PageHeader.vue'
import ErrorState from '@/components/state/ErrorState.vue'
import LoadingState from '@/components/state/LoadingState.vue'
import RefreshFailed from '@/components/state/RefreshFailed.vue'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { DropdownMenuItem } from '@/components/ui/dropdown-menu'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { api } from '@/core/client'
import { momentPhrase, dayIn } from '@/core/dates'
import { useDeveloperMode } from '@/core/developer'
import { errorText } from '@/core/errors'
import { useSession } from '@/stores/session'
import { Devices, pairingCode, type Device } from './api'
import ConnectAgent from './ConnectAgent.vue'

/**
 * Devices signed in to this account: browsers, app shells and screens. A screen is added by
 * the code it shows; a device is switched off here without touching the others.
 */
const { t, te } = useI18n()
const router = useRouter()
const session = useSession()
// Development agents are connected by an administrator who turned on developer mode.
const developer = useDeveloperMode()

/** Sign-ins are named by the server after the browser and platform («Safari · iPhone»);
 * older ones only have the generic name and show their kind. */
function title(device: Device): string {
  if (device.kind === 'agent') return device.name
  if (device.kind === 'browser' && device.name && device.name !== 'Web session') return device.name
  return t(`devices.kind.${device.kind}`)
}
const queries = useQueryClient()
const zone = computed(() => session.me?.timezone ?? 'UTC')
const devices = useQuery({
  queryKey: ['devices'],
  queryFn: async ({ signal }) => Devices.parse(await api.get('/v1/devices', signal)),
})
const refresh = () => queries.invalidateQueries({ queryKey: ['devices'] })
const icons = { display: MonitorSmartphone, android: Smartphone, ios: Smartphone, agent: Bot }
const connecting = ref(false)
const iconOf = (device: Device) => icons[device.kind as keyof typeof icons] ?? Globe

/** What a development agent may do besides reading like this person. */
function rights(device: Device): string {
  const writes = device.grants?.writes ?? []
  const parts = [
    writes.length
      ? t('agents.writesList', {
          list: writes
            .map((s) => (te(`agents.sections.${s}`) ? t(`agents.sections.${s}`) : s))
            .join(', '),
        })
      : t('agents.readOnly'),
  ]
  if (device.grants?.diagnostics) parts.push(t('agents.withDiagnostics'))
  return parts.join(' · ')
}

function seen(device: Device): string {
  const moment = device.last_seen_at ?? device.created_at
  return t(device.last_seen_at ? 'devices.seen' : 'devices.added', {
    when: momentPhrase(moment, zone.value, dayIn(zone.value)),
  })
}

// Pairing: the owner types the code from the screen; the screen receives its session itself.
const adding = ref(false)
const form = reactive({ code: '', name: '', busy: false, error: '' })
function startAdding() {
  Object.assign(form, { code: '', name: '', busy: false, error: '' })
  adding.value = true
}
async function approve() {
  const code = pairingCode(form.code)
  form.error = code ? '' : t('devices.codeInvalid')
  if (!code || form.busy) return
  form.busy = true
  try {
    const { data } = await api.post<{ device: { id: string; name: string } }>(
      '/v1/devices/pairings/approve',
      form.name.trim() ? { code, name: form.name.trim() } : { code },
    )
    adding.value = false
    toast.success(t('devices.paired', { name: data.device.name }))
    await refresh()
    await router.push({ name: 'settings-display', params: { device: data.device.id } })
  } catch (error) {
    form.error = errorText(error, t)
  } finally {
    form.busy = false
  }
}

// Switching off another browser or phone ends its session at once.
const revoking = shallowRef<Device | null>(null)
const revokeOpen = computed({
  get: () => revoking.value !== null,
  set: (value: boolean) => {
    if (!value) revoking.value = null
  },
})
const revokeBusy = ref(false)
async function revoke() {
  const device = revoking.value
  if (!device || revokeBusy.value) return
  revokeBusy.value = true
  try {
    await api.post(`/v1/devices/${encodeURIComponent(device.id)}/revoke`, {})
    toast.success(t('devices.revoked', { name: device.name }))
    revoking.value = null
  } catch (error) {
    toast.error(errorText(error, t))
  } finally {
    revokeBusy.value = false
    await refresh()
  }
}
</script>

<template>
  <PageHeader
    :title="t('devices.title')"
    :description="t('devices.text')"
    :back="{ name: 'settings' }"
  >
    <template #actions>
      <HeaderAction
        primary
        :icon="Plus"
        :label="t('devices.add')"
        data-testid="add-display"
        @click="startAdding"
      />
    </template>
    <template v-if="developer" #menu>
      <DropdownMenuItem data-testid="add-agent" @select="connecting = true">
        <Bot />{{ t('devices.connectAgent') }}
      </DropdownMenuItem>
    </template>
  </PageHeader>
  <div class="mx-auto max-w-3xl space-y-5 px-4 pb-8 md:px-8">
    <RefreshFailed :query="devices" />
    <LoadingState v-if="devices.isPending.value" :rows="3" />
    <ErrorState
      v-else-if="devices.isLoadingError.value"
      :error="devices.error.value"
      @retry="devices.refetch()"
    />
    <ul v-else-if="devices.data.value" class="bg-card divide-y rounded-3xl border">
      <li
        v-for="device in devices.data.value.devices"
        :key="device.id"
        class="flex items-center gap-3 px-4 py-3.5"
        :data-testid="`device-${device.kind}`"
      >
        <component :is="iconOf(device)" class="text-muted-foreground size-5 shrink-0" />
        <RouterLink
          v-if="device.kind === 'display'"
          :to="{ name: 'settings-display', params: { device: device.id } }"
          class="flex min-w-0 flex-1 items-center gap-3"
        >
          <span class="min-w-0 flex-1">
            <span class="block truncate font-medium">{{ device.name }}</span>
            <span class="text-muted-foreground block text-sm">{{ seen(device) }}</span>
          </span>
          <ChevronRight class="text-muted-foreground size-4" />
        </RouterLink>
        <template v-else>
          <span class="min-w-0 flex-1">
            <span class="block truncate font-medium">
              {{ title(device) }}
            </span>
            <span v-if="device.kind === 'agent'" class="text-muted-foreground block text-sm">
              {{ rights(device) }}
            </span>
            <span class="text-muted-foreground block text-sm">{{ seen(device) }}</span>
          </span>
          <Badge v-if="device.current" variant="secondary">{{ t('devices.current') }}</Badge>
          <Button
            v-else
            size="sm"
            variant="ghost"
            class="text-destructive"
            @click="revoking = device"
          >
            {{ t('devices.revoke') }}
          </Button>
        </template>
      </li>
    </ul>
  </div>

  <Dialog v-model:open="adding">
    <DialogContent>
      <DialogHeader>
        <DialogTitle>{{ t('devices.addTitle') }}</DialogTitle>
        <DialogDescription>{{ t('devices.addText') }}</DialogDescription>
      </DialogHeader>
      <form id="pair-form" class="space-y-4" @submit.prevent="approve">
        <div class="space-y-1.5">
          <Label for="pair-code">{{ t('devices.code') }}</Label>
          <Input
            id="pair-code"
            v-model="form.code"
            autocomplete="off"
            autocapitalize="characters"
            spellcheck="false"
            placeholder="4K7Q-2MXA"
            class="font-mono tracking-widest uppercase"
            :aria-invalid="form.error ? true : undefined"
            aria-describedby="pair-error"
          />
        </div>
        <div class="space-y-1.5">
          <Label for="pair-name">{{ t('devices.name') }}</Label>
          <Input id="pair-name" v-model="form.name" :placeholder="t('devices.namePlaceholder')" />
        </div>
        <p v-if="form.error" id="pair-error" class="text-destructive text-sm" role="alert">
          {{ form.error }}
        </p>
      </form>
      <DialogFooter>
        <Button variant="outline" @click="adding = false">{{ t('common.cancel') }}</Button>
        <Button type="submit" form="pair-form" :disabled="form.busy">
          {{ t('devices.confirm') }}
        </Button>
      </DialogFooter>
    </DialogContent>
  </Dialog>

  <ConnectAgent v-model:open="connecting" @connected="refresh" />

  <Dialog v-model:open="revokeOpen">
    <DialogContent>
      <DialogHeader>
        <DialogTitle>{{ t('devices.revokeTitle') }}</DialogTitle>
        <DialogDescription>{{ t('devices.revokeText') }}</DialogDescription>
      </DialogHeader>
      <DialogFooter>
        <Button variant="outline" @click="revoking = null">{{ t('common.cancel') }}</Button>
        <Button variant="destructive" :disabled="revokeBusy" @click="revoke">
          {{ t('devices.revoke') }}
        </Button>
      </DialogFooter>
    </DialogContent>
  </Dialog>
</template>
