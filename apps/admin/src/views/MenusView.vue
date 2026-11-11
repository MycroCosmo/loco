<script setup lang="ts">
import { useQueryState } from '../composables/queryState'
import QueryStatus from '../components/QueryStatus.vue'
const queryState = useQueryState()
import { computed, onMounted, ref, shallowRef, watch } from 'vue'
import { api, ApiError } from '../api/client'
import { tree, movePreview, type Tree } from '../api/contracts'
import { useAction } from '../composables/action'
import { session } from '../composables/session'
import { screens } from '../screens'
import { adjacentPosition, excludedParents, outline } from './menuModel'
import ConfirmDialog from '../components/ConfirmDialog.vue'
const model=ref<Tree>({revision:0,nodes:[]}), canManage=ref(false), selectedId=ref('')
const parent=ref(''), before=ref(''), title=ref(''), routeKey=ref('cms.files'), visible=ref(true)
const createTitle=ref(''), createType=ref('GROUP'), createParent=ref(''), createScreen=ref('cms.files')
const preview=ref<ReturnType<typeof movePreview>|null>(null), deleting=ref(false)
const opener=shallowRef<HTMLElement|null>(null)
const {busy,error,notice,run}=useAction()
const selected=computed(()=>model.value.nodes.find(n=>n.id===selectedId.value))
const rows=computed(()=>outline(model.value.nodes))
const parents=computed(()=>rows.value.filter(r=>r.node.type==='GROUP'&&!excludedParents(model.value.nodes,selectedId.value).has(r.node.id)))
const siblings=computed(()=>model.value.nodes.filter(n=>n.parentId===(parent.value||null)&&n.id!==selectedId.value).sort((a,b)=>a.sortOrder-b.sortOrder))
watch(selectedId,()=>{const n=selected.value;parent.value=n?.parentId??'';before.value='';title.value=n?.title??'';routeKey.value=n?.routeKey??'cms.files';visible.value=n?.visible??true;preview.value=null})
watch([parent,before],()=>{preview.value=null})
async function load() { return queryState.load(async () => { model.value=await api.read('/menus',tree);if(!model.value.nodes.some(n=>n.id===selectedId.value))selectedId.value='' }) }
onMounted(()=>run(async()=>{canManage.value=await session.allowed('cms.menus','MANAGE');await load()}))
async function mutate(work:()=>Promise<void>, refresh=true) {
  try { await work(); queryState.status.value='ready'; queryState.hasResult.value=true; if(refresh)await session.changed() }
  catch(cause) {
    if(cause instanceof ApiError && cause.code==='STALE_REVISION') {
      preview.value=null;deleting.value=false;await load();parent.value='';before.value=''
    }
    throw cause
  }
}
function create() {void run(()=>mutate(async()=>{
  model.value=await api.send('/menus','POST',{title:createTitle.value,type:createType.value,parentId:createParent.value||null,beforeSiblingId:null,
    routeKey:createType.value==='PAGE'?createScreen.value:null,permissionKey:createType.value==='PAGE'?createScreen.value:null,visible:true,revision:model.value.revision},tree)
  createTitle.value='';notice.value='메뉴 생성 완료'
}))}
function save() {void run(()=>mutate(async()=>{
  if(!selected.value)return
  model.value=await api.send(`/menus/${selectedId.value}`,'PUT',{title:title.value,visible:visible.value,
    routeKey:selected.value.type==='PAGE'?routeKey.value:null,permissionKey:selected.value.type==='PAGE'?routeKey.value:null,revision:model.value.revision},tree)
  notice.value='메뉴를 변경했습니다.'
}))}
async function prepare() {
  opener.value=document.activeElement instanceof HTMLElement?document.activeElement:null
  await mutate(async()=>{ preview.value=await api.send(`/menus/${selectedId.value}/move-preview`,'POST',{
    parentId:parent.value||null,beforeSiblingId:before.value||null,revision:model.value.revision},movePreview) },false)
}
function adjacent(direction:'up'|'down') {void run(async()=>{
  const position=adjacentPosition(model.value.nodes,selectedId.value,direction)
  if(!position)return
  parent.value=position.parentId??'';before.value=position.beforeSiblingId??''
  await prepare()
})}
function move() {void run(()=>mutate(async()=>{
  const value=preview.value;if(!value)return
  model.value=await api.send(`/menus/${value.id}/position`,'PUT',{parentId:value.newParentId,beforeSiblingId:value.beforeSiblingId,revision:value.revision},tree)
  preview.value=null;notice.value='이동 완료 · 권한 변화 없음'
}))}
function remove() {void run(()=>mutate(async()=>{model.value=await api.send(`/menus/${selectedId.value}?revision=${model.value.revision}`,'DELETE',undefined,tree);deleting.value=false;selectedId.value='';notice.value='메뉴를 삭제했습니다.'}))}
function label(id:string|null) {return id?model.value.nodes.find(n=>n.id===id)?.title??id:'최상위'}
</script>
<template>
  <h1>메뉴 트리</h1><p>변경 번호 {{ model.revision }} · 이동해도 ID와 권한은 바뀌지 않습니다.</p><p id="menus-error" role="alert">{{ error }}</p><p role="status">{{ notice }}</p>
  <button :disabled="busy" @click="run(async()=>{preview=null;await load()})">최신 트리 조회</button>
  <QueryStatus :status="queryState.status.value" :has-result="queryState.hasResult.value" :empty="!model.nodes.length" /><ul><li v-for="r in rows" :key="r.node.id" :style="{marginLeft:r.depth+'rem'}"><button :disabled="busy" @click="selectedId=r.node.id">{{ r.node.title }}</button> {{ r.node.type === 'GROUP' ? '메뉴 묶음' : '화면 연결' }} {{ r.node.visible?'':'(숨김)' }} <small>{{ r.node.id }}</small></li></ul>
  <section v-if="selected&&canManage"><h2>메뉴 편집: {{ selected.title }}</h2>
    <label for="menu-title">메뉴 제목</label><input id="menu-title" v-model="title" :disabled="busy" aria-describedby="menus-error">
    <label for="menu-visible"><input id="menu-visible" v-model="visible" type="checkbox" :disabled="busy">표시</label>
    <template v-if="selected.type==='PAGE'"><label for="menu-screen">화면</label><select id="menu-screen" v-model="routeKey" :disabled="busy"><option v-for="s in screens" :key="s.key" :value="s.key">{{ s.label }}</option></select></template>
    <button :disabled="busy||!title.trim()" @click="save">메뉴 정보 저장</button><button :disabled="busy" @click="deleting=true">메뉴 삭제</button>
    <h3>이동·정렬</h3><label for="menu-parent">새 부모</label><select id="menu-parent" v-model="parent" :disabled="busy" @change="before='' "><option value="">최상위</option><option v-for="r in parents" :key="r.node.id" :value="r.node.id">{{ '· '.repeat(r.depth) }}{{ r.node.title }}</option></select>
    <label for="menu-before">배치 위치</label><select id="menu-before" v-model="before" :disabled="busy"><option value="">맨 뒤</option><option v-for="n in siblings" :key="n.id" :value="n.id">{{ n.title }} 앞</option></select>
    <button :disabled="busy" @click="run(prepare)">이동 미리보기</button><button :disabled="busy||!adjacentPosition(model.nodes,selectedId,'up')" @click="adjacent('up')">위로 이동 미리보기</button><button :disabled="busy||!adjacentPosition(model.nodes,selectedId,'down')" @click="adjacent('down')">아래로 이동 미리보기</button>
  </section>
  <section v-if="canManage"><h2>메뉴 생성</h2><form @submit.prevent="create">
    <label for="create-title">새 메뉴 제목</label><input id="create-title" v-model="createTitle" required :disabled="busy" aria-describedby="menus-error">
    <label for="create-type">종류</label><select id="create-type" v-model="createType" :disabled="busy"><option value="GROUP">메뉴 묶음 (GROUP)</option><option value="PAGE">화면 연결 (PAGE)</option></select>
    <label for="create-parent">생성 부모</label><select id="create-parent" v-model="createParent" :disabled="busy"><option value="">최상위</option><option v-for="r in rows.filter(r=>r.node.type==='GROUP')" :key="r.node.id" :value="r.node.id">{{ r.node.title }}</option></select>
    <template v-if="createType==='PAGE'"><label for="create-screen">등록 화면</label><select id="create-screen" v-model="createScreen" :disabled="busy"><option v-for="s in screens" :key="s.key" :value="s.key">{{ s.label }}</option></select></template><button :disabled="busy">메뉴 생성</button>
  </form></section>
  <ConfirmDialog :open="preview!==null" title="메뉴 이동 미리보기" confirm-label="메뉴 이동" :busy="busy" :error="error" :opener="opener" @cancel="preview=null" @confirm="move"><p>{{ label(preview?.oldParentId??null) }} → {{ label(preview?.newParentId??null) }}</p><p>{{ preview?.message }}</p><p>ID를 재생성하지 않습니다. 다른 관리자가 변경하면 최신 위치를 다시 확인해야 합니다.</p></ConfirmDialog>
  <ConfirmDialog :open="deleting" title="메뉴 삭제" confirm-label="메뉴 삭제" :busy="busy" :error="error" @cancel="deleting=false" @confirm="remove"><p>{{ selected?.title }} 메뉴를 삭제합니다. 하위 메뉴가 있으면 삭제할 수 없습니다.</p></ConfirmDialog>
</template>
