<script setup lang="ts">
import { computed, nextTick, reactive, ref, shallowRef, useId, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { toast } from 'vue-sonner'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { NativeSelect } from '@/components/ui/native-select'
import { isIsoDay, isTimeZone } from '@/core/dates'
import { errorText } from '@/core/errors'
import { ApiError } from '@/core/http'
import { parseAmount } from '@/core/numbers'
import messages from '@/locales/personal-forms-ru.json'
import { timeZones } from '@/sections/auth/links'
import { useSession } from '@/stores/session'
import { Profile } from './api'
import { heightText } from './displayUnits'
import { ageOn, profileField, type ProfileField } from './profile'
import { usePersonal } from './usePersonal'

const props = withDefaults(
  defineProps<{
    profile: Profile
    today: string
    readOnly?: boolean
    focusField?: ProfileField
    formId?: string
    externalActions?: boolean
  }>(),
  { readOnly: false, externalActions: false },
)
const emit = defineEmits<{ saved: [] }>()
const { t } = useI18n({
  useScope: 'local',
  messages: { ru: messages },
  missingWarn: false,
  fallbackWarn: false,
})
const personal = usePersonal()
const session = useSession()
const zones = timeZones()
const baseline = shallowRef({ ...props.profile })
const element = ref<HTMLFormElement | null>(null)
const generatedId = `profile-${useId()}`
const formId = computed(() => props.formId ?? generatedId)
const fieldId = (field: string) => `${generatedId}-${field}`
type InvalidField = 'birth' | 'height' | 'zone'
const errors = reactive<Record<InvalidField, string>>({ birth: '', height: '', zone: '' })
const form = reactive({
  height: props.profile.height_cm ?? '',
  birth: props.profile.birth_date ?? '',
  timezone: props.profile.timezone,
  gender: (props.profile.gender ?? '') as string,
  busy: false,
  error: '',
})
const age = computed(() => ageOn(form.birth, props.today))
const busy = computed(() => form.busy)
const dirty = computed(
  () =>
    form.height !== (baseline.value.height_cm ?? '') ||
    form.birth !== (baseline.value.birth_date ?? '') ||
    form.timezone !== baseline.value.timezone ||
    form.gender !== (baseline.value.gender ?? ''),
)

function reset(profile: Profile) {
  baseline.value = { ...profile }
  Object.assign(form, {
    height: profile.height_cm ?? '',
    birth: profile.birth_date ?? '',
    timezone: profile.timezone,
    gender: profile.gender ?? '',
    error: '',
  })
  Object.assign(errors, { birth: '', height: '', zone: '' })
}

// An overview refresh may update a clean form, but never a draft or its captured revision.
watch([() => props.profile, () => form.busy], ([profile]) => {
  if (!form.busy && !dirty.value && profile.revision >= baseline.value.revision) reset(profile)
})

function resetCurrent() {
  if (form.busy || props.readOnly) return
  reset(props.profile.revision >= baseline.value.revision ? props.profile : baseline.value)
}
function focusControl(field: string): boolean {
  const target = !props.readOnly
    ? element.value?.querySelector<HTMLElement>(`[data-testid="profile-${field}"]`)
    : null
  target?.focus({ preventScroll: true })
  target?.scrollIntoView?.({ block: 'nearest' })
  return Boolean(target)
}
function focus(field: ProfileField): boolean {
  const safe = profileField(field)
  return safe ? focusControl(safe) : false
}
defineExpose({ focus, busy, dirty, reset: resetCurrent })
watch(
  [() => props.focusField, element],
  ([field]) => {
    const safe = profileField(field)
    if (safe) focus(safe)
  },
  { flush: 'post' },
)

function validate(): InvalidField | undefined {
  Object.assign(errors, { birth: '', height: '', zone: '' })
  if (form.birth && (!isIsoDay(form.birth) || form.birth > props.today))
    errors.birth = t('personal.birthInvalid')
  if (form.height.trim()) {
    const height = parseAmount(form.height)
    if (!height || Number(height) < 50 || Number(height) > 260)
      errors.height = t('personal.heightRange')
  }
  if (!isTimeZone(form.timezone)) errors.zone = t('personal.zoneInvalid')
  return (['birth', 'height', 'zone'] as const).find((field) => errors[field])
}

async function save() {
  if (props.readOnly || form.busy) return
  form.error = ''
  const invalid = validate()
  if (invalid) {
    await nextTick()
    focusControl(invalid)
    return
  }
  form.busy = true
  try {
    const changes: Record<string, unknown> = {}
    const before = baseline.value
    const height = form.height.trim() ? parseAmount(form.height) : null
    if (
      (height === null) !== (before.height_cm === null) ||
      (height !== null && Number(height) !== Number(before.height_cm))
    )
      changes.height_cm = height
    if ((form.birth || null) !== (before.birth_date ?? null))
      changes.birth_date = form.birth || null
    if (form.timezone !== before.timezone) changes.timezone = form.timezone
    // Only the explicit selection becomes a fact, including clearing it.
    if (form.gender !== (before.gender ?? '')) changes.gender = form.gender || null
    if (!Object.keys(changes).length) {
      emit('saved')
      return
    }
    const receipt = await personal.operate('profile.update', changes, {
      [before.user_id]: before.revision,
    })
    const saved = Profile.parse((receipt as { profile: unknown }).profile)
    reset(saved)
    // The receipt supplies the next revision before the background overview refresh finishes.
    if (saved.timezone !== session.me?.timezone) await session.load()
    toast.success(t('personal.profileSaved'))
    emit('saved')
  } catch (error) {
    form.error =
      error instanceof ApiError && error.status === 409
        ? t('personal.conflict')
        : errorText(error, t)
    personal.failed(error)
  } finally {
    form.busy = false
  }
}
</script>

<template>
  <form :id="formId" ref="element" class="space-y-4" novalidate @submit.prevent="save">
    <fieldset class="space-y-4" :disabled="readOnly || form.busy">
      <div class="grid gap-3 sm:grid-cols-2">
        <div class="space-y-2">
          <Label :for="fieldId('birth')">{{ t('personal.birthDate') }}</Label>
          <Input
            :id="fieldId('birth')"
            v-model="form.birth"
            data-testid="profile-birth"
            type="date"
            :max="today"
            :aria-invalid="errors.birth ? true : undefined"
            :aria-describedby="
              [fieldId('birth-hint'), errors.birth ? fieldId('birth-error') : '']
                .filter(Boolean)
                .join(' ')
            "
          />
          <p :id="fieldId('birth-hint')" class="text-muted-foreground text-sm">
            {{ t('personal.birthHint') }}
          </p>
          <p
            v-if="errors.birth"
            :id="fieldId('birth-error')"
            class="text-destructive text-sm"
            role="alert"
          >
            {{ errors.birth }}
          </p>
        </div>
        <div class="space-y-2">
          <Label :for="fieldId('age')">{{ t('personal.age') }}</Label>
          <output
            :id="fieldId('age')"
            data-testid="profile-age"
            :for="fieldId('birth')"
            class="bg-muted flex h-11 items-center rounded-xl px-3.5 tabular-nums"
            :aria-describedby="fieldId('age-hint')"
            >{{ age === null ? '—' : t('personal.ageYears', { count: age }, age) }}</output
          >
          <p :id="fieldId('age-hint')" class="text-muted-foreground text-sm">
            {{ t('personal.ageHint') }}
          </p>
        </div>
      </div>
      <div class="grid gap-3 sm:grid-cols-2">
        <div class="space-y-2">
          <Label :for="fieldId('gender')">{{ t('personal.gender') }}</Label>
          <NativeSelect :id="fieldId('gender')" v-model="form.gender" data-testid="profile-gender">
            <option value="">{{ t('personal.genderUnset') }}</option>
            <option value="male">{{ t('personal.genderMale') }}</option>
            <option value="female">{{ t('personal.genderFemale') }}</option>
          </NativeSelect>
          <p class="text-muted-foreground text-sm">{{ t('personal.genderHint') }}</p>
        </div>
        <div class="space-y-2">
          <Label :for="fieldId('height')">{{ t('personal.height') }}</Label>
          <p
            v-if="profile.height_cm !== null"
            class="text-muted-foreground text-sm"
            data-testid="profile-height-display"
          >
            {{ heightText(profile.height_cm, profile.display_units) }}
          </p>
          <p class="text-muted-foreground text-xs">{{ t('personalForms.heightInputHint') }}</p>
          <Input
            :id="fieldId('height')"
            v-model="form.height"
            data-testid="profile-height"
            inputmode="decimal"
            :aria-invalid="errors.height ? true : undefined"
            :aria-describedby="errors.height ? fieldId('height-error') : undefined"
          />
          <p
            v-if="errors.height"
            :id="fieldId('height-error')"
            class="text-destructive text-sm"
            role="alert"
          >
            {{ errors.height }}
          </p>
        </div>
      </div>
      <div class="space-y-2">
        <Label :for="fieldId('zone')">{{ t('personal.timezone') }}</Label>
        <Input
          :id="fieldId('zone')"
          v-model="form.timezone"
          data-testid="profile-zone"
          :list="fieldId('zones')"
          autocomplete="off"
          :aria-invalid="errors.zone ? true : undefined"
          :aria-describedby="errors.zone ? fieldId('zone-error') : undefined"
        />
        <datalist :id="fieldId('zones')">
          <option v-for="zone in zones" :key="zone" :value="zone" />
        </datalist>
        <p
          v-if="errors.zone"
          :id="fieldId('zone-error')"
          class="text-destructive text-sm"
          role="alert"
        >
          {{ errors.zone }}
        </p>
        <p class="text-muted-foreground text-sm">{{ t('personal.timezoneHint') }}</p>
      </div>
    </fieldset>
    <p v-if="readOnly" class="text-muted-foreground text-sm">{{ t('access.readOnly') }}</p>
    <div v-if="form.error" class="space-y-1" data-testid="profile-server-error">
      <p class="text-destructive text-sm" role="alert">{{ form.error }}</p>
      <p class="text-muted-foreground text-sm">{{ t('personalForms.draftKept') }}</p>
    </div>
    <div v-if="!readOnly && !externalActions" class="flex flex-wrap gap-2">
      <Button type="submit" size="lg" class="flex-1" :disabled="form.busy">
        {{ form.busy ? t('personalForms.saving') : t('personal.saveProfile') }}
      </Button>
      <Button
        v-if="dirty"
        type="button"
        variant="outline"
        :disabled="form.busy"
        @click="resetCurrent"
        >{{ t('personal.resetProfile') }}</Button
      >
    </div>
    <RouterLink
      :to="{ name: 'food' }"
      class="text-primary block text-sm underline-offset-4 hover:underline"
    >
      {{ t('personal.goalElsewhere') }}
    </RouterLink>
  </form>
</template>
