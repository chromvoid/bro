<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { aiConsent } from '@/core/aiConsent'

const { t } = useI18n()
// The policy of the server this page came from.
const privacy = new URL('/privacy', window.location.origin).href
</script>

<template>
  <Dialog :open="aiConsent.asking.value" @update:open="(open) => !open && aiConsent.decline()">
    <DialogContent :show-close-button="false" data-testid="ai-consent">
      <DialogHeader>
        <DialogTitle>{{ t('aiConsent.title') }}</DialogTitle>
        <DialogDescription>{{ t('aiConsent.text') }}</DialogDescription>
      </DialogHeader>
      <a
        :href="privacy"
        target="_blank"
        rel="noopener noreferrer"
        class="text-primary justify-self-start text-sm underline"
        >{{ t('aiConsent.privacy') }}</a
      >
      <DialogFooter>
        <Button variant="outline" data-testid="ai-consent-later" @click="aiConsent.decline()">{{
          t('aiConsent.later')
        }}</Button>
        <Button data-testid="ai-consent-allow" @click="aiConsent.allow()">{{
          t('aiConsent.allow')
        }}</Button>
      </DialogFooter>
    </DialogContent>
  </Dialog>
</template>
