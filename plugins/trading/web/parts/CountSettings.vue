<script setup lang="ts">
import { ChevronDown } from '@lucide/vue'
import { computed } from 'vue'
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
import { LOT_METHODS, type LotMethod } from '../contracts'
import { methodLabel, t } from '../labels'
import type { PnlModel } from '../model'

/**
 * How positions are counted, in two buttons: the unit (the strategy coin is starred and can be made the
 * default) and which purchases a sale closes. Choosing changes the numbers here and on coin pages.
 */
const props = defineProps<{ model: PnlModel; units: string[] }>()
const unit = computed(() => props.model.shownUnit())
const strategy = computed(() => {
  const value = props.model.positions.value
  return value.kind === 'ready' ? (value.value.strategy_coin ?? null) : null
})
const method = computed(() => props.model.shownMethod())
</script>

<template>
  <div class="flex flex-wrap items-center gap-2" data-testid="count-settings">
    <DropdownMenu>
      <DropdownMenuTrigger as-child>
        <Button
          variant="outline"
          size="sm"
          class="min-w-24 justify-between"
          data-testid="unit-menu"
        >
          {{ t('count_in_short', unit) }}<ChevronDown class="size-4" />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="start" class="w-64">
        <DropdownMenuLabel class="text-muted-foreground text-xs font-normal">
          {{ t('count_in_hint') }}
        </DropdownMenuLabel>
        <DropdownMenuRadioGroup
          :model-value="unit"
          @update:model-value="(value) => model.count(String(value))"
        >
          <DropdownMenuRadioItem v-for="item in units" :key="item" :value="item">
            {{ item
            }}<span v-if="item === (strategy ?? 'USD')" class="text-muted-foreground ms-auto"
              >★</span
            >
          </DropdownMenuRadioItem>
        </DropdownMenuRadioGroup>
        <template v-if="model.canSetStrategy.value && unit !== (strategy ?? 'USD')">
          <DropdownMenuSeparator />
          <DropdownMenuItem
            :disabled="model.saving.value"
            @select="model.setStrategy(unit === 'USD' ? null : unit)"
          >
            {{ t('strategy_make', unit) }}
          </DropdownMenuItem>
        </template>
      </DropdownMenuContent>
    </DropdownMenu>
    <DropdownMenu>
      <DropdownMenuTrigger as-child>
        <!-- One width for both labels: the open menu follows its trigger's size. -->
        <Button variant="outline" size="sm" class="w-52 justify-between" data-testid="method-menu">
          {{ method === 'fifo' ? t('method_button_fifo') : t('method_button_lifo')
          }}<ChevronDown class="size-4" />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="start" class="w-80">
        <DropdownMenuLabel class="text-muted-foreground text-xs font-normal">
          {{ t('lot_method') }}
        </DropdownMenuLabel>
        <DropdownMenuRadioGroup
          :model-value="method"
          @update:model-value="(value) => model.useMethod(value as LotMethod)"
        >
          <DropdownMenuRadioItem v-for="item in LOT_METHODS" :key="item" :value="item">
            <span class="space-y-0.5">
              <span class="block">{{ methodLabel(item) }}</span>
              <span class="text-muted-foreground block text-xs">
                {{ t(`method_hint_${item}` as const) }}
              </span>
            </span>
          </DropdownMenuRadioItem>
        </DropdownMenuRadioGroup>
      </DropdownMenuContent>
    </DropdownMenu>
  </div>
</template>
