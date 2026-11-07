<script setup lang="ts">
import { session } from './composables/session'
import { useAction } from './composables/action'
const { busy, error, run } = useAction()
</script>
<template>
  <a class="skip" href="#main">본문 바로가기</a>
  <header><strong>CMS 관리</strong><span v-if="session.state.user">{{ session.state.user.displayName }}</span>
    <template v-if="session.state.user"><RouterLink to="/password">비밀번호 변경</RouterLink><button :disabled="busy" @click="run(session.logout)">로그아웃</button></template>
  </header>
  <p role="status">{{ session.state.notice }}</p><p role="alert">{{ error }}</p>
  <main id="main" tabindex="-1"><RouterView :key="session.state.epoch" /></main>
</template>
