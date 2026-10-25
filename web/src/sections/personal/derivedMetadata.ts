import { createI18n } from 'vue-i18n'
import messages from '@/locales/derived-scores-ru.json'
import commonMessages from '@/locales/ru.json'

const locale = messages.derivedMissingInputs
const inputKeys = new Map<string, string>([
  ...Object.keys(locale.inputs).map((code): [string, string] => [
    code,
    `derivedMissingInputs.inputs.${code}`,
  ]),
  ['steps', 'activity.steps'],
  ['weight_kg', 'personal.metric.weight'],
  ['age', 'personal.age'],
])

/** Exact machine identifiers from the saved Soul input contract, not inferred meanings. */
const conditionKeys = new Map<string, keyof typeof locale.conditions>([
  ['actual_measurements', 'actual_measurements'],
  ['formula_evidence', 'formula_evidence'],
  ['night_hr:four_samples', 'night_hr_four_samples'],
  ['night_hrv:three_samples', 'night_hrv_three_samples'],
  ['sleep_debt:three_weekly_rows', 'sleep_debt_three_weekly_rows'],
  ['sleep_debt:positive_integer_source_durations', 'sleep_debt_positive_integer_source_durations'],
  ['sleep_goal_minutes:integer_source_goal', 'sleep_goal_minutes_integer_source_goal'],
  ['instant_evaluation', 'instant_evaluation'],
  ['conditions_not_supplied_by_soul_ring_path', 'conditions_not_supplied_by_soul_ring_path'],
])

type DerivedMetadataTranslate = (key: string, values?: Record<string, string>) => string

/** Pure callers use the same locale that Vue and the native resource generator consume. */
const metadataI18n = createI18n({
  legacy: false,
  locale: 'ru',
  fallbackLocale: 'ru',
  messages: { ru: { ...commonMessages, ...messages } },
})
const localeText: DerivedMetadataTranslate = (key, values) =>
  values ? metadataI18n.global.t(key, values) : metadataI18n.global.t(key)

export function derivedInputLabel(code: string) {
  const key = inputKeys.get(code)
  return localeText(key ?? 'derivedMissingInputs.inputUnknown')
}

/** Only known identifiers and source-emitted validation suffixes receive a meaning. */
export function derivedMissingMessages(
  codes: string[],
  translate: DerivedMetadataTranslate = localeText,
) {
  return codes.map((code) => {
    let label: string
    const inputKey = inputKeys.get(code)
    if (inputKey) label = translate(inputKey)
    else {
      const condition = conditionKeys.get(code)
      if (condition) label = translate(`derivedMissingInputs.conditions.${condition}`)
      else {
        const separator = code.lastIndexOf(':')
        const input = code.slice(0, separator)
        const qualifier = code.slice(separator + 1)
        const qualifiedInputKey = inputKeys.get(input)
        if (
          separator > 0 &&
          qualifiedInputKey &&
          (qualifier === 'invalid' || qualifier === 'future')
        ) {
          const key = qualifier === 'invalid' ? 'invalidInput' : 'futureInput'
          label = translate(`derivedMissingInputs.${key}`, {
            label: translate(qualifiedInputKey),
          })
        } else label = translate('derivedMissingInputs.unknown')
      }
    }
    return { code, label }
  })
}

export function derivedSyntheticReason(reason: string) {
  if (reason === 'soul_reference_default')
    return localeText('derivedMissingInputs.syntheticReasons.referenceDefault')
  if (reason === 'soul_age_reference')
    return localeText('derivedMissingInputs.syntheticReasons.ageReference')
  return localeText('derivedMissingInputs.syntheticReasons.unknown')
}
