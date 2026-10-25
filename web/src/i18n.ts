import { createI18n } from 'vue-i18n'
import ru from './locales/ru.json'
import clarifications from './locales/clarification-ru.json'
import activityDay from './locales/activity-day-ru.json'
import activityDetail from './locales/activity-detail-ru.json'
import activityNative from './locales/activity-native-ru.json'
import activityRedesign from './locales/activity-redesign-ru.json'
import activityWorkout from './locales/activity-workout-ru.json'

// The default rule picks English forms; Russian needs one/few/many, optionally after a zero form.
const russian = new Intl.PluralRules('ru')
const forms = { one: 0, few: 1, many: 2, other: 2, zero: 2, two: 2 } as const

export function russianPlural(choice: number, choicesLength: number): number {
  if (choicesLength < 3) return Math.min(choice === 1 ? 0 : 1, choicesLength - 1)
  if (choicesLength === 4) return choice === 0 ? 0 : forms[russian.select(choice)] + 1
  return forms[russian.select(choice)]
}

export const i18n = createI18n({
  legacy: false,
  locale: 'ru',
  fallbackLocale: 'ru',
  messages: {
    ru: {
      ...ru,
      ...clarifications,
      ...activityDay,
      ...activityDetail,
      ...activityNative,
      ...activityRedesign,
      ...activityWorkout,
    },
  },
  pluralRules: { ru: russianPlural },
})
