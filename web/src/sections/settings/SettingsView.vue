<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query'
import {
  BellRing,
  ChevronRight,
  LayoutGrid,
  LogOut,
  MapPin,
  Mic,
  MonitorSmartphone,
  Network,
  Plug,
  Share2,
  ShieldCheck,
  UserRound,
} from '@lucide/vue'
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { toast } from 'vue-sonner'
import { probeNetwork } from '@/calls/network'
import { microphoneProblem } from '@/calls/session'
import { browserMedia } from '@/calls/store'
import { useCallsEnabled } from '@/calls/useCalls'
import PageHeader from '@/components/PageHeader.vue'
import Panel from '@/components/Panel.vue'
import ErrorState from '@/components/state/ErrorState.vue'
import RefreshFailed from '@/components/state/RefreshFailed.vue'
import { Avatar, AvatarFallback } from '@/components/ui/avatar'
import { Button } from '@/components/ui/button'
import { Progress } from '@/components/ui/progress'
import { Skeleton } from '@/components/ui/skeleton'
import { Switch } from '@/components/ui/switch'
import { api } from '@/core/client'
import { developerPreference, setDeveloperMode, useDeveloperMode } from '@/core/developer'
import { useLogout } from '@/app/useLogout'
import { useSession } from '@/stores/session'
import AccountForm from './AccountForm.vue'
import DeleteAccount from './DeleteAccount.vue'
import InstallationInfo from './InstallationInfo.vue'
import { Limits, resetLabel, usageOf } from './limits'

/** S01: personal settings only; installation settings live under «Управление» for admins. */
const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const session = useSession()
const zone = computed(() => session.me?.timezone ?? 'UTC')
const limits = useQuery({
  queryKey: ['me', 'limits'],
  queryFn: async ({ signal }) => Limits.parse(await api.get('/v1/me/limits', signal)),
})
// Usage counters matter where a limit is set; developer mode shows them always.
const developer = useDeveloperMode()
const showLimits = computed(
  () => developer.value || !!limits.data.value?.limits.some((limit) => limit.maximum !== null),
)
const accountOpen = ref(route.query.panel === 'account')
const deletionOpen = ref(false)
watch(accountOpen, (open) => {
  if (!open && route.query.panel) void router.replace({ query: {} })
})
const initials = computed(() =>
  (session.me?.name ?? '?')
    .split(/\s+/)
    .map((part) => part[0] ?? '')
    .join('')
    .slice(0, 2)
    .toUpperCase(),
)
const links = computed(() => [
  ...(session.can('personal.read')
    ? [
        {
          name: 'personal-profile',
          icon: UserRound,
          title: 'personal.bodyProfileTitle',
          text: 'personal.profileSummary',
        },
        {
          name: 'personal-sources',
          icon: Plug,
          title: 'sources.title',
          text: 'sources.settingsText',
        },
      ]
    : []),
  {
    name: 'settings-speech',
    icon: Mic,
    title: 'speechSettings.title',
    text: 'speechSettings.linkText',
  },
  {
    name: 'settings-sharing',
    icon: Share2,
    title: 'settings.sharing',
    text: 'settings.sharingText',
  },
  {
    name: 'settings-devices',
    icon: MonitorSmartphone,
    title: 'settings.devices',
    text: 'settings.devicesText',
  },
  {
    name: 'settings-notifications',
    icon: BellRing,
    title: 'settings.notifications',
    text: 'settings.notificationsText',
  },
  {
    name: 'settings-location',
    icon: MapPin,
    title: 'settings.location',
    text: 'settings.locationText',
  },
  {
    name: 'settings-capabilities',
    icon: LayoutGrid,
    title: 'settings.capabilities',
    text: 'settings.capabilitiesText',
  },
  ...(session.me?.system_admin
    ? [{ name: 'admin', icon: ShieldCheck, title: 'settings.admin', text: 'settings.adminText' }]
    : []),
])

// Developers check which addresses this device would offer in a call; nothing is sent.
const callsEnabled = useCallsEnabled()
const probing = ref(false)
const probe = ref<string | null>(null)
async function checkNetwork() {
  if (probing.value) return
  probing.value = true
  try {
    const found = await probeNetwork(browserMedia)
    const list = (values: string[]) => values.join(', ') || '—'
    probe.value = t('calls.dev.probeResult', {
      kinds: list(found.kinds),
      types: list(found.types),
      protocols: list(found.protocols),
    })
  } catch (error) {
    toast.error(t(`calls.${microphoneProblem(error)}`))
  } finally {
    probing.value = false
  }
}

// Signing out asks first when a write on this device is still unsettled.
const { askLogout } = useLogout()
</script>

<template>
  <PageHeader :title="t('settings.title')" />
  <div class="mx-auto max-w-3xl space-y-6 px-4 pb-8 md:px-8">
    <button
      type="button"
      class="bg-card hover:bg-accent/40 flex w-full items-center gap-4 rounded-3xl border p-5 text-left"
      data-testid="account-card"
      @click="accountOpen = true"
    >
      <Avatar class="size-12">
        <AvatarFallback>{{ initials }}</AvatarFallback>
      </Avatar>
      <span class="min-w-0 flex-1">
        <span class="block truncate text-lg font-semibold">{{ session.me?.name }}</span>
        <span class="text-muted-foreground block truncate text-sm">@{{ session.me?.login }}</span>
        <span class="text-muted-foreground block text-sm">{{ t('settings.accountText') }}</span>
      </span>
      <ChevronRight class="text-muted-foreground size-5" />
    </button>

    <nav class="bg-card divide-y rounded-3xl border" :aria-label="t('settings.more')">
      <RouterLink
        v-for="link in links"
        :key="link.name"
        :to="{ name: link.name }"
        class="hover:bg-accent/60 flex items-center gap-3 px-4 py-3.5"
      >
        <component :is="link.icon" class="text-muted-foreground size-5" />
        <span class="min-w-0 flex-1">
          <span class="block font-medium">{{ t(link.title) }}</span>
          <span class="text-muted-foreground block text-sm">{{ t(link.text) }}</span>
        </span>
        <ChevronRight class="text-muted-foreground size-4" />
      </RouterLink>
    </nav>

    <section v-if="showLimits" id="limits" class="space-y-2" aria-labelledby="limits-title">
      <h2 id="limits-title" class="font-semibold">{{ t('settings.limits') }}</h2>
      <div class="bg-card space-y-4 rounded-3xl border p-5" data-testid="limits">
        <RefreshFailed :query="limits" />
        <template v-if="limits.isPending.value">
          <Skeleton v-for="index in 3" :key="index" class="h-8" />
        </template>
        <ErrorState
          v-else-if="limits.isLoadingError.value"
          :error="limits.error.value"
          @retry="limits.refetch()"
        />
        <template v-else-if="limits.data.value">
          <div v-for="limit in limits.data.value.limits" :key="limit.kind" class="space-y-1.5">
            <div class="flex flex-wrap items-baseline justify-between gap-3 text-sm">
              <span class="min-w-0 font-medium">{{ t(`settings.limit.${limit.kind}`) }}</span>
              <span
                :class="[
                  'shrink-0 tabular-nums',
                  usageOf(limit).exhausted ? 'text-warning font-medium' : 'text-muted-foreground',
                ]"
              >
                {{
                  usageOf(limit).maximum === null
                    ? t('settings.usedUnlimited', { used: usageOf(limit).used })
                    : t(limit.kind === 'dictation_ms' ? 'settings.usedMinutes' : 'settings.used', {
                        used: usageOf(limit).used,
                        maximum: usageOf(limit).maximum,
                      })
                }}<template v-if="usageOf(limit).exhausted"> · {{ t('settings.usedUp') }}</template>
              </span>
            </div>
            <Progress
              v-if="usageOf(limit).share !== null"
              :model-value="usageOf(limit).share"
              :aria-label="t(`settings.limit.${limit.kind}`)"
            />
          </div>
          <p class="text-muted-foreground border-t pt-3 text-sm">
            {{ t('settings.reset', { when: resetLabel(zone) }) }}
          </p>
        </template>
      </div>
    </section>

    <section
      v-if="session.me?.system_admin"
      class="space-y-2"
      aria-labelledby="developer-title"
      data-testid="developer"
    >
      <h2 id="developer-title" class="font-semibold">{{ t('settings.developer') }}</h2>
      <div class="bg-card flex items-center gap-4 rounded-3xl border p-5">
        <div class="min-w-0 flex-1">
          <p id="developer-label" class="font-medium">{{ t('settings.developerShow') }}</p>
          <p class="text-muted-foreground text-sm">{{ t('settings.developerText') }}</p>
        </div>
        <Switch
          :model-value="developerPreference"
          aria-labelledby="developer-label"
          @update:model-value="(value) => setDeveloperMode(value === true)"
        />
      </div>
      <div
        v-if="developer && callsEnabled"
        class="bg-card space-y-3 rounded-3xl border p-5"
        data-testid="call-probe"
      >
        <p class="text-muted-foreground text-sm">{{ t('calls.dev.probeText') }}</p>
        <Button variant="outline" :disabled="probing" @click="checkNetwork">
          <Network />{{ t('calls.dev.probe') }}
        </Button>
        <p v-if="probe" class="text-sm tabular-nums" role="status">{{ probe }}</p>
      </div>
    </section>

    <InstallationInfo />
    <div class="space-y-2">
      <Button variant="outline" class="text-destructive w-full" @click="askLogout">
        <LogOut />{{ t('settings.signOut') }}
      </Button>
      <p class="text-muted-foreground text-center text-sm">{{ t('settings.signOutHint') }}</p>
      <Button variant="ghost" class="text-destructive w-full" @click="deletionOpen = true">{{
        t('accountDeletion.title')
      }}</Button>
    </div>
  </div>

  <Panel v-model:open="accountOpen" :title="t('settings.account')">
    <AccountForm @saved="accountOpen = false" />
  </Panel>
  <Panel v-model:open="deletionOpen" :title="t('accountDeletion.title')"><DeleteAccount /></Panel>
</template>
