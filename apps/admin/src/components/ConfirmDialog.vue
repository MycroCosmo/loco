<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref, watch, useId } from 'vue'
const props = defineProps<{ open: boolean; title: string; busy: boolean; error?: string }>()
const emit = defineEmits<{ confirm: []; cancel: [] }>()
const dialog = ref<HTMLDialogElement>()
const id = useId()
let previous: HTMLElement | null = null
watch(() => props.open, async open => {
  await nextTick()
  if (open) {
    previous = document.activeElement instanceof HTMLElement ? document.activeElement : null
    dialog.value?.showModal()
  } else { dialog.value?.close(); previous?.focus() }
}, { immediate: true })
onBeforeUnmount(() => { dialog.value?.close(); previous?.focus() })
</script>
<template>
  <dialog ref="dialog" :aria-labelledby="id+'-title'" :aria-describedby="id+'-description'" @cancel.prevent="!busy && emit('cancel')">
    <h2 :id="id+'-title'">{{ title }}</h2><div :id="id+'-description'"><slot /></div><p role="alert">{{ error }}</p>
    <button type="button" autofocus :disabled="busy" @click="emit('cancel')">취소</button>
    <button type="button" :disabled="busy" @click="emit('confirm')">확인</button>
  </dialog>
</template>
