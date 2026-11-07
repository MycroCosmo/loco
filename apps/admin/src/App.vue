<script setup lang="ts">
import { session } from './composables/session'
import { useAction } from './composables/action'
import { ref, watch } from 'vue'
import { screens } from './screens'
import { message } from './api/client'
import NavigationTree from './components/NavigationTree.vue'
const { busy, error, run } = useAction()
const links = ref<typeof screens>([])
watch(() => session.state.user, async user => {
  links.value = []
  if (!user || user.mustChangePassword) return
  const epoch = session.state.epoch
  try {
    const allowed = await Promise.all(screens.map(async item => ({ item, allowed: await session.allowed(item.key, 'READ') })))
    if (epoch === session.state.epoch) links.value = allowed.filter(item => item.allowed).map(item => item.item)
  } catch (cause) { error.value = message(cause) }
}, { immediate: true })
</script>
<template>
  <a class="skip" href="#main">본문 바로가기</a>
  <header><strong>CMS 관리</strong><span v-if="session.state.user">{{ session.state.user.displayName }}</span>
    <template v-if="session.state.user"><RouterLink to="/password">비밀번호 변경</RouterLink><button :disabled="busy" @click="run(session.logout)">로그아웃</button></template>
  </header>
  <template v-if="session.state.user && !session.state.user.mustChangePassword">
    <nav aria-label="관리 도구"><RouterLink to="/access">내 접근 근거</RouterLink><RouterLink v-for="link in links" :key="link.key" :to="link.path">{{ link.label }}</RouterLink><RouterLink v-if="session.state.user.systemAdmin" to="/history">변경 이력</RouterLink></nav>
    <nav v-if="session.state.navigation.nodes.length" aria-label="업무 메뉴"><NavigationTree :nodes="session.state.navigation.nodes" :parent="null" /></nav>
  </template>
  <p role="status">{{ session.state.notice }}</p><p role="alert">{{ error }}</p>
  <main id="main" tabindex="-1"><RouterView :key="session.state.epoch+':'+$route.path" /></main>
</template>
