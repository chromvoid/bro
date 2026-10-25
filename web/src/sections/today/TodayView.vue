<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import PageHeader from '@/components/PageHeader.vue'
import { fullDayLabel } from '@/core/dates'
import { useCalendarDay } from '@/core/useCalendarDay'
import TodayStepsCard from '@/sections/activity/TodayStepsCard.vue'
import { useSession } from '@/stores/session'
import AttentionCard from './AttentionCard.vue'
import FoodCard from './FoodCard.vue'
import NotificationsCard from './NotificationsCard.vue'
import PersonalCard from './PersonalCard.vue'
import QuickActions from './QuickActions.vue'
import TasksCard from './TasksCard.vue'
import TimersCard from './TimersCard.vue'

// Each card loads on its own: one failing source never hides the others.
const { t } = useI18n()
const session = useSession()
const zone = computed(() => session.me?.timezone ?? 'UTC')
const day = useCalendarDay(zone)
</script>

<template>
  <PageHeader :title="t('today.title')" :description="fullDayLabel(day, day)" width="5xl" />
  <!-- One column on a phone; on a wide screen what needs attention spans both columns and the
       cards sit in two columns that keep their own heights. -->
  <div
    class="mx-auto grid max-w-5xl grid-cols-1 items-start gap-4 px-4 pb-8 md:px-8 lg:grid-cols-2"
  >
    <!-- Bro and the microphone are in the tab bar and the sidebar; Today has no card for them. -->
    <div class="flex flex-col gap-4 lg:col-span-2">
      <QuickActions />
      <AttentionCard />
      <NotificationsCard />
    </div>
    <!-- On a phone the columns dissolve into one flow and steps come last; on a wide screen they
         sit under food, so the two columns stay even. -->
    <div class="contents lg:block lg:space-y-4">
      <FoodCard v-if="session.can('nutrition.read')" :day="day" />
      <TodayStepsCard
        v-if="session.can('personal.read')"
        class="max-lg:order-last"
        :today="day"
        :timezone="zone"
      />
      <TimersCard v-if="session.can('timers.read')" />
    </div>
    <div class="contents lg:block lg:space-y-4">
      <TasksCard v-if="session.can('tasks.read')" />
      <PersonalCard v-if="session.can('personal.read')" :day="day" />
    </div>
  </div>
</template>
