<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { session } from '../composables/session'
import { locoApi, ApiError, message } from '../api/client'
import { useAction } from '../composables/action'
import { locoScreens, locoRow, locoPage, locoQuery, type LocoResource } from './locoModel'
import ReportProcessingForm from '../components/ReportProcessingForm.vue'
import { useQueryState } from '../composables/queryState'
import QueryStatus from '../components/QueryStatus.vue'
import { displayCode } from './displayLabels'
const queryState = useQueryState()
const advanced = ref(false), appliedLabel = ref('아직 적용된 조건이 없습니다.')
const props = defineProps<{ resource: LocoResource }>()
const route = useRoute()
const config = computed(() => locoScreens[props.resource])
const filters = ref<Record<string, string>>({}), from = ref(''), until = ref(''), size = ref(50)
const page = ref<ReturnType<ReturnType<typeof locoPage>>>({ items: [], nextCursor: null })
const detail = ref<ReturnType<ReturnType<typeof locoRow>> | null>(null)
const relations = ref<ReturnType<ReturnType<typeof locoRow>>['relations']>([])
const cursors = ref<(string | null)[]>([null])
const canManage = ref(false), reportNotice = ref('')
const action = useAction()
const { error, run } = action
const busy = computed(() => action.busy.value || queryState.status.value === 'loading')
let applied = 'size=50', generation = 0
async function load(cursor: string | null, candidate = applied, label = appliedLabel.value) {
  return queryState.load(async () => {
  const current = generation, resource = props.resource
  const query = new URLSearchParams(candidate)
  if (cursor) query.set('cursor', cursor)
  try {
    const result = await locoApi.read(`/${resource}?${query}`, locoPage(resource))
    if (current === generation) { page.value = result; applied = candidate; appliedLabel.value = label; detail.value = null; relations.value = [] }
  } catch (cause) {
    if (current === generation && cause instanceof ApiError && [401,403].includes(cause.status)) { queryState.hasResult.value = false; page.value = {items:[],nextCursor:null}; detail.value = null; relations.value = [] }
    throw cause
  }
  })
}
watch(() => [props.resource, route.query.id], () => {
  generation++; queryState.reset(); error.value = ''; advanced.value = false; appliedLabel.value = '아직 적용된 조건이 없습니다.'; filters.value = Object.fromEntries(config.value.filters.map(f => [f.key, ''])); from.value = ''; until.value = ''; size.value = 50
  page.value = { items: [], nextCursor: null }; detail.value = null; cursors.value = [null]; applied = 'size=50'
  relations.value = []; canManage.value = false; reportNotice.value = ''
  let initialLabel = '전체 · 50개씩'
  if (typeof route.query.id === 'string') { filters.value.id = route.query.id; advanced.value = true; initialLabel = 'ID: '+route.query.id+' · 50개씩'; applied = locoQuery(props.resource, filters.value, '', '', 50) }
  const current = generation
  void load(null, applied, initialLabel).catch(cause => { if (current === generation) error.value = message(cause) })
}, { immediate: true })
function search() { void run(async () => {
  const candidate = locoQuery(props.resource, filters.value, from.value, until.value, size.value)
  const conditions = config.value.filters.flatMap(f => filters.value[f.key]?.trim() ? [f.label+': '+displayCode(filters.value[f.key]!.trim())] : [])
  if (from.value) conditions.push('시작: '+from.value)
  if (until.value) conditions.push('종료: '+until.value)
  await load(null, candidate, (conditions.join(' · ') || '전체')+' · '+size.value+'개씩')
  cursors.value = [null]
}) }
function next() { void run(async () => { const cursor = page.value.nextCursor; if (cursor) { await load(cursor); cursors.value.push(cursor) } }) }
function previous() { void run(async () => { await load(cursors.value.at(-2) ?? null); cursors.value.pop() }) }
async function readDetail(id: string) {
  const current = generation
  detail.value = null; relations.value = []
  let result: ReturnType<ReturnType<typeof locoRow>>
  try { result = await locoApi.read(`/${props.resource}/${id}`, locoRow(props.resource, true)) }
  catch (cause) { if (current === generation && cause instanceof ApiError && [401,403].includes(cause.status)) page.value = {items:[],nextCursor:null}; throw cause }
  const allowed = await Promise.all(result.relations.map(async relation => ({ relation, allowed: await session.allowed('loco.'+relation.resource, 'READ') })))
  const manage = props.resource === 'reports' && await session.allowed('loco.reports', 'MANAGE')
  if (current === generation) { detail.value = result; canManage.value = manage; relations.value = allowed.filter(r => r.allowed).map(r => r.relation) }
}
function show(id: string) { reportNotice.value = ''; void run(() => readDetail(id)) }
function reportChanged(id: string, notice: string) {
  reportNotice.value = notice
  void run(async () => {await load(cursors.value.at(-1) ?? null); await readDetail(id)})
}
</script>
<template>
  <h1>{{ config.title }}</h1><p>{{ config.note }}</p><p v-if="resource !== 'reports'">조회 전용입니다. 업무 변경 이력을 만들지 않습니다.</p>
  <p v-else>신고 처리 상태만 기록합니다. 회원 정지·강제 비공개·앱 알림은 실행하지 않습니다.</p>
  <p role="status">{{ reportNotice }}</p>
  <p id="loco-error" role="alert">{{ error }}</p>
  <form @submit.prevent="search">
    <div class="query-fields"><div v-for="f in config.filters.filter(f => !f.key.endsWith('Id') && f.key !== 'id')" :key="f.key" class="query-field"><label :for="'loco-'+f.key">{{ f.label }}</label>
      <select v-if="f.options" :id="'loco-'+f.key" v-model="filters[f.key]" aria-describedby="loco-error"><option value="">전체</option><option v-for="o in f.options.filter(o => o !== 'ALL')" :key="o" :value="o">{{ displayCode(o) }}</option></select>
      <input v-else :id="'loco-'+f.key" v-model="filters[f.key]" maxlength="100" aria-describedby="loco-error">
    </div></div>
    <details :open="advanced"><summary>추가 조회 조건 (ID·기간·개수)</summary><div class="query-fields"><div v-for="f in config.filters.filter(f => f.key.endsWith('Id') || f.key === 'id')" :key="f.key" class="query-field"><label :for="'loco-'+f.key">{{ f.label }}</label>
      <select v-if="f.options" :id="'loco-'+f.key" v-model="filters[f.key]" aria-describedby="loco-error"><option value="">전체</option><option v-for="o in f.options.filter(o => o !== 'ALL')" :key="o" :value="o">{{ displayCode(o) }}</option></select>
      <input v-else :id="'loco-'+f.key" v-model="filters[f.key]" maxlength="100" aria-describedby="loco-error">
    </div>
    <div class="query-field"><label for="loco-from">생성 시작 (한국, 포함)</label><input id="loco-from" v-model="from" type="datetime-local" aria-describedby="loco-error"></div>
    <div class="query-field"><label for="loco-until">생성 종료 (한국, 제외)</label><input id="loco-until" v-model="until" type="datetime-local" aria-describedby="loco-error"></div>
    <div class="query-field"><label for="loco-size">페이지 크기 (1~100)</label><input id="loco-size" v-model.number="size" type="number" min="1" max="100" aria-describedby="loco-error"></div>
    </div></details>
    <button :disabled="busy">조회</button>
  </form>
  <p class="applied-query">적용된 조건: {{ appliedLabel }}. 입력만 바꾸면 결과에 반영되지 않습니다. 조회 버튼을 누르세요.</p>
  <QueryStatus :status="queryState.status.value" :has-result="queryState.hasResult.value" :empty="!page.items.length" />
  <div class="table-scroll"><table><caption>{{ config.title }} 결과</caption><thead><tr><th>ID</th><th v-for="f in page.items[0]?.fields ?? []" :key="f.label">{{ f.label }}</th><th>상세</th></tr></thead>
    <tbody><tr v-for="row in page.items" :key="row.id"><td>{{ row.id }}</td><td v-for="f in row.fields" :key="f.label">{{ f.value }}</td><td><button :disabled="busy" :aria-label="'상세 '+row.id" @click="show(row.id)">상세 보기</button></td></tr></tbody></table></div>
  <button :disabled="busy || cursors.length < 2" @click="previous">이전 결과</button><span> {{ cursors.length }}번째 조회 </span><button :disabled="busy || !page.nextCursor" @click="next">다음 결과</button>
  <section v-if="detail"><h2>상세 정보</h2><p>{{ detail.id }}</p><dl><template v-for="f in detail.fields" :key="f.label"><dt>{{ f.label }}</dt><dd class="preserve-text">{{ f.value }}</dd></template></dl><nav aria-label="관련 업무"><RouterLink v-for="r in relations" :key="r.label" :to="{ path: '/loco/'+r.resource, query: { id: r.id } }">{{ r.label }}</RouterLink><RouterLink v-if="resource === 'reports'" :to="{path:'/history',query:{resourceType:'loco.report',resourceId:detail.id}}">신고 처리 이력</RouterLink></nav>
    <ReportProcessingForm v-if="canManage && detail.processing" :id="detail.id" :value="detail.processing" @refresh="reportChanged(detail.id, $event)" />
  </section>
</template>
