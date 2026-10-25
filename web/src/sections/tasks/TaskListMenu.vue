<script setup lang="ts">
import { Ellipsis, Settings2 } from '@lucide/vue'
import { useI18n } from 'vue-i18n'
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

/**
 * How the tasks are ordered and, for whoever manages the list, its settings. The board, the
 * places and «+ Проект» are on the screen itself.
 */
defineProps<{ sorts: string[]; canManage: boolean }>()
const order = defineModel<string>('order', { required: true })
const emit = defineEmits<{ settings: [] }>()
const { t } = useI18n()
</script>

<template>
  <DropdownMenu>
    <DropdownMenuTrigger as-child>
      <Button variant="ghost" size="icon" :aria-label="t('tasks.listActions')"
        ><Ellipsis class="size-5"
      /></Button>
    </DropdownMenuTrigger>
    <DropdownMenuContent align="end" class="min-w-56">
      <DropdownMenuLabel>{{ t('tasks.sort') }}</DropdownMenuLabel>
      <DropdownMenuRadioGroup v-model="order">
        <DropdownMenuRadioItem v-for="name in sorts" :key="name" :value="name">{{
          t(`tasks.sorts.${name}`)
        }}</DropdownMenuRadioItem>
      </DropdownMenuRadioGroup>
      <template v-if="canManage">
        <DropdownMenuSeparator />
        <DropdownMenuItem @select="emit('settings')"
          ><Settings2 />{{ t('tasks.projectSettings') }}</DropdownMenuItem
        >
      </template>
    </DropdownMenuContent>
  </DropdownMenu>
</template>
