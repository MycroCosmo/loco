<script setup lang="ts">
import { ref } from 'vue'
import { session } from '../composables/session'
import { useAction } from '../composables/action'
const current = ref(''), next = ref(''), confirm = ref('')
const { busy, error, run } = useAction()
async function submit() {
  if (next.value !== confirm.value) { error.value = '새 비밀번호 확인이 일치하지 않습니다.'; return }
  await run(async () => { try { await session.password(current.value, next.value) }
    finally { current.value = ''; next.value = ''; confirm.value = '' } })
}
</script>
<template>
  <h1>비밀번호 변경</h1><p v-if="session.state.user?.mustChangePassword">첫 로그인입니다. 업무 화면 이용 전에 비밀번호를 변경하세요.</p>
  <form @submit.prevent="submit" :aria-busy="busy">
    <label for="current-password">현재 비밀번호</label><input id="current-password" v-model="current" type="password" autocomplete="current-password" required aria-describedby="password-error">
    <label for="new-password">새 비밀번호</label><input id="new-password" v-model="next" type="password" autocomplete="new-password" required aria-describedby="password-error">
    <label for="confirm-password">새 비밀번호 확인</label><input id="confirm-password" v-model="confirm" type="password" autocomplete="new-password" required aria-describedby="password-error">
    <p id="password-error" role="alert">{{ error }}</p><button :disabled="busy">비밀번호 변경</button>
  </form><p>변경하면 세션이 종료되어 다시 로그인해야 합니다.</p>
</template>
