<script setup lang="ts">
import { CheckCheck } from '@lucide/vue'
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { toast } from 'vue-sonner'
import BrandMark from '@/components/BrandMark.vue'
import HeaderAction from '@/components/HeaderAction.vue'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/state/EmptyState.vue'
import ErrorState from '@/components/state/ErrorState.vue'
import LoadingState from '@/components/state/LoadingState.vue'
import RefreshFailed from '@/components/state/RefreshFailed.vue'
import { errorText } from '@/core/errors'
import NotificationCard from './NotificationCard.vue'
import { useNotifications } from './useNotifications'

const { t } = useI18n()
const { list, items, unread, read } = useNotifications()
const busy = ref(false)
// Open matters first; solved problems wait folded at the end.
const open = computed(() => items.value.filter((item) => item.state === 'active'))
const solved = computed(() => items.value.filter((item) => item.state !== 'active'))

async function readAll() {
  busy.value = true
  try {
    await read()
  } catch (error) {
    toast.error(errorText(error, t))
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <PageHeader :title="t('notifications.title')" bare>
    <template #actions>
      <HeaderAction
        v-if="unread"
        :icon="CheckCheck"
        :label="t('notifications.readAll')"
        :disabled="busy"
        @click="readAll"
      />
    </template>
  </PageHeader>
  <div class="mx-auto max-w-3xl space-y-3 px-4 md:px-8">
    <RefreshFailed :query="list" />
    <LoadingState v-if="list.isPending.value" />
    <ErrorState
      v-else-if="list.isLoadingError.value"
      :error="list.error.value"
      @retry="list.refetch()"
    />
    <EmptyState
      v-else-if="!items.length"
      brand
      :title="t('notifications.emptyTitle')"
      :description="t('notifications.emptyText')"
    />
    <template v-else>
      <NotificationCard
        v-for="notification in open"
        :key="notification.id"
        :notification="notification"
      />
      <p
        v-if="!open.length"
        class="text-muted-foreground flex items-center gap-3 py-2 text-sm"
        data-testid="notifications-clear"
      >
        <BrandMark mood="ok" class="size-8 shrink-0" />{{ t('notifications.allClear') }}
      </p>
      <details v-if="solved.length" class="space-y-2" data-testid="notifications-solved">
        <summary class="text-muted-foreground cursor-pointer py-2 text-sm font-medium">
          {{ t('notifications.resolvedGroup') }} · {{ solved.length }}
        </summary>
        <div class="space-y-2">
          <NotificationCard
            v-for="notification in solved"
            :key="notification.id"
            :notification="notification"
          />
        </div>
      </details>
    </template>
  </div>
</template>
