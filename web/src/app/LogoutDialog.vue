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
import { useLogout } from './useLogout'

const { t } = useI18n()
const { leaving, pending, logout } = useLogout()
</script>

<template>
  <Dialog v-model:open="leaving">
    <DialogContent data-testid="logout-dialog">
      <DialogHeader>
        <DialogTitle>{{ t('settings.leaveTitle') }}</DialogTitle>
        <DialogDescription>
          {{ t('settings.leaveText', { count: pending.length }, pending.length) }}
        </DialogDescription>
      </DialogHeader>
      <DialogFooter>
        <Button variant="outline" @click="leaving = false">{{ t('settings.stay') }}</Button>
        <Button variant="destructive" @click="logout">{{ t('settings.leaveAnyway') }}</Button>
      </DialogFooter>
    </DialogContent>
  </Dialog>
</template>
