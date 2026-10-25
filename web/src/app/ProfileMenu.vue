<script setup lang="ts">
import { LogOut } from '@lucide/vue'
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { Avatar, AvatarFallback } from '@/components/ui/avatar'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { applyTheme, theme, type ThemePreference } from '@/core/theme'
import ProfileSheet from './profile/ProfileSheet.vue'
import { THEMES, useProfileMenu } from './profile/useProfileMenu'

/**
 * The profile: on a phone the avatar opens a bottom sheet with the sections beyond the tab bar;
 * at the bottom of the desktop sidebar, which lists the sections itself, a small menu.
 */
defineProps<{ wide?: boolean }>()

const { t } = useI18n()
const { session, circle, initials, askLogout } = useProfileMenu()
const sheet = ref(false)
</script>

<template>
  <DropdownMenu v-if="wide">
    <DropdownMenuTrigger as-child>
      <Button
        variant="ghost"
        class="h-auto w-full justify-start gap-3 px-2 py-2 text-left"
        data-testid="profile-menu"
      >
        <Avatar class="size-9">
          <AvatarFallback>{{ initials }}</AvatarFallback>
        </Avatar>
        <span class="min-w-0">
          <span class="block truncate font-medium">{{ session.me?.name }}</span>
          <span v-if="session.me?.login" class="text-muted-foreground block truncate text-xs"
            >@{{ session.me.login }}</span
          >
        </span>
      </Button>
    </DropdownMenuTrigger>
    <DropdownMenuContent align="end" class="w-64">
      <DropdownMenuLabel class="font-normal">
        <span class="block font-medium">{{ session.me?.name }}</span>
        <span v-if="session.me?.login" class="text-muted-foreground block text-xs"
          >@{{ session.me.login }}</span
        >
      </DropdownMenuLabel>
      <DropdownMenuSeparator />
      <DropdownMenuLabel class="text-muted-foreground text-xs">{{
        t('theme.title')
      }}</DropdownMenuLabel>
      <DropdownMenuRadioGroup
        :model-value="theme"
        @update:model-value="(value) => applyTheme(value as ThemePreference)"
      >
        <DropdownMenuRadioItem v-for="option in THEMES" :key="option.value" :value="option.value">
          <component :is="option.icon" />
          {{ t(option.label) }}
        </DropdownMenuRadioItem>
      </DropdownMenuRadioGroup>
      <DropdownMenuSeparator />
      <DropdownMenuItem variant="destructive" data-testid="logout" @select="askLogout">
        <LogOut />
        {{ t('profile.logout') }}
      </DropdownMenuItem>
    </DropdownMenuContent>
  </DropdownMenu>
  <template v-else>
    <Button
      variant="ghost"
      size="icon"
      class="relative"
      aria-haspopup="dialog"
      :aria-label="
        circle ? `${t('profile.open')}. ${t('circle.waiting')}: ${circle}` : t('profile.open')
      "
      data-testid="profile-menu"
      @click="sheet = true"
    >
      <Avatar class="size-9">
        <AvatarFallback>{{ initials }}</AvatarFallback>
      </Avatar>
      <!-- Circle lives in the profile on a phone: a dot says something waits there. -->
      <span
        v-if="circle"
        class="bg-primary ring-background absolute top-1 right-1 size-2.5 rounded-full ring-2"
        data-testid="circle-dot"
        aria-hidden="true"
      />
    </Button>
    <ProfileSheet v-model:open="sheet" />
  </template>
</template>
