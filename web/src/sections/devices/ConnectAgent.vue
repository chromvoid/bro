<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query'
import { computed, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { toast } from 'vue-sonner'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { api } from '@/core/client'
import { errorText } from '@/core/errors'
import { AgentOptions, ConnectedAgent, agentSetup } from './api'

/**
 * A development agent (Claude Code, Codex) becomes a device of this person: it reads what they
 * read and writes only the sections chosen here (docs/agent-access.md). The token is shown once.
 */
const open = defineModel<boolean>('open', { required: true })
const emit = defineEmits<{ connected: [] }>()

const { t, te } = useI18n()
const options = useQuery({
  queryKey: ['devices', 'agent-options'],
  queryFn: async ({ signal }) => AgentOptions.parse(await api.get('/v1/agents/options', signal)),
  enabled: open,
})
const form = reactive({
  name: '',
  writes: [] as string[],
  diagnostics: false,
  busy: false,
  error: '',
})
const token = ref<string | null>(null)
const setup = computed(() => (token.value ? agentSetup(location.origin, token.value) : null))
const label = (section: string) =>
  te(`agents.sections.${section}`) ? t(`agents.sections.${section}`) : section

function reset() {
  Object.assign(form, { name: '', writes: [], diagnostics: false, busy: false, error: '' })
  token.value = null
}

function toggle(section: string, checked: boolean | 'indeterminate') {
  form.writes =
    checked === true ? [...form.writes, section] : form.writes.filter((s) => s !== section)
}

async function create() {
  if (form.busy || !form.name.trim()) return
  form.busy = true
  form.error = ''
  try {
    const { data } = await api.post('/v1/agents', {
      name: form.name.trim(),
      writes: form.writes,
      diagnostics: form.diagnostics,
    })
    token.value = ConnectedAgent.parse(data).token
    emit('connected')
  } catch (error) {
    form.error = errorText(error, t)
  } finally {
    form.busy = false
  }
}

async function copy(value: string) {
  try {
    await navigator.clipboard.writeText(value)
    toast.success(t('agents.copied'))
  } catch {
    toast.error(t('agents.copyFailed'))
  }
}

function close(value: boolean) {
  open.value = value
  if (!value) reset()
}
</script>

<template>
  <Dialog :open="open" @update:open="close">
    <DialogContent class="max-h-[90dvh] overflow-y-auto">
      <DialogHeader>
        <DialogTitle>{{ setup ? t('agents.tokenTitle') : t('agents.title') }}</DialogTitle>
        <DialogDescription>{{
          setup ? t('agents.tokenText') : t('agents.text')
        }}</DialogDescription>
      </DialogHeader>

      <div v-if="setup" class="space-y-4" data-testid="agent-setup">
        <section v-for="client in ['claude', 'codex'] as const" :key="client" class="space-y-1.5">
          <div class="flex items-center justify-between gap-2">
            <h3 class="text-sm font-medium">{{ t(`agents.${client}`) }}</h3>
            <Button size="sm" variant="ghost" @click="copy(setup[client])">
              {{ t('agents.copy') }}
            </Button>
          </div>
          <pre
            class="bg-muted overflow-x-auto rounded-xl p-3 text-xs break-all whitespace-pre-wrap select-all"
            :data-testid="`agent-${client}`"
            >{{ setup[client] }}</pre>
        </section>
      </div>

      <form v-else id="agent-form" class="space-y-4" @submit.prevent="create">
        <div class="space-y-1.5">
          <Label for="agent-name">{{ t('agents.name') }}</Label>
          <Input
            id="agent-name"
            v-model="form.name"
            maxlength="60"
            :placeholder="t('agents.namePlaceholder')"
          />
        </div>
        <fieldset v-if="options.data.value?.writes.length" class="space-y-2">
          <legend class="mb-1 text-sm font-medium">{{ t('agents.writes') }}</legend>
          <div class="grid grid-cols-2 gap-2">
            <Label
              v-for="section in options.data.value.writes"
              :key="section"
              class="flex items-center gap-2 font-normal"
            >
              <Checkbox
                :model-value="form.writes.includes(section)"
                @update:model-value="(checked) => toggle(section, checked)"
              />{{ label(section) }}
            </Label>
          </div>
          <p class="text-muted-foreground text-sm">{{ t('agents.writesHint') }}</p>
        </fieldset>
        <div v-if="options.data.value?.diagnostics" class="space-y-1">
          <Label class="flex items-center gap-2 font-normal">
            <Checkbox v-model="form.diagnostics" />{{ t('agents.diagnostics') }}
          </Label>
          <p class="text-muted-foreground text-sm">{{ t('agents.diagnosticsHint') }}</p>
        </div>
        <p v-if="form.error" class="text-destructive text-sm" role="alert">{{ form.error }}</p>
      </form>

      <DialogFooter>
        <Button v-if="setup" @click="close(false)">{{ t('agents.done') }}</Button>
        <template v-else>
          <Button variant="outline" @click="close(false)">{{ t('common.cancel') }}</Button>
          <Button type="submit" form="agent-form" :disabled="form.busy || !form.name.trim()">
            {{ t('agents.create') }}
          </Button>
        </template>
      </DialogFooter>
    </DialogContent>
  </Dialog>
</template>
