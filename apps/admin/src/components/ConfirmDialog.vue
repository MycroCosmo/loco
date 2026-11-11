<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref, watch, useId } from 'vue'
const props = defineProps<{ open: boolean; title: string; confirmLabel: string; busy: boolean; error?: string; opener?: HTMLElement | null }>()
const emit = defineEmits<{ confirm: []; cancel: [] }>()
const dialog = ref<HTMLDialogElement>()
const id = useId()
let previous: HTMLElement | null = null
watch([() => props.open, () => props.busy], async ([open,busy]) => {
  await nextTick()
  if (open) {
    if (!dialog.value?.open) {
      previous = props.opener ?? (document.activeElement instanceof HTMLElement ? document.activeElement : null)
      dialog.value?.showModal()
    }
    if (!busy) dialog.value?.querySelector<HTMLButtonElement>('button[autofocus]')?.focus()
  } else { dialog.value?.close(); previous?.focus() }
}, { immediate: true })
onBeforeUnmount(() => { dialog.value?.close(); previous?.focus() })
</script>
<template>
  <dialog ref="dialog" :aria-labelledby="id+'-title'" :aria-describedby="id+'-description'" @cancel.prevent="!busy && emit('cancel')">
    <h2 :id="id+'-title'">{{ title }}</h2><div :id="id+'-description'"><slot /></div><p role="alert">{{ error }}</p>
    <button type="button" autofocus :disabled="busy" @click="emit('cancel')">취소</button>
    <button type="button" :disabled="busy" @click="emit('confirm')">{{ confirmLabel }}</button>
  </dialog>
</template>
