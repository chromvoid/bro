<script setup lang="ts">
import { CircleAlert, Inbox, Mic, Monitor, Moon, Sun, Utensils } from '@lucide/vue'
import { ref } from 'vue'
import { toast } from 'vue-sonner'
import LineChart from '@/components/chart/LineChart.vue'
import EmptyState from '@/components/state/EmptyState.vue'
import ErrorState from '@/components/state/ErrorState.vue'
import LoadingState from '@/components/state/LoadingState.vue'
import NoAccess from '@/components/state/NoAccess.vue'
import RefreshFailed from '@/components/state/RefreshFailed.vue'
import UnknownOutcome from '@/components/state/UnknownOutcome.vue'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Checkbox } from '@/components/ui/checkbox'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from '@/components/ui/dialog'
import {
  Drawer,
  DrawerContent,
  DrawerDescription,
  DrawerFooter,
  DrawerHeader,
  DrawerTitle,
  DrawerTrigger,
} from '@/components/ui/drawer'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover'
import { Progress } from '@/components/ui/progress'
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { Separator } from '@/components/ui/separator'
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
  SheetTrigger,
} from '@/components/ui/sheet'
import { Switch } from '@/components/ui/switch'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { Textarea } from '@/components/ui/textarea'
import { ToggleGroup, ToggleGroupItem } from '@/components/ui/toggle-group'
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from '@/components/ui/tooltip'
import { applyTheme, theme, type ThemePreference } from '@/core/theme'
import { NetworkError } from '@/core/http'

// A development reference of every building block and state, in both themes.
const swatches = [
  ['bg-background', 'background'],
  ['bg-card', 'card'],
  ['bg-muted', 'muted'],
  ['bg-secondary', 'secondary'],
  ['bg-accent', 'accent'],
  ['bg-primary', 'primary'],
  ['bg-destructive', 'destructive'],
  ['bg-warning', 'warning'],
  ['bg-warning-fill', 'warning-fill'],
  ['bg-warning-surface', 'warning-surface'],
  ['bg-success', 'success'],
  ['bg-success-surface', 'success-surface'],
  ['bg-chart-1', 'chart-1'],
  ['bg-chart-2', 'chart-2'],
  ['bg-chart-3', 'chart-3'],
  ['bg-chart-4', 'chart-4'],
  ['bg-chart-5', 'chart-5'],
] as const
const variants = ['default', 'secondary', 'outline', 'ghost', 'destructive', 'link'] as const
const sizes = ['sm', 'default', 'lg'] as const
const themes: Array<[ThemePreference, typeof Sun, string]> = [
  ['system', Monitor, 'Как в системе'],
  ['light', Sun, 'Светлое'],
  ['dark', Moon, 'Тёмное'],
]
const portion = ref('100')
const meal = ref('breakfast')
const reminders = ref(true)
// A refresh that failed over data already on screen.
const failedRefresh = {
  isRefetchError: ref(true),
  isFetching: ref(false),
  refetch: async () => toast('Обновляем…'),
}
const weights = [81.4, 81.1, 80.9, 81.2, 80.6, 80.4, 80.1].map((y, index) => ({
  x: Date.UTC(2026, 8, 19 + index),
  y,
}))
const day = new Intl.DateTimeFormat('ru-RU', { day: 'numeric', month: 'short', timeZone: 'UTC' })
</script>

<template>
  <main class="mx-auto max-w-5xl space-y-12 px-4 py-8 md:px-8">
    <header class="flex flex-wrap items-end justify-between gap-4">
      <div>
        <p class="text-muted-foreground text-sm">Дизайн-система Bro</p>
        <h1 class="text-3xl font-semibold tracking-tight">Компоненты и состояния</h1>
      </div>
      <ToggleGroup
        type="single"
        variant="outline"
        :model-value="theme"
        aria-label="Оформление"
        @update:model-value="(value) => value && applyTheme(value as ThemePreference)"
      >
        <ToggleGroupItem
          v-for="[value, icon, label] in themes"
          :key="value"
          :value="value"
          :aria-label="label"
        >
          <component :is="icon" />
        </ToggleGroupItem>
      </ToggleGroup>
    </header>

    <section class="space-y-4" aria-labelledby="colors">
      <h2 id="colors" class="text-xl font-semibold">Цвета</h2>
      <div class="grid grid-cols-3 gap-3 sm:grid-cols-5">
        <div v-for="[swatch, name] in swatches" :key="name" class="space-y-1.5">
          <div :class="['h-14 rounded-xl border', swatch]" />
          <p class="text-muted-foreground text-xs">{{ name }}</p>
        </div>
      </div>
    </section>

    <section class="space-y-4" aria-labelledby="type">
      <h2 id="type" class="text-xl font-semibold">Типографика</h2>
      <div class="space-y-3">
        <p class="text-3xl font-semibold tracking-tight">Заголовок экрана · 30</p>
        <p class="text-xl font-semibold">Заголовок раздела · 20</p>
        <p class="text-base">Основной текст · 16. Записал омлет 100 г и салат 50 г.</p>
        <p class="text-muted-foreground text-sm">Вторичный текст · 14. Обновлено в 09:17.</p>
        <p class="text-4xl font-semibold tabular-nums">
          1 842 <span class="text-muted-foreground text-lg font-normal">/ 2 100 ккал</span>
        </p>
      </div>
    </section>

    <section class="space-y-4" aria-labelledby="buttons">
      <h2 id="buttons" class="text-xl font-semibold">Кнопки</h2>
      <div v-for="size in sizes" :key="size" class="flex flex-wrap items-center gap-2">
        <Button v-for="variant in variants" :key="variant" :variant="variant" :size="size">{{
          variant
        }}</Button>
      </div>
      <div class="flex flex-wrap items-center gap-2">
        <Button size="icon" aria-label="Диктовать"><Mic /></Button>
        <Button size="icon" variant="outline" aria-label="Питание"><Utensils /></Button>
        <Button disabled>Недоступно</Button>
      </div>
    </section>

    <section class="space-y-4" aria-labelledby="forms">
      <h2 id="forms" class="text-xl font-semibold">Поля ввода</h2>
      <div class="grid gap-6 md:grid-cols-2">
        <div class="space-y-2">
          <Label for="gallery-portion">Порция, г</Label>
          <Input id="gallery-portion" v-model="portion" inputmode="decimal" />
        </div>
        <div class="space-y-2">
          <Label for="gallery-invalid">Название</Label>
          <Input id="gallery-invalid" aria-invalid="true" model-value="" />
          <p class="text-destructive text-sm">Укажите название продукта.</p>
        </div>
        <div class="space-y-2">
          <Label for="gallery-note">Сообщение</Label>
          <Textarea id="gallery-note" placeholder="Написать Bro…" />
        </div>
        <div class="space-y-2">
          <Label>Приём пищи</Label>
          <Select v-model="meal">
            <SelectTrigger class="w-full"><SelectValue /></SelectTrigger>
            <SelectContent>
              <SelectItem value="breakfast">Завтрак</SelectItem>
              <SelectItem value="lunch">Обед</SelectItem>
              <SelectItem value="dinner">Ужин</SelectItem>
            </SelectContent>
          </Select>
        </div>
        <div class="flex items-center gap-3">
          <Checkbox id="gallery-complete" />
          <Label for="gallery-complete">День заполнен полностью</Label>
        </div>
        <div class="flex items-center gap-3">
          <Switch id="gallery-reminders" v-model="reminders" />
          <Label for="gallery-reminders">Напоминания</Label>
        </div>
        <RadioGroup default-value="summary" class="gap-3">
          <div class="flex items-center gap-3">
            <RadioGroupItem id="gallery-summary" value="summary" />
            <Label for="gallery-summary">Только сводка</Label>
          </div>
          <div class="flex items-center gap-3">
            <RadioGroupItem id="gallery-detail" value="detail" />
            <Label for="gallery-detail">Подробности</Label>
          </div>
        </RadioGroup>
        <Progress :model-value="72" aria-label="Калории дня" />
      </div>
    </section>

    <section class="space-y-4" aria-labelledby="content">
      <h2 id="content" class="text-xl font-semibold">Карточки, метки, сообщения</h2>
      <div class="grid gap-4 md:grid-cols-2">
        <Card>
          <CardHeader>
            <CardTitle>Омлет</CardTitle>
            <CardDescription>100 г · завтрак</CardDescription>
          </CardHeader>
          <CardContent class="flex flex-wrap gap-2">
            <Badge>записано</Badge>
            <Badge variant="secondary">оценка</Badge>
            <Badge variant="warning">проверьте</Badge>
            <Badge variant="success">готово</Badge>
            <Badge variant="destructive">ошибка</Badge>
            <Badge variant="outline">черновик</Badge>
          </CardContent>
        </Card>
        <div class="space-y-3">
          <Alert>
            <Inbox />
            <AlertTitle>Сохранён черновик</AlertTitle>
            <AlertDescription>Продукт не найден в каталоге.</AlertDescription>
          </Alert>
          <Alert variant="warning">
            <CircleAlert />
            <AlertTitle>Нужна проверка</AlertTitle>
            <AlertDescription>Калорийность рассчитана по оценке порции.</AlertDescription>
          </Alert>
          <Alert variant="destructive">
            <CircleAlert />
            <AlertTitle>Запрос не выполнен</AlertTitle>
            <AlertDescription>Текст сохранён, можно отправить снова.</AlertDescription>
          </Alert>
        </div>
      </div>
      <Tabs default-value="day">
        <TabsList>
          <TabsTrigger value="day">День</TabsTrigger>
          <TabsTrigger value="week">Неделя</TabsTrigger>
        </TabsList>
        <TabsContent value="day" class="text-muted-foreground pt-3">Записи за день.</TabsContent>
        <TabsContent value="week" class="text-muted-foreground pt-3">Итоги недели.</TabsContent>
      </Tabs>
    </section>

    <section class="space-y-4" aria-labelledby="chart">
      <h2 id="chart" class="text-xl font-semibold">График</h2>
      <Card>
        <CardContent>
          <LineChart
            :points="weights"
            label="Вес за неделю"
            :format-y="(value) => `${value.toFixed(1).replace('.', ',')} кг`"
            :format-x="(value) => day.format(value)"
          />
        </CardContent>
      </Card>
    </section>

    <section class="space-y-4" aria-labelledby="states">
      <h2 id="states" class="text-xl font-semibold">Состояния</h2>
      <div class="grid gap-4 md:grid-cols-2">
        <Card
          ><EmptyState
            :icon="Inbox"
            title="Пока нет записей"
            description="Добавьте еду голосом, фото или поиском."
        /></Card>
        <Card
          ><CardContent><LoadingState :rows="2" /></CardContent
        ></Card>
        <Card><ErrorState :error="new NetworkError(false)" /></Card>
        <RefreshFailed :query="failedRefresh" />
        <Card><NoAccess /></Card>
        <UnknownOutcome can-resend />
        <p class="text-muted-foreground bg-muted rounded-xl px-4 py-3 text-sm" role="status">
          Данные могли устареть: обновляем…
        </p>
      </div>
    </section>

    <section class="space-y-4" aria-labelledby="overlays">
      <h2 id="overlays" class="text-xl font-semibold">Всплывающие элементы</h2>
      <Separator />
      <div class="flex flex-wrap gap-2">
        <Dialog>
          <DialogTrigger as-child><Button variant="outline">Диалог</Button></DialogTrigger>
          <DialogContent>
            <DialogHeader>
              <DialogTitle>Удалить запись?</DialogTitle>
              <DialogDescription>Омлет 100 г исчезнет из дневника за сегодня.</DialogDescription>
            </DialogHeader>
            <DialogFooter>
              <Button variant="outline">Отмена</Button>
              <Button variant="destructive">Удалить</Button>
            </DialogFooter>
          </DialogContent>
        </Dialog>
        <Drawer>
          <DrawerTrigger as-child><Button variant="outline">Нижняя панель</Button></DrawerTrigger>
          <DrawerContent>
            <DrawerHeader>
              <DrawerTitle>Добавить еду</DrawerTitle>
              <DrawerDescription>Голосом, фото или поиском по каталогу.</DrawerDescription>
            </DrawerHeader>
            <DrawerFooter><Button>Продолжить</Button></DrawerFooter>
          </DrawerContent>
        </Drawer>
        <Sheet>
          <SheetTrigger as-child><Button variant="outline">Боковая панель</Button></SheetTrigger>
          <SheetContent>
            <SheetHeader>
              <SheetTitle>Детали разговора</SheetTitle>
              <SheetDescription>К какому результату относится ответ.</SheetDescription>
            </SheetHeader>
          </SheetContent>
        </Sheet>
        <DropdownMenu>
          <DropdownMenuTrigger as-child
            ><Button variant="outline">Меню</Button></DropdownMenuTrigger
          >
          <DropdownMenuContent>
            <DropdownMenuItem>Изменить</DropdownMenuItem>
            <DropdownMenuItem variant="destructive">Удалить</DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
        <Popover>
          <PopoverTrigger as-child><Button variant="outline">Подсказка</Button></PopoverTrigger>
          <PopoverContent>Оценка основана на типичной порции.</PopoverContent>
        </Popover>
        <TooltipProvider>
          <Tooltip>
            <TooltipTrigger as-child
              ><Button variant="outline">Всплывающая подсказка</Button></TooltipTrigger
            >
            <TooltipContent>Обновлено в 09:17</TooltipContent>
          </Tooltip>
        </TooltipProvider>
        <Button variant="outline" @click="toast.success('Записано: омлет 100 г')"
          >Уведомление</Button
        >
      </div>
    </section>
  </main>
</template>
