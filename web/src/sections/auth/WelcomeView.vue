<script setup lang="ts">
import { ListTodo, MessageCircle, Utensils } from '@lucide/vue'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import PageHeader from '@/components/PageHeader.vue'
import { Button } from '@/components/ui/button'
import { useSession } from '@/stores/session'

const { t } = useI18n()
const session = useSession()
const starts = computed(() =>
  [
    {
      route: 'food',
      icon: Utensils,
      title: 'welcome.food',
      text: 'welcome.foodText',
      scope: 'nutrition.write',
    },
    { route: 'assistant', icon: MessageCircle, title: 'welcome.ask', text: 'welcome.askText' },
    {
      route: 'tasks',
      icon: ListTodo,
      title: 'welcome.task',
      text: 'welcome.taskText',
      scope: 'tasks.write',
    },
  ].filter((start) => !start.scope || session.can(start.scope)),
)
</script>

<template>
  <PageHeader
    :title="t('welcome.title', { name: session.me?.name ?? '' })"
    :description="t('welcome.text')"
    width="2xl"
  />
  <div class="mx-auto grid grid-cols-1 max-w-2xl gap-3 px-4 md:px-8">
    <RouterLink
      v-for="start in starts"
      :key="start.route"
      :to="{ name: start.route }"
      class="bg-card hover:bg-accent/60 focus-visible:ring-ring/50 flex items-center gap-4 rounded-2xl border p-4 transition-colors outline-none focus-visible:ring-3"
    >
      <span
        class="bg-accent text-accent-foreground grid size-12 shrink-0 place-items-center rounded-xl"
      >
        <component :is="start.icon" class="size-6" />
      </span>
      <span>
        <span class="block font-medium">{{ t(start.title) }}</span>
        <span class="text-muted-foreground block text-sm">{{ t(start.text) }}</span>
      </span>
    </RouterLink>
    <Button as-child variant="ghost" class="justify-self-start">
      <RouterLink :to="{ name: 'today' }">{{ t('welcome.later') }}</RouterLink>
    </Button>
  </div>
</template>
