<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { locoApi } from '../api/client'
import { useAction } from '../composables/action'
import { locoScreens, locoRow, locoPage, locoQuery, type LocoResource } from './locoModel'
const props = defineProps<{ resource: LocoResource }>()
const config = computed(() => locoScreens[props.resource])
const filters = ref<Record<string, string>>({}), from = ref(''), until = ref(''), size = ref(50)
const page = ref<ReturnType<ReturnType<typeof locoPage>>>({ items: [], nextCursor: null })
const detail = ref<ReturnType<ReturnType<typeof locoRow>> | null>(null)
const cursors = ref<(string | null)[]>([null])
const { busy, error, run } = useAction()
let applied = 'size=50', generation = 0
async function load(cursor: string | null) {
  const current = generation, resource = props.resource
  const query = new URLSearchParams(applied)
  if (cursor) query.set('cursor', cursor)
  const result = await locoApi.read(`/${resource}?${query}`, locoPage(resource))
  if (current === generation) { page.value = result; detail.value = null }
}
watch(() => props.resource, () => {
  generation++; filters.value = {}; from.value = ''; until.value = ''; size.value = 50
  page.value = { items: [], nextCursor: null }; detail.value = null; cursors.value = [null]; applied = 'size=50'
  void run(() => load(null))
}, { immediate: true })
function search() { void run(async () => { applied = locoQuery(props.resource, filters.value, from.value, until.value, size.value); await load(null); cursors.value = [null] }) }
function next() { void run(async () => { const cursor = page.value.nextCursor; if (cursor) { await load(cursor); cursors.value.push(cursor) } }) }
function previous() { void run(async () => { await load(cursors.value.at(-2) ?? null); cursors.value.pop() }) }
function show(id: string) { void run(async () => {
  const current = generation
  const result = await locoApi.read(`/${props.resource}/${id}`, locoRow(props.resource, true))
  if (current === generation) detail.value = result
}) }
</script>
<template>
  <h1>{{ config.title }}</h1><p>{{ config.note }}</p><p>조회 전용입니다. 업무 변경 이력을 만들지 않습니다.</p>
  <p id="loco-error" role="alert">{{ error }}</p>
  <form @submit.prevent="search">
    <template v-for="f in config.filters" :key="f.key"><label :for="'loco-'+f.key">{{ f.label }}</label>
      <select v-if="f.options" :id="'loco-'+f.key" v-model="filters[f.key]" aria-describedby="loco-error"><option value="">전체</option><option v-for="o in f.options" :key="o">{{ o }}</option></select>
      <input v-else :id="'loco-'+f.key" v-model="filters[f.key]" maxlength="100" aria-describedby="loco-error">
    </template>
    <label for="loco-from">생성 시작 (한국, 포함)</label><input id="loco-from" v-model="from" type="datetime-local" aria-describedby="loco-error">
    <label for="loco-until">생성 종료 (한국, 제외)</label><input id="loco-until" v-model="until" type="datetime-local" aria-describedby="loco-error">
    <label for="loco-size">페이지 크기 (1~100)</label><input id="loco-size" v-model.number="size" type="number" min="1" max="100" aria-describedby="loco-error">
    <button :disabled="busy">조회</button>
  </form>
  <p v-if="busy" role="status">불러오는 중…</p>
  <p v-if="!busy && !page.items.length">조건에 맞는 결과가 없습니다.</p>
  <div class="table-scroll"><table><caption>{{ config.title }} 결과</caption><thead><tr><th>ID</th><th v-for="f in page.items[0]?.fields ?? []" :key="f.label">{{ f.label }}</th><th>상세</th></tr></thead>
    <tbody><tr v-for="row in page.items" :key="row.id"><td>{{ row.id }}</td><td v-for="f in row.fields" :key="f.label">{{ f.value }}</td><td><button :disabled="busy" @click="show(row.id)">상세 {{ row.id.slice(0,8) }}</button></td></tr></tbody></table></div>
  <button :disabled="busy || cursors.length < 2" @click="previous">이전 결과</button><span> {{ cursors.length }}번째 조회 </span><button :disabled="busy || !page.nextCursor" @click="next">다음 결과</button>
  <section v-if="detail"><h2>상세 정보</h2><p>{{ detail.id }}</p><dl><template v-for="f in detail.fields" :key="f.label"><dt>{{ f.label }}</dt><dd class="preserve-text">{{ f.value }}</dd></template></dl></section>
</template>
