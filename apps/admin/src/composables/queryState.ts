import { ref } from 'vue'

// Separate read state from mutation errors: a failed action is not an empty list.
export function useQueryState() {
  const status = ref<'idle' | 'loading' | 'ready' | 'failed'>('idle')
  const hasResult = ref(false)
  let generation = 0
  function reset() { generation++; status.value = 'idle'; hasResult.value = false }
  async function load(work: () => Promise<void>) {
    const current = ++generation
    status.value = 'loading'
    try {
      await work()
      if (current === generation) { status.value = 'ready'; hasResult.value = true }
    } catch (error) {
      if (current === generation) status.value = 'failed'
      throw error
    }
  }
  return { status, hasResult, load, reset }
}
