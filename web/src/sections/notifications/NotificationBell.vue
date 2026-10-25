<script setup lang="ts">
import { Bell } from '@lucide/vue'
import { useI18n } from 'vue-i18n'
import { Button } from '@/components/ui/button'
import messages from '@/locales/notifications-ru.json'
import { useNotifications } from './useNotifications'

/** The way to what the assistant said on its own, with the count of unread ones. */
const { t } = useI18n({
  useScope: 'local',
  messages: { ru: messages },
  missingWarn: false,
  fallbackWarn: false,
})
const { unread } = useNotifications()
</script>

<template>
  <Button as-child variant="ghost" size="icon" class="relative shrink-0">
    <RouterLink
      :to="{ name: 'notifications' }"
      :aria-label="
        unread ? t('notifications.bellUnread', { count: unread }, unread) : t('notifications.title')
      "
      data-testid="notifications-bell"
    >
      <Bell class="size-5" />
      <span
        v-if="unread"
        class="bg-destructive text-destructive-foreground absolute -top-0.5 -right-0.5 grid h-5 min-w-5 place-items-center rounded-full px-1 text-xs font-semibold tabular-nums"
        aria-hidden="true"
        data-testid="notifications-unread"
        >{{ unread > 99 ? t('notifications.badgeOverflow') : unread }}</span
      >
    </RouterLink>
  </Button>
</template>
