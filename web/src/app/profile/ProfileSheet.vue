<script setup lang="ts">
import { Blocks, LogOut } from '@lucide/vue'
import { nextTick, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import Panel from '@/components/Panel.vue'
import SegmentedControl from '@/components/SegmentedControl.vue'
import { Button } from '@/components/ui/button'
import { applyTheme, theme, type ThemePreference } from '@/core/theme'
import { cn } from '@/lib/utils'
import { THEMES, useProfileMenu } from './useProfileMenu'

/**
 * The profile on a phone, from the avatar: the sections beyond the tab bar as tiles, the theme and
 * sign-out, in a bottom sheet that stays clear of the gesture bar and scrolls when it must.
 */
const open = defineModel<boolean>('open', { required: true })
const { t } = useI18n()
const route = useRoute()
const { session, plugins, circle, links, askLogout } = useProfileMenu()

// A tile leads to its screen: the sheet goes away with the old one.
watch(
  () => route.fullPath,
  () => (open.value = false),
)
/** A section stays marked on its nested screens. */
const current = (name: string) =>
  route.matched.some((record) => record.name === name || record.meta.section === name)
const plugin = (id: string) => route.name === 'plugin' && route.params.installation === id

/** One modal at a time: the sheet closes before the sign-out question. */
async function after(action: () => unknown) {
  open.value = false
  await nextTick()
  void action()
}
const tile =
  'hover:bg-accent focus-visible:ring-ring/50 flex min-h-20 flex-col items-center justify-center gap-1.5 rounded-2xl border px-2 py-3 text-center text-sm font-medium outline-none focus-visible:ring-3'
</script>

<template>
  <Panel
    v-model:open="open"
    :title="session.me?.name ?? t('profile.title')"
    :description="session.me?.login ? `@${session.me.login}` : undefined"
    :autofocus="false"
    data-testid="profile-sheet"
  >
    <nav :aria-label="t('profile.sections')">
      <ul class="grid grid-cols-3 gap-2">
        <li v-for="item in links" :key="item.name">
          <RouterLink
            :to="{ name: item.name }"
            :aria-current="current(item.name) ? 'page' : undefined"
            :class="cn(tile, current(item.name) && 'bg-accent border-primary/40')"
          >
            <span class="relative">
              <component :is="item.icon" class="size-6" aria-hidden="true" />
              <span
                v-if="item.name === 'circle' && circle"
                class="bg-primary text-primary-foreground absolute -top-2 -right-4 min-w-5 rounded-full px-1.5 text-xs font-semibold tabular-nums"
                ><span class="sr-only">{{ t('circle.waiting') }}: </span>{{ circle }}</span
              >
            </span>
            <span class="line-clamp-2">{{ t(item.label) }}</span>
          </RouterLink>
        </li>
        <li v-for="entry in plugins.entries" :key="entry.installation.installation_id">
          <RouterLink
            :to="{ name: 'plugin', params: { installation: entry.installation.installation_id } }"
            :aria-current="plugin(entry.installation.installation_id) ? 'page' : undefined"
            :class="cn(tile, plugin(entry.installation.installation_id) && 'bg-accent')"
          >
            <component :is="entry.ui?.icon ?? Blocks" class="size-6" aria-hidden="true" />
            <span class="line-clamp-2">{{ entry.installation.title }}</span>
          </RouterLink>
        </li>
      </ul>
    </nav>
    <section class="space-y-2" aria-labelledby="profile-theme">
      <h3 id="profile-theme" class="text-muted-foreground text-sm font-medium">
        {{ t('theme.title') }}
      </h3>
      <SegmentedControl
        stretch
        :label="t('theme.title')"
        :model-value="theme"
        :segments="
          THEMES.map((option) => ({
            value: option.value,
            label: t(option.label),
            icon: option.icon,
          }))
        "
        @update:model-value="(value) => applyTheme(value as ThemePreference)"
      />
    </section>
    <Button
      variant="outline"
      class="text-destructive w-full"
      data-testid="logout"
      @click="after(askLogout)"
    >
      <LogOut aria-hidden="true" />{{ t('profile.logout') }}
    </Button>
  </Panel>
</template>
