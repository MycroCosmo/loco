<script setup lang="ts">
import { displayCode } from './displayLabels'
import { ref } from 'vue'
import { useRoute } from 'vue-router'
import { api } from '../api/client'
import { explanation } from '../api/contracts'
import { screens } from '../screens'
import { session } from '../composables/session'
import { useAction } from '../composables/action'
const key = ref('cms.files'), action = ref('READ'), accountId = ref('')
const route = useRoute()
const result = ref<ReturnType<typeof explanation> | null>(null)
const { busy, error, run } = useAction()
function query() { void run(async () => {
  result.value = null
  const params = new URLSearchParams({permissionKey:key.value,action:action.value})
  if (session.state.user?.systemAdmin && accountId.value.trim()) params.set('accountId',accountId.value.trim())
  result.value = await api.read('/permissions/explain?' + params, explanation)
}) }
const reasons: Record<string,string> = { SYSTEM_ADMIN:'최고관리자이므로 허용', SYSTEM_ADMIN_REQUIRED:'최고관리자 전용입니다.', ROLE_GRANT:'배정 역할의 권한으로 허용', NO_ROLE_GRANT:'허용하는 역할이 없습니다.', PASSWORD_CHANGE_REQUIRED:'비밀번호 변경이 필요합니다.', ACCOUNT_INACTIVE:'비활성 계정입니다.' }
</script>
<template>
  <h1>내 접근 근거</h1><p v-if="route.query.denied" role="alert">요청한 화면의 권한이 없습니다.</p><p v-if="route.query.unavailable" role="alert">접근 권한을 확인하지 못했습니다. 잠시 후 다시 시도하세요.</p><p>메뉴 위치는 권한을 바꾸지 않습니다. 관리(MANAGE)에는 조회(READ)가 포함됩니다. 권한 상속이나 거부 설정은 없습니다.</p>
  <form @submit.prevent="query"><label for="access-key">업무 권한 키</label><select id="access-key" v-model="key"><option v-for="s in screens" :key="s.key" :value="s.key">{{ s.label }} ({{ s.key }})</option></select>
    <label for="access-action">동작</label><select id="access-action" v-model="action"><option value="READ">조회 (READ)</option><option value="MANAGE">관리 (MANAGE)</option></select>
    <template v-if="session.state.user?.systemAdmin"><label for="access-account">대상 관리자 ID (비우면 본인)</label><input id="access-account" v-model="accountId"></template>
    <button :disabled="busy">접근 근거 조회</button></form><p role="alert">{{ error }}</p>
  <section v-if="result" aria-live="polite"><h2>{{ result.allowed ? '접근 가능' : '접근 불가' }}</h2><p>{{ reasons[result.reason] ?? result.reason }}</p>
    <ul><li v-for="grant in result.grants" :key="grant.roleId">{{ grant.roleCode }}: {{ displayCode(grant.grantedAction) }}<span v-if="grant.impliesRead"> → 조회 포함</span></li></ul>
  </section>
</template>
