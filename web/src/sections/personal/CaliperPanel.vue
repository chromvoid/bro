<script setup lang="ts">
import { computed, nextTick, reactive, ref, useId, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { toast } from 'vue-sonner'
import Panel from '@/components/Panel.vue'
import { Button } from '@/components/ui/button'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { NativeSelect } from '@/components/ui/native-select'
import { Textarea } from '@/components/ui/textarea'
import { zonedInput, zonedInstant } from '@/core/dates'
import { errorText } from '@/core/errors'
import { ApiError } from '@/core/http'
import { parseAmount } from '@/core/numbers'
import messages from '@/locales/personal-forms-ru.json'
import type { Profile } from './api'
import { FOLD_SITES, type FoldSite } from './caliper'
import CaliperMethod from './CaliperMethod.vue'
import ProfilePanel from './ProfilePanel.vue'
import { usePersonal } from './usePersonal'
import { ageOn, type ProfileField } from './profile'

const props = defineProps<{ profile: Profile; day: string; today: string }>()
const open = defineModel<boolean>('open', { required: true })
const { t } = useI18n({
  useScope: 'local',
  messages: { ru: messages },
  missingWarn: false,
  fallbackWarn: false,
})
const personal = usePersonal()
const zone = computed(() => props.profile.timezone)
const optionalOpen = ref(false)
const overridesOpen = ref(false)
const genderOverridden = ref(false)
const profileOpen = ref(false)
const profileFocus = ref<ProfileField>()
const element = ref<HTMLFormElement | null>(null)
const formId = `caliper-form-${useId()}`
const fieldId = (field: string) => `${formId}-${field}`
type InvalidField = FoldSite | 'age' | 'measured'
const errors = reactive<Partial<Record<InvalidField, string>>>({})
const form = reactive({
  folds: Object.fromEntries(FOLD_SITES.map((site) => [site, ''])) as Record<FoldSite, string>,
  measured: '',
  gender: '',
  age: '',
  note: '',
  busy: false,
  error: '',
})
watch(
  open,
  (visible) => {
    if (!visible) {
      profileOpen.value = false
      return
    }
    optionalOpen.value = false
    overridesOpen.value = false
    genderOverridden.value = false
    const now = zonedInput(new Date(), zone.value)
    Object.assign(form, {
      folds: Object.fromEntries(FOLD_SITES.map((site) => [site, ''])),
      measured: props.day === props.today ? now : `${props.day}T${now.slice(11)}`,
      gender: props.profile.gender ?? '',
      age: '',
      note: '',
      error: '',
    })
    clearErrors()
  },
  { immediate: true },
)

function editProfile(field: ProfileField) {
  profileFocus.value = field
  profileOpen.value = true
}
const requiredSites = computed<readonly FoldSite[]>(() =>
  form.gender === 'male'
    ? ['chest', 'abdomen', 'thigh']
    : form.gender === 'female'
      ? ['triceps', 'suprailiac', 'thigh']
      : [],
)
const profileAge = computed(() => ageOn(props.profile.birth_date, form.measured.slice(0, 10)))
const primarySites = computed(() => (requiredSites.value.length ? requiredSites.value : FOLD_SITES))
const optionalSites = computed(() =>
  FOLD_SITES.filter((site) => !primarySites.value.includes(site)),
)
const optionalFilled = computed(
  () => optionalSites.value.filter((site) => form.folds[site].trim()).length,
)
watch(
  () => form.gender,
  () => {
    // A filled field changing groups stays visible; changing protocols never clears it.
    if (optionalFilled.value) optionalOpen.value = true
  },
)
watch(
  () => props.profile.gender,
  (gender) => {
    if (!open.value) return
    // An untouched default follows profile updates; explicit measurement facts stay visible.
    if (!genderOverridden.value) form.gender = gender ?? ''
    else overridesOpen.value = true
  },
)
watch(zone, (current, previous) => {
  if (!open.value || current === previous) return
  const instant = zonedInstant(form.measured, previous)
  if (instant) form.measured = zonedInput(new Date(instant), current)
})

function clearErrors() {
  for (const field of [...FOLD_SITES, 'age', 'measured'] as const) delete errors[field]
}
function validate(): InvalidField | undefined {
  clearErrors()
  // Follow the visible form order, including the optional fields after the primary protocol.
  const sites = [...primarySites.value, ...optionalSites.value]
  const filled = FOLD_SITES.filter((site) => form.folds[site].trim())
  if (!filled.length) errors[sites[0]!] = t('personal.caliper.foldRequired')
  for (const site of filled) {
    const fold = parseAmount(form.folds[site])
    if (!fold || Number(fold) <= 0 || Number(fold) > 100)
      errors[site] = t('personal.caliper.foldRange', {
        site: t(`personal.caliper.sites.${site}`),
      })
  }
  if (form.age.trim() && (!/^\d+$/.test(form.age.trim()) || Number(form.age) > 120))
    errors.age = t('personal.caliper.ageRange')
  const instant = zonedInstant(form.measured, zone.value)
  if (!instant) errors.measured = t('personal.timeRequired')
  else if (Date.parse(instant) > Date.now() + 60_000) errors.measured = t('personal.futureTime')
  const order: InvalidField[] = [...sites, 'measured', 'age']
  return order.find((field) => errors[field])
}
async function focusInvalid(field: InvalidField) {
  if (field === 'age') overridesOpen.value = true
  else if (optionalSites.value.includes(field as FoldSite)) optionalOpen.value = true
  await nextTick()
  const target = element.value?.querySelector<HTMLElement>(`[data-testid="caliper-${field}"]`)
  target?.focus({ preventScroll: true })
  target?.scrollIntoView?.({ block: 'nearest' })
}

async function save() {
  if (form.busy) return
  form.error = ''
  const invalid = validate()
  if (invalid) {
    await focusInvalid(invalid)
    return
  }
  form.busy = true
  try {
    const folds = Object.fromEntries(
      FOLD_SITES.filter((site) => form.folds[site].trim()).map((site) => [
        site,
        parseAmount(form.folds[site]),
      ]),
    )
    await personal.operate('caliper.add', {
      folds_mm: folds,
      measured_at: zonedInstant(form.measured, zone.value),
      // These describe this measurement; the profile itself is never changed here.
      ...(genderOverridden.value ? { gender: form.gender || null } : {}),
      ...(form.age.trim() ? { age_years: Number(form.age) } : {}),
      ...(form.note.trim() ? { note: form.note.trim() } : {}),
    })
    toast.success(t('personal.caliper.saved'))
    open.value = false
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
  <Panel v-model:open="open" :title="t('personal.caliper.add')">
    <form :id="formId" ref="element" class="space-y-4" novalidate @submit.prevent="save">
      <!-- Native fieldsets otherwise keep the intrinsic width of date and text controls. -->
      <fieldset class="min-w-0 space-y-4" :disabled="form.busy">
        <p class="text-muted-foreground text-sm">{{ t('personal.caliper.formHint') }}</p>
        <p
          :id="fieldId('protocol')"
          data-testid="caliper-protocol"
          class="text-muted-foreground text-sm"
        >
          {{
            requiredSites.length
              ? t('personal.caliper.protocol', {
                  sites: requiredSites
                    .map((site) => t(`personal.caliper.sites.${site}`))
                    .join(', '),
                })
              : t('personal.caliper.protocolUnset')
          }}
        </p>
        <div class="space-y-3" data-testid="caliper-primary">
          <h3 v-if="requiredSites.length" class="text-sm font-medium">
            {{ t('personal.caliper.primaryFolds') }}
          </h3>
          <div class="grid gap-3 sm:grid-cols-2">
            <div v-for="site in primarySites" :key="site" class="space-y-2">
              <Label :for="fieldId(site)">
                {{ t(`personal.caliper.sites.${site}`) }}, {{ t('personal.caliper.mm') }}
              </Label>
              <Input
                :id="fieldId(site)"
                v-model="form.folds[site]"
                :data-testid="`caliper-${site}`"
                inputmode="decimal"
                autocomplete="off"
                :aria-invalid="errors[site] ? true : undefined"
                :aria-describedby="
                  [
                    requiredSites.includes(site) ? fieldId('protocol') : '',
                    errors[site] ? fieldId(`${site}-error`) : '',
                  ]
                    .filter(Boolean)
                    .join(' ') || undefined
                "
              />
              <p
                v-if="errors[site]"
                :id="fieldId(`${site}-error`)"
                class="text-destructive text-sm"
                role="alert"
              >
                {{ errors[site] }}
              </p>
            </div>
          </div>
        </div>
        <details
          v-if="optionalSites.length"
          :open="optionalOpen"
          class="rounded-2xl border p-3 text-sm"
          data-testid="caliper-optional"
          @toggle="optionalOpen = ($event.target as HTMLDetailsElement).open"
        >
          <summary class="text-primary min-h-11 cursor-pointer py-2 font-medium">
            {{ t('personal.caliper.optionalFolds') }}
            <span v-if="optionalFilled" class="text-muted-foreground block font-normal">
              {{ t('personal.caliper.optionalFilled', { count: optionalFilled }) }}
            </span>
          </summary>
          <p class="text-muted-foreground mt-3">{{ t('personal.caliper.optionalHint') }}</p>
          <div class="mt-3 grid gap-3 sm:grid-cols-2">
            <div v-for="site in optionalSites" :key="site" class="space-y-2">
              <Label :for="fieldId(site)">
                {{ t(`personal.caliper.sites.${site}`) }}, {{ t('personal.caliper.mm') }}
              </Label>
              <Input
                :id="fieldId(site)"
                v-model="form.folds[site]"
                :data-testid="`caliper-${site}`"
                inputmode="decimal"
                autocomplete="off"
                :aria-invalid="errors[site] ? true : undefined"
                :aria-describedby="errors[site] ? fieldId(`${site}-error`) : undefined"
              />
              <p
                v-if="errors[site]"
                :id="fieldId(`${site}-error`)"
                class="text-destructive text-sm"
                role="alert"
              >
                {{ errors[site] }}
              </p>
            </div>
          </div>
        </details>
        <div class="space-y-2">
          <Label :for="fieldId('measured')">{{ t('personal.measuredAt') }}</Label>
          <Input
            :id="fieldId('measured')"
            v-model="form.measured"
            data-testid="caliper-measured"
            type="datetime-local"
            :aria-invalid="errors.measured ? true : undefined"
            :aria-describedby="
              [fieldId('zone-hint'), errors.measured ? fieldId('measured-error') : '']
                .filter(Boolean)
                .join(' ')
            "
          />
          <p
            v-if="errors.measured"
            :id="fieldId('measured-error')"
            class="text-destructive text-sm"
            role="alert"
          >
            {{ errors.measured }}
          </p>
          <p :id="fieldId('zone-hint')" class="text-muted-foreground text-sm">
            {{ t('personal.zoneHint', { zone }) }}
          </p>
        </div>
        <section class="space-y-2 rounded-2xl border p-3" data-testid="caliper-profile-metadata">
          <h3 class="text-sm font-medium">{{ t('personal.caliper.profileMetadata') }}</h3>
          <p class="text-muted-foreground text-sm">
            {{
              profile.gender
                ? t('personal.caliper.profileGender', {
                    gender: t(
                      profile.gender === 'male' ? 'personal.genderMale' : 'personal.genderFemale',
                    ),
                  })
                : t('personal.caliper.profileGenderUnknown')
            }}
          </p>
          <p class="text-muted-foreground text-sm">
            {{
              profileAge === null
                ? t('personal.caliper.profileAgeUnknown')
                : t('personal.caliper.profileAge', { age: profileAge })
            }}
          </p>
          <Alert
            v-if="!profile.gender || profileAge === null"
            variant="warning"
            data-testid="caliper-profile-notice"
          >
            <AlertTitle>{{ t('personal.caliper.profileNotificationTitle') }}</AlertTitle>
            <AlertDescription class="space-y-2">
              <p>{{ t('personal.caliper.profileFormHint') }}</p>
              <div class="flex flex-wrap gap-x-4 gap-y-2">
                <Button
                  v-if="profileAge === null"
                  type="button"
                  variant="link"
                  class="h-auto min-h-11 whitespace-normal px-0 py-2 text-start"
                  @click="editProfile('birth')"
                  >{{ t('personal.caliper.profileBirthAction') }}</Button
                >
                <Button
                  v-if="!profile.gender"
                  type="button"
                  variant="link"
                  class="h-auto min-h-11 whitespace-normal px-0 py-2 text-start"
                  @click="editProfile('gender')"
                  >{{ t('personal.caliper.profileGenderAction') }}</Button
                >
              </div>
            </AlertDescription>
          </Alert>
        </section>
        <details
          :open="overridesOpen"
          class="space-y-3 rounded-2xl border p-3 text-sm"
          data-testid="caliper-metadata-overrides"
          @toggle="overridesOpen = ($event.target as HTMLDetailsElement).open"
        >
          <summary class="text-primary min-h-11 cursor-pointer py-2 font-medium">
            {{ t('personal.caliper.measurementOverrides') }}
          </summary>
          <p class="text-muted-foreground mt-3">{{ t('personal.caliper.overridesHint') }}</p>
          <div class="grid gap-3 sm:grid-cols-2">
            <div class="space-y-2">
              <Label :for="fieldId('gender')">{{ t('personal.caliper.gender') }}</Label>
              <NativeSelect
                :id="fieldId('gender')"
                v-model="form.gender"
                data-testid="caliper-gender"
                @change="genderOverridden = true"
              >
                <option value="">{{ t('personal.genderUnset') }}</option>
                <option value="male">{{ t('personal.genderMale') }}</option>
                <option value="female">{{ t('personal.genderFemale') }}</option>
              </NativeSelect>
            </div>
            <div class="space-y-2">
              <Label :for="fieldId('age')">{{ t('personal.caliper.age') }}</Label>
              <Input
                :id="fieldId('age')"
                v-model="form.age"
                data-testid="caliper-age"
                inputmode="numeric"
                :aria-invalid="errors.age ? true : undefined"
                :aria-describedby="errors.age ? fieldId('age-error') : undefined"
              />
              <p
                v-if="errors.age"
                :id="fieldId('age-error')"
                class="text-destructive text-sm"
                role="alert"
              >
                {{ errors.age }}
              </p>
            </div>
          </div>
        </details>
        <p class="text-muted-foreground text-sm">
          {{ t('personal.caliper.metadataHint') }}
          {{ t('personal.caliper.ageFromProfileHint') }}
        </p>
        <div class="space-y-2">
          <Label :for="fieldId('note')">{{ t('personal.caliper.note') }}</Label>
          <Textarea
            :id="fieldId('note')"
            v-model="form.note"
            data-testid="caliper-note"
            :maxlength="1000"
          />
        </div>
        <CaliperMethod
          :gender="form.gender === 'male' ? 'male' : form.gender === 'female' ? 'female' : null"
        />
      </fieldset>
      <div v-if="form.error" class="space-y-1" data-testid="caliper-server-error">
        <p class="text-destructive text-sm" role="alert">{{ form.error }}</p>
        <p class="text-muted-foreground text-sm">{{ t('personalForms.draftKept') }}</p>
      </div>
    </form>
    <ProfilePanel
      v-model:open="profileOpen"
      :profile="profile"
      :today="today"
      :focus-field="profileFocus"
    />
    <template #footer>
      <Button
        type="submit"
        :form="formId"
        size="lg"
        class="w-full whitespace-normal"
        :disabled="form.busy"
      >
        {{ form.busy ? t('personalForms.saving') : t('personal.caliper.save') }}
      </Button>
    </template>
  </Panel>
</template>
