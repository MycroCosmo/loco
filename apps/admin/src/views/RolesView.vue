<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api, nothing } from '../api/client'
import { list, role, permission, roleTemplate, type Role } from '../api/contracts'
import { session } from '../composables/session'
import { useAction } from '../composables/action'
import ConfirmDialog from '../components/ConfirmDialog.vue'
const roles=ref<Role[]>([]), permissions=ref<ReturnType<typeof permission>[]>([]), templates=ref<ReturnType<typeof roleTemplate>[]>([])
const code=ref(''), name=ref(''), template=ref(''), keys=ref<string[]>([])
const selected=ref<Role|null>(null), renamed=ref(''), grants=ref<Record<string,'READ'|'MANAGE'|''>>({}), deleting=ref(false)
const {busy,error,notice,run}=useAction()
async function load() { [roles.value,permissions.value,templates.value]=await Promise.all([api.read('/roles',list(role)),api.read('/permissions',list(permission)),api.read('/role-templates',list(roleTemplate))]) }
function select(r:Role) { selected.value=r; renamed.value=r.name; grants.value=Object.fromEntries(permissions.value.filter(p=>!p.systemOnly).map(p=>[p.key,r.permissions[p.key]??''])) }
onMounted(()=>run(load))
function create() { void run(async()=>{ await api.send('/roles','POST',{code:code.value,name:name.value,template:template.value||null,permissionKeys:template.value?keys.value:[]},role); code.value='';name.value='';keys.value=[];await load();notice.value='역할을 생성했습니다.' }) }
function save(kind:'name'|'permissions') { void run(async()=>{
  if(!selected.value)return
  const path='/roles/'+selected.value.id
  const permissions=Object.fromEntries(Object.entries(grants.value).filter(([,value])=>value))
  const result=await api.send(kind==='name'?path:path+'/permissions','PUT',kind==='name'?{name:renamed.value,version:selected.value.version}:{permissions,version:selected.value.version},role)
  select(result); await session.changed(); await load();notice.value='역할을 변경했습니다.'
}) }
function remove() { void run(async()=>{if(!selected.value)return; await api.send(`/roles/${selected.value.id}?version=${selected.value.version}`,'DELETE',undefined,nothing);deleting.value=false;selected.value=null;await load();notice.value='역할을 삭제했습니다.'}) }
</script>
<template>
  <h1>역할·권한</h1><p>MANAGE는 READ를 포함합니다. 역할별 권한은 합집합이며 상속·deny는 없습니다.</p><p id="roles-error" role="alert">{{ error }}</p><p role="status">{{ notice }}</p>
  <button :disabled="busy" @click="run(async()=>{selected=null;await load()})">목록 새로고침</button>
  <ul><li v-for="r in roles" :key="r.id"><button :disabled="busy" @click="select(r)">{{ r.name }} ({{ r.code }})</button><span v-if="r.systemProtected"> 보호 역할</span></li></ul>
  <section v-if="selected"><h2>{{ selected.code }} 편집</h2><p v-if="selected.systemProtected">SYSTEM_ADMIN 역할은 수정·삭제할 수 없습니다.</p>
    <template v-else><label for="role-rename">역할 이름</label><input id="role-rename" v-model="renamed" aria-describedby="roles-error"><button :disabled="busy||!renamed.trim()" @click="save('name')">이름 저장</button>
      <fieldset><legend>업무 권한</legend><template v-for="p in permissions.filter(p=>!p.systemOnly)" :key="p.key"><label :for="'grant-'+p.key">{{ p.label }} ({{ p.key }})</label><select :id="'grant-'+p.key" v-model="grants[p.key]"><option value="">없음</option><option v-for="a in p.allowedActions" :key="a">{{ a }}</option></select></template></fieldset>
      <button :disabled="busy" @click="save('permissions')">권한 저장</button><button :disabled="busy" @click="deleting=true">역할 삭제</button>
    </template>
  </section>
  <h2>역할 생성</h2><form @submit.prevent="create"><label for="role-code">역할 코드</label><input id="role-code" v-model="code" required aria-describedby="roles-error"><label for="role-name">역할 이름</label><input id="role-name" v-model="name" required aria-describedby="roles-error">
    <label for="role-template">시작 템플릿</label><select id="role-template" v-model="template"><option value="">빈 역할</option><option v-for="t in templates" :key="t.code" :value="t.code">{{ t.code }} ({{ t.action }})</option></select>
    <fieldset v-if="template"><legend>복사할 업무 권한 키</legend><label v-for="p in permissions.filter(p=>!p.systemOnly)" :key="p.key" :for="'template-'+p.key"><input :id="'template-'+p.key" type="checkbox" v-model="keys" :value="p.key">{{ p.label }}<br></label></fieldset>
    <p>템플릿은 생성 시 복사되며 이후 변경이 기존 역할에 전파되지 않습니다.</p><button :disabled="busy">역할 생성</button>
  </form>
  <ConfirmDialog :open="deleting" title="역할 삭제" :busy="busy" :error="error" @cancel="deleting=false" @confirm="remove"><p>{{ selected?.name }} 역할을 삭제합니다. 배정된 역할은 서버가 삭제를 거절합니다.</p></ConfirmDialog>
</template>
