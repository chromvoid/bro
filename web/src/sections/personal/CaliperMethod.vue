<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import maleGuide from '@/assets/caliper/male-three-site.png'
import { FOLD_SITES } from './caliper'

defineProps<{ gender?: 'male' | 'female' | null }>()
const { t } = useI18n()
</script>

<template>
  <details class="text-sm">
    <summary class="text-primary cursor-pointer font-medium">
      {{ t('personal.caliper.about') }}
    </summary>
    <div class="text-muted-foreground mt-3 space-y-3">
      <p>{{ t('personal.caliper.method') }}</p>
      <p>{{ t('personal.caliper.technique') }}</p>
      <figure
        v-if="gender !== 'female'"
        class="space-y-3 rounded-2xl border p-3"
        data-testid="caliper-male-guide"
      >
        <figcaption class="text-foreground font-medium">
          {{ t('personal.caliper.maleGuideTitle') }}
        </figcaption>
        <p>{{ t('personal.caliper.maleGuideHint') }}</p>
        <img
          :src="maleGuide"
          :alt="t('personal.caliper.maleGuideAlt')"
          width="1024"
          height="1536"
          loading="lazy"
          decoding="async"
          class="mx-auto h-auto w-full max-w-xl rounded-xl"
        />
        <p>{{ t('personal.caliper.maleGuideMarkers') }}</p>
        <p>{{ t('personal.caliper.maleGuideTiming') }}</p>
        <a
          :href="maleGuide"
          target="_blank"
          rel="noopener noreferrer"
          class="text-primary block underline-offset-4 hover:underline"
          >{{ t('personal.caliper.fullSizeGuide') }}</a
        >
      </figure>
      <dl class="space-y-2">
        <div v-for="site in FOLD_SITES" :key="site">
          <dt class="text-foreground font-medium">{{ t(`personal.caliper.sites.${site}`) }}</dt>
          <dd>{{ t(`personal.caliper.techniqueSite.${site}`) }}</dd>
        </div>
      </dl>
      <p>{{ t('personal.caliper.limits') }}</p>
      <p class="flex flex-wrap gap-x-4 gap-y-2">
        <a
          href="https://expert.sportwiki.to/fatEngine.html"
          target="_blank"
          rel="noopener noreferrer"
          class="text-primary underline-offset-4 hover:underline"
          >{{ t('personal.caliper.calculator') }}</a
        >
        <a
          href="https://doi.org/10.1079/BJN19780152"
          target="_blank"
          rel="noopener noreferrer"
          class="text-primary underline-offset-4 hover:underline"
          >{{ t('personal.caliper.researchMale') }}</a
        >
        <a
          href="https://pubmed.ncbi.nlm.nih.gov/7402053/"
          target="_blank"
          rel="noopener noreferrer"
          class="text-primary underline-offset-4 hover:underline"
          >{{ t('personal.caliper.researchFemale') }}</a
        >
        <a
          href="https://pmc.ncbi.nlm.nih.gov/articles/PMC7156815/"
          target="_blank"
          rel="noopener noreferrer"
          class="text-primary underline-offset-4 hover:underline"
          >{{ t('personal.caliper.protocolSource') }}</a
        >
      </p>
    </div>
  </details>
</template>
