<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { api, ApiError } from '../api/client'
import { useRoute } from 'vue-router'
import { session } from '../composables/session'
import { historyEntry, historyPage } from '../api/contracts'
import { useAction } from '../composables/action'
import { historyFilters, displayTime } from './historyModel'
const filters=reactive({resourceType:'',resourceId:'',actorId:'',from:'',until:''})
const route=useRoute()
const page=ref<ReturnType<typeof historyPage>>({items:[],nextCursor:null}), detail=ref<ReturnType<typeof historyEntry>|null>(null)
const cursors=ref<(string|null)[]>([null]);let applied='size=50'
const {busy,error,run}=useAction()
function discardDenied(cause: unknown) { if (cause instanceof ApiError && [401,403].includes(cause.status)) {page.value={items:[],nextCursor:null};detail.value=null} }
async function load(cursor:string|null) {
  const params=new URLSearchParams(applied);if(cursor)params.set('cursor',cursor)
  try { await session.refreshHistoryResources();page.value=await api.read('/history?'+params,historyPage);detail.value=null }
  catch(cause){discardDenied(cause);throw cause}
}
function show(id:string) {void run(async()=>{try{detail.value=await api.read('/history/'+id,historyEntry)}catch(cause){discardDenied(cause);throw cause}})}
function search() {void run(async()=>{const query=historyFilters(filters);applied=query;await load(null);cursors.value=[null]})}
function next() {void run(async()=>{const cursor=page.value.nextCursor;if(cursor){await load(cursor);cursors.value.push(cursor)}})}
function previous() {void run(async()=>{const cursor=cursors.value[cursors.value.length-2]??null;await load(cursor);cursors.value.pop()})}
onMounted(()=>{for(const key of ['resourceType','resourceId'] as const){const value=route.query[key];if(typeof value==='string')filters[key]=value}search()})
</script>
<template>
  <h1>변경 이력</h1><p>서버가 허용한 이력만 조회합니다. core 보안 이력은 SYSTEM_ADMIN 전용, 신고 처리 이력은 loco.reports READ가 필요합니다. 기간은 Asia/Seoul, 시작 포함·종료 제외입니다.</p><p id="history-error" role="alert">{{ error }}</p>
  <form @submit.prevent="search"><label for="history-type">대상 유형</label><select id="history-type" v-model="filters.resourceType" aria-describedby="history-error"><option value="">허용된 전체</option><option v-for="resource in session.state.historyResources" :key="resource">{{ resource }}</option></select><label for="history-resource">대상 ID</label><input id="history-resource" v-model="filters.resourceId" aria-describedby="history-error"><label for="history-actor">행위자 ID</label><input id="history-actor" v-model="filters.actorId" aria-describedby="history-error">
    <label for="history-from">시작 시각 (한국)</label><input id="history-from" type="datetime-local" v-model="filters.from" aria-describedby="history-error"><label for="history-until">종료 시각 (한국)</label><input id="history-until" type="datetime-local" v-model="filters.until" aria-describedby="history-error"><button :disabled="busy">이력 조회</button></form>
  <div class="table-scroll"><table><caption>변경 이력 목록</caption><thead><tr><th>시각 (한국)</th><th>대상·행위</th><th>행위자</th><th>상세</th></tr></thead><tbody><tr v-for="h in page.items" :key="h.id"><td>{{ displayTime(h.occurredAt) }}</td><td>{{ h.resourceType }} / {{ h.resourceId }}<br>{{ h.action }}</td><td>{{ h.actorId??'행위자 미지정' }}</td><td><button :disabled="busy" @click="show(h.id)">상세 {{ h.id.slice(0,8) }}</button></td></tr></tbody></table></div>
  <p v-if="!busy&&!page.items.length">조건에 맞는 이력이 없습니다.</p><button :disabled="busy||cursors.length<=1" @click="previous">이전 이력</button><button :disabled="busy||!page.nextCursor" @click="next">다음 이력</button>
  <section v-if="detail"><h2>변경 전·후</h2><p>이력 계약 버전 {{ detail.payloadVersion }}</p><h3>변경 전</h3><pre>{{ JSON.stringify(detail.before,null,2) }}</pre><h3>변경 후</h3><pre>{{ JSON.stringify(detail.after,null,2) }}</pre></section>
</template>
