import { ref } from 'vue'
import { message } from '../api/client'

export function useAction() {
  const busy = ref(false), error = ref(''), notice = ref('')
  async function run(work: () => Promise<void>) {
    if (busy.value) return
    busy.value = true; error.value = ''; notice.value = ''
    try { await work() } catch (cause) { error.value = message(cause) }
    finally { busy.value = false }
  }
  return { busy, error, notice, run }
}
