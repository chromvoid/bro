<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query'
import { useI18n } from 'vue-i18n'
import { api } from '@/core/client'
import { ClientConfig } from '@/sections/notifications/native'
const { t } = useI18n()
const config = useQuery({
  queryKey: ['client', 'config'],
  queryFn: async ({ signal }) => ClientConfig.parse(await api.get('/v1/client/config', signal)),
})
const safeUrl = (value: string | null | undefined) => {
  try {
    const url = new URL(value ?? '')
    return url.protocol === 'https:' ? url.href : null
  } catch {
    return null
  }
}
</script>
<template>
  <section
    v-if="config.data.value"
    class="bg-card space-y-3 rounded-3xl border p-5"
    aria-labelledby="installation-info"
  >
    <h2 id="installation-info" class="font-semibold">{{ t('installation.title') }}</h2>
    <p class="text-muted-foreground text-sm">
      {{ t('installation.data', { name: config.data.value.name }) }}
    </p>
    <p v-if="config.data.value.providers?.length" class="text-muted-foreground text-sm">
      {{ t('installation.providers', { providers: config.data.value.providers.join(', ') }) }}
    </p>
    <div class="flex flex-wrap gap-4 text-sm">
      <a
        v-if="safeUrl(config.data.value.privacy_url)"
        :href="safeUrl(config.data.value.privacy_url)!"
        target="_blank"
        rel="noopener noreferrer"
        class="text-primary underline"
        >{{ t('installation.privacy') }}</a
      >
      <a
        v-if="safeUrl(config.data.value.support_url)"
        :href="safeUrl(config.data.value.support_url)!"
        target="_blank"
        rel="noopener noreferrer"
        class="text-primary underline"
        >{{ t('installation.support') }}</a
      >
    </div>
  </section>
</template>
