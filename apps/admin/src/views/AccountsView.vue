<script setup lang="ts">
import { useQueryState } from '../composables/queryState'
import QueryStatus from '../components/QueryStatus.vue'
const queryState = useQueryState()
import { onMounted, ref } from 'vue'
import { api } from '../api/client'
import { assignment, identity, list, role, type Identity, type Role } from '../api/contracts'
import { useAction } from '../composables/action'
import { session } from '../composables/session'
import ConfirmDialog from '../components/ConfirmDialog.vue'
const accounts = ref<Identity[]>([]), roles = ref<Role[]>([])
const loginId = ref(''), displayName = ref(''), password = ref('')
const editing = ref<ReturnType<typeof assignment> | null>(null), chosen = ref<string[]>([])
const confirmation = ref<{kind:'status'; account:Identity} | {kind:'roles'} | null>(null)
const { busy, error, notice, run } = useAction()
async function load() { return queryState.load(async () => { [accounts.value,roles.value] = await Promise.all([api.read('/accounts',list(identity)), api.read('/roles',list(role))]) }) }
onMounted(() => run(load))
function create() { void run(async () => {
  try { await api.send('/accounts','POST',{loginId:loginId.value,displayName:displayName.value,password:password.value},identity); loginId.value=''; displayName.value=''; await load(); notice.value='관리자 계정을 생성했습니다. 첫 로그인에서 비밀번호를 변경해야 합니다.' }
  finally { password.value='' }
}) }
function edit(id:string) { void run(async () => { editing.value=null; const value=await api.read(`/accounts/${id}/roles`,assignment); editing.value=value; chosen.value=[...value.roleIds] }) }
function confirm() { void run(async () => {
  const pending=confirmation.value
  if (pending?.kind==='status') await api.send(`/accounts/${pending.account.id}/status`,'PUT',{active:!pending.account.active},identity)
  if (pending?.kind==='roles' && editing.value) await api.send(`/accounts/${editing.value.accountId}/roles`,'PUT',{roleIds:chosen.value,authVersion:editing.value.authVersion},identity)
  confirmation.value=null; editing.value=null; await session.changed(); await load(); notice.value='변경했습니다.'
}) }
</script>
<template>
  <h1>관리자 계정</h1><p>계정 관리·역할 배정은 최고관리자(SYSTEM_ADMIN) 전용입니다.</p><p id="accounts-error" role="alert">{{ error }}</p><p role="status">{{ notice }}</p>
  <button :disabled="busy" @click="run(async()=>{editing=null;await load()})">목록 새로고침</button>
  <QueryStatus :status="queryState.status.value" :has-result="queryState.hasResult.value" :empty="!accounts.length" />
  <div class="table-scroll"><table><caption>관리자 계정 목록</caption><thead><tr><th>ID / 이름</th><th>상태</th><th>작업</th></tr></thead><tbody>
    <tr v-for="a in accounts" :key="a.id"><td>{{ a.loginId }} / {{ a.displayName }}<br><small>{{ a.id }}</small></td><td>{{ a.active?'활성':'비활성' }} {{ a.systemAdmin?'(SYSTEM_ADMIN)':'' }}</td><td>
      <button :disabled="busy" @click="edit(a.id)">{{ a.loginId }} 역할 배정</button><button :disabled="busy" @click="confirmation={kind:'status',account:a}">{{ a.loginId }} {{ a.active?'비활성화':'활성화' }}</button>
    </td></tr></tbody></table></div>
  <section v-if="editing"><h2>역할 배정: {{ accounts.find(a=>a.id===editing?.accountId)?.loginId }}</h2><p>현재 배정을 읽은 뒤 전체 목록을 교체합니다. 변경하면 대상의 기존 세션이 무효화됩니다.</p>
    <fieldset><legend>배정 역할</legend><label v-for="r in roles" :key="r.id" :for="'assign-'+r.id"><input :id="'assign-'+r.id" type="checkbox" v-model="chosen" :value="r.id">{{ r.name }} ({{ r.code }})<br></label></fieldset>
    <button :disabled="busy" @click="confirmation={kind:'roles'}">역할 배정 저장</button><button :disabled="busy" @click="editing=null">편집 취소</button>
  </section>
  <h2>관리자 생성</h2><form @submit.prevent="create" :aria-busy="busy">
    <label for="account-login">새 로그인 ID</label><input id="account-login" v-model="loginId" required autocomplete="off" aria-describedby="accounts-error">
    <label for="account-name">표시 이름</label><input id="account-name" v-model="displayName" required aria-describedby="accounts-error">
    <label for="account-password">초기 비밀번호</label><input id="account-password" v-model="password" required type="password" autocomplete="new-password" aria-describedby="accounts-error"><button :disabled="busy">관리자 생성</button>
  </form>
  <ConfirmDialog :open="confirmation!==null" :title="confirmation?.kind === 'roles' ? '역할 배정 저장' : confirmation?.account.active ? '계정 비활성화' : '계정 활성화'" :confirm-label="confirmation?.kind === 'roles' ? '역할 배정 저장' : confirmation?.account.active ? '계정 비활성화' : '계정 활성화'" :busy="busy" :error="error" @cancel="confirmation=null" @confirm="confirm"><p>대상의 활성 상태 또는 배정 역할이 변경됩니다. 대상 관리자는 다시 로그인해야 할 수 있습니다.</p></ConfirmDialog>
</template>
