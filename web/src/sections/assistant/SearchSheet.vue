<script setup lang="ts">
import { Search } from '@lucide/vue'
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import DateField from '@/components/DateField.vue'
import ErrorState from '@/components/state/ErrorState.vue'
import LoadingState from '@/components/state/LoadingState.vue'
import RefreshFailed from '@/components/state/RefreshFailed.vue'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
} from '@/components/ui/sheet'
import { dayIn, dayLabel, timeIn, zoneNote } from '@/core/dates'
import { useSession } from '@/stores/session'
import { useConversationSearch } from './useConversationSearch'

const open = defineModel<boolean>('open', { required: true })
const emit = defineEmits<{ pick: [messageId: string] }>()
const { t } = useI18n()
const session = useSession()
const text = ref('')
const after = ref('')
const through = ref('')
const zone = computed(() => session.me?.timezone ?? 'UTC')
/** Said only when the account's zone is not this device's. */
const zoneName = computed(() => zoneNote(zone.value))
const { results, hits, ready, bounds } = useConversationSearch(text, after, through, zone, open)

function pick(id: string) {
  open.value = false
  emit('pick', id)
}
</script>

<template>
  <Sheet v-model:open="open">
    <SheetContent side="top" class="max-h-[85dvh] overflow-y-auto">
      <SheetHeader>
        <SheetTitle>{{ t('assistant.search') }}</SheetTitle>
        <SheetDescription>{{ t('assistant.searchHint') }}</SheetDescription>
      </SheetHeader>
      <div class="space-y-3 px-4 pb-6">
        <div class="relative">
          <Search class="text-muted-foreground absolute top-3 left-3.5 size-5" />
          <Input
            v-model="text"
            type="search"
            class="pl-11"
            maxlength="160"
            :placeholder="t('assistant.searchPlaceholder')"
            :aria-label="t('assistant.searchPlaceholder')"
          />
        </div>
        <div class="grid grid-cols-2 items-end gap-3">
          <div class="min-w-0 space-y-1.5">
            <Label for="conversation-search-after">{{ t('assistant.searchAfter') }}</Label>
            <DateField
              id="conversation-search-after"
              v-model="after"
              clearable
              :placeholder="t('assistant.searchAnyDate')"
              :max="through || undefined"
            />
          </div>
          <div class="min-w-0 space-y-1.5">
            <Label for="conversation-search-through">{{ t('assistant.searchThrough') }}</Label>
            <DateField
              id="conversation-search-through"
              v-model="through"
              clearable
              :placeholder="t('assistant.searchAnyDate')"
              :min="after || undefined"
            />
          </div>
        </div>
        <p v-if="zoneName" class="text-muted-foreground text-xs">
          {{ t('assistant.searchTimezone', { zone: zoneName }) }}
        </p>
        <p v-if="!bounds" class="text-destructive text-sm" role="alert">
          {{ t('assistant.searchDateError') }}
        </p>
        <RefreshFailed v-if="ready" :query="results" />
        <LoadingState v-if="ready && results.isFetching.value && !results.data.value" :rows="2" />
        <ErrorState
          v-else-if="ready && results.isLoadingError.value"
          :error="results.error.value"
          @retry="results.refetch()"
        />
        <p v-else-if="ready && results.data.value && !hits.length" class="text-muted-foreground">
          {{ t('assistant.searchEmpty') }}
        </p>
        <ul v-else-if="hits.length" class="divide-y rounded-xl border">
          <li v-for="hit in hits" :key="hit.id">
            <button
              type="button"
              class="hover:bg-accent w-full px-4 py-3 text-left"
              @click="pick(hit.id)"
            >
              <span class="text-muted-foreground block text-xs">
                {{ t(hit.author === 'assistant' ? 'assistant.searchAssistant' : 'assistant.you') }}
                · {{ dayLabel(dayIn(zone, new Date(hit.occurred_at)), dayIn(zone)) }},
                {{ timeIn(zone, hit.occurred_at) }}
              </span>
              <span class="line-clamp-2">{{ hit.text }}</span>
            </button>
          </li>
        </ul>
        <p
          v-if="ready && results.isFetchNextPageError.value"
          class="text-destructive text-sm"
          role="alert"
        >
          {{ t('assistant.searchMoreError') }}
        </p>
        <Button
          v-if="ready && results.hasNextPage.value"
          type="button"
          variant="outline"
          :disabled="results.isFetching.value"
          @click="results.fetchNextPage()"
        >
          {{ t(results.isFetchingNextPage.value ? 'state.loading' : 'assistant.searchMore') }}
        </Button>
      </div>
    </SheetContent>
  </Sheet>
</template>
