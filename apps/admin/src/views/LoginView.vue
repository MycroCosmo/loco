<script setup lang="ts">
import { ref } from 'vue'
import { session } from '../composables/session'
import { useAction } from '../composables/action'
const loginId = ref(''), password = ref('')
const { busy, error, run } = useAction()
async function submit() { await run(async () => { try { await session.login(loginId.value, password.value) } finally { password.value = '' } }) }
</script>
<template>
  <h1>관리자 로그인</h1>
  <p>LOCO 회원 계정과 별도입니다. 초기 계정은 관리자 생성 CLI로 준비합니다.</p>
  <form @submit.prevent="submit" :aria-busy="busy">
    <label for="login-id">로그인 ID</label><input id="login-id" v-model="loginId" autocomplete="username" required aria-describedby="login-error">
    <label for="login-password">비밀번호</label><input id="login-password" v-model="password" type="password" autocomplete="current-password" required aria-describedby="login-error">
    <p id="login-error" role="alert">{{ error }}</p><button :disabled="busy">로그인</button>
  </form>
</template>
