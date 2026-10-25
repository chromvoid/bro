<script setup lang="ts">
import {
  Bot,
  ChevronRight,
  Database,
  Globe,
  KeyRound,
  LayoutGrid,
  Mic,
  UsersRound,
} from '@lucide/vue'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import PageHeader from '@/components/PageHeader.vue'
import ErrorState from '@/components/state/ErrorState.vue'
import LoadingState from '@/components/state/LoadingState.vue'
import RefreshFailed from '@/components/state/RefreshFailed.vue'
import { dayIn, momentLabel } from '@/core/dates'
import { useDeveloperMode } from '@/core/developer'
import { useSession } from '@/stores/session'
import { useAudit, useUsers } from './useAdmin'

/**
 * M01: the installation's control area, apart from everyday screens and open to system
 * administrators only. It never shows anyone's messages or diary.
 */
const { t, te } = useI18n()
// Message keys cannot contain dots: «tasks.read» is looked up as «tasks_read».
const key = (code: string) => code.replaceAll('.', '_')
const session = useSession()
const audit = useAudit()
const users = useUsers()
const zone = computed(() => session.me?.timezone ?? 'UTC')
const today = computed(() => dayIn(zone.value))
const nameOf = (id: string | null | undefined) =>
  users.data.value?.users.find((user) => user.id === id)?.name ?? t('admin.someone')
const developer = useDeveloperMode()
// Every code the server writes has words (contracts/admin-audit.json); one from a newer server
// reads «Другое действие», its code shown only in developer mode.
const action = (code: string) =>
  te(`admin.audit.${key(code)}`) ? t(`admin.audit.${key(code)}`) : t('admin.audit.other')
const unknown = (code: string) => developer.value && !te(`admin.audit.${key(code)}`)
const links = [
  {
    name: 'admin-speech',
    icon: Mic,
    title: 'speechSettings.adminTitle',
    text: 'speechSettings.adminText',
  },
  { name: 'admin-chat', icon: Bot, title: 'chatSettings.title', text: 'chatSettings.linkText' },
  {
    name: 'admin-research-sources',
    icon: Globe,
    title: 'researchSources.title',
    text: 'researchSources.linkText',
  },
  {
    name: 'admin-food-catalog',
    icon: Database,
    title: 'foodCatalog.title',
    text: 'foodCatalog.linkText',
  },
  { name: 'admin-users', icon: UsersRound, title: 'admin.users', text: 'admin.usersText' },
  {
    name: 'admin-moderation',
    icon: UsersRound,
    title: 'moderation.title',
    text: 'moderation.queueText',
  },
  { name: 'accounts', icon: KeyRound, title: 'admin.accounts', text: 'admin.accountsText' },
  { name: 'workers', icon: Bot, title: 'admin.workers', text: 'admin.workersText' },
  {
    name: 'settings-capabilities',
    icon: LayoutGrid,
    title: 'capabilities.title',
    text: 'admin.capabilitiesText',
  },
]
</script>

<template>
  <PageHeader :title="t('admin.title')" :description="t('admin.subtitle')" />
  <div class="mx-auto max-w-3xl space-y-6 px-4 pb-8 md:px-8">
    <nav class="bg-card divide-y rounded-3xl border" data-testid="admin-home">
      <RouterLink
        v-for="link in links"
        :key="link.name"
        :to="{ name: link.name }"
        class="hover:bg-accent/60 flex items-center gap-3 px-4 py-3.5"
      >
        <component :is="link.icon" class="text-muted-foreground size-5 shrink-0" />
        <span class="min-w-0 flex-1">
          <span class="block font-medium">{{ t(link.title) }}</span>
          <span class="text-muted-foreground block text-sm">{{ t(link.text) }}</span>
        </span>
        <ChevronRight class="text-muted-foreground size-4" />
      </RouterLink>
    </nav>

    <section class="space-y-2" aria-labelledby="audit-title">
      <h2 id="audit-title" class="font-semibold">{{ t('admin.auditTitle') }}</h2>
      <RefreshFailed :query="audit" />
      <LoadingState v-if="audit.isPending.value" :rows="3" />
      <ErrorState
        v-else-if="audit.isLoadingError.value"
        :error="audit.error.value"
        @retry="audit.refetch()"
      />
      <ul v-else class="bg-card divide-y rounded-3xl border text-sm" data-testid="audit">
        <li
          v-for="event in audit.data.value?.events.slice(0, 20)"
          :key="event.id"
          class="px-4 py-2.5"
        >
          <span class="font-medium">{{ action(event.action) }}</span>
          <code v-if="unknown(event.action)" class="text-muted-foreground ms-1 text-xs">{{
            event.action
          }}</code>
          <span class="text-muted-foreground">
            · {{ nameOf(event.actor_user_id) }} ·
            {{ momentLabel(event.created_at, zone, today) }}</span
          >
        </li>
      </ul>
    </section>
  </div>
</template>
