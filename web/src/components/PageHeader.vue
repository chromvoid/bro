<script setup lang="ts">
import { ChevronLeft, MoreHorizontal } from '@lucide/vue'
import { useI18n } from 'vue-i18n'
import type { RouteLocationRaw } from 'vue-router'
import ProfileMenu from '@/app/ProfileMenu.vue'
import BrandMark from '@/components/BrandMark.vue'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { cn } from '@/lib/utils'
import NotificationBell from '@/sections/notifications/NotificationBell.vue'

/**
 * One row on a phone: the way back or the app's mark, the title, the screen's own actions (icons,
 * HeaderAction) and, on top-level screens, this phone's devices (the ring's charge or background
 * recording), notifications and the profile. Rarer actions go to `#menu`, behind «…». At large
 * text sizes the actions wrap below the title.
 */
withDefaults(
  defineProps<{
    title: string
    description?: string
    /** A parent screen: a back link instead of the app-wide buttons. */
    back?: RouteLocationRaw
    /** Without the notification bell: the notifications screen itself. */
    bare?: boolean
    /** The screen's content column, so the title lines up with what is below it. */
    width?: '2xl' | '3xl' | '5xl' | '6xl' | '7xl'
    /** A chat screen: a slim row with the app's mark on a phone, aligned with its column. */
    compact?: boolean
  }>(),
  { width: '3xl' },
)

// Literal class names for Tailwind; the content of each screen uses the same column.
const WIDTHS = {
  '2xl': 'max-w-2xl',
  '3xl': 'max-w-3xl',
  '5xl': 'max-w-5xl',
  '6xl': 'max-w-6xl',
  '7xl': 'max-w-7xl',
} as const

const { t } = useI18n()
</script>

<template>
  <header :class="compact ? 'px-4 py-1.5 md:px-6 md:pt-8 md:pb-2' : 'pt-3 pb-2 md:pt-8 md:pb-3'">
    <div :class="cn('mx-auto', !compact && 'px-4 md:px-8', WIDTHS[width])">
      <div class="flex flex-wrap items-center gap-y-1 md:gap-x-1">
        <Button v-if="back" as-child variant="ghost" size="icon" class="-ml-2 shrink-0">
          <RouterLink :to="back" :aria-label="t('common.back')">
            <ChevronLeft class="size-5" />
          </RouterLink>
        </Button>
        <BrandMark v-else-if="compact" class="me-1.5 size-7 shrink-0 md:hidden" />
        <!-- A rem basis: at large text sizes the title keeps its line and the actions go below.
             3 rem lets a short title («Дела») share a 360 px row with six 44 px buttons; it then
             grows into the room left over. -->
        <h1
          :title="title"
          :class="
            cn(
              'min-w-0 flex-[1_1_3rem] leading-tight font-semibold tracking-tight md:text-3xl',
              compact ? 'text-lg' : 'text-2xl',
              back ? 'line-clamp-2 break-words' : 'truncate',
            )
          "
        >
          {{ title }}
        </h1>
        <!-- Below the title the actions keep one row. Where even that is too narrow (four at 200%
             text on a phone) they shrink toward a 44 px target, as the tab bar's microphone does,
             instead of running off the screen or taking a row more from the page. On a phone the
             44 px targets touch: their icons keep 24 px apart all the same. -->
        <div
          class="ms-auto flex min-w-0 max-w-full items-center justify-end md:gap-2 [&_[data-slot=button]]:min-w-[44px] [&_[data-slot=button]]:shrink"
          data-testid="header-actions"
        >
          <slot name="actions" />
          <DropdownMenu v-if="$slots.menu">
            <DropdownMenuTrigger as-child>
              <Button variant="ghost" size="icon" :aria-label="t('common.more')">
                <MoreHorizontal class="size-5" />
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end" class="min-w-56">
              <slot name="menu" />
            </DropdownMenuContent>
          </DropdownMenu>
          <template v-if="!back">
            <NotificationBell v-if="!bare" />
            <div class="flex min-w-0 md:hidden"><ProfileMenu /></div>
          </template>
        </div>
      </div>
      <p
        v-if="description"
        :class="cn('text-muted-foreground mt-0.5 text-sm md:text-base', back && 'ps-9')"
      >
        {{ description }}
      </p>
    </div>
  </header>
</template>
