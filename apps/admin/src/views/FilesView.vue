<script setup lang="ts">
import { useQueryState } from '../composables/queryState'
import QueryStatus from '../components/QueryStatus.vue'
const queryState = useQueryState()
import { onMounted, ref } from 'vue'
import { api, nothing } from '../api/client'
import { list, storedFile } from '../api/contracts'
import { session } from '../composables/session'
import { useAction } from '../composables/action'
import { displayTime } from './historyModel'
import ConfirmDialog from '../components/ConfirmDialog.vue'
const files=ref<ReturnType<typeof storedFile>[]>([]), detail=ref<ReturnType<typeof storedFile>|null>(null), pendingDelete=ref<ReturnType<typeof storedFile>|null>(null)
const page=ref(0), canManage=ref(false), picker=ref<HTMLInputElement>(), chosen=ref<File|null>(null)
const {busy,error,notice,run}=useAction()
async function load(target=page.value) { return queryState.load(async () => {const result=await api.read(`/files?page=${target}&size=50`,list(storedFile));files.value=result;page.value=target;detail.value=null}) }
onMounted(()=>run(async()=>{canManage.value=await session.allowed('cms.files','MANAGE');await load()}))
function select(event:Event) {const target=event.target;if(target instanceof HTMLInputElement)chosen.value=target.files?.item(0)??null}
function upload() {void run(async()=>{
  const file=chosen.value;if(!file)throw new Error('파일을 선택하세요.')
  if(file.size>10*1024*1024)throw new Error('파일은 10MiB 이하여야 합니다.')
  const form=new FormData();form.append('file',file)
  try{await api.send('/files','POST',form,storedFile);await load(0);notice.value='업로드 완료'}
  catch(cause){notice.value='응답을 받지 못한 경우 목록을 먼저 확인하세요. 수동 재업로드하면 중복 파일이 생길 수 있습니다.';throw cause}
  finally{chosen.value=null;if(picker.value)picker.value.value=''}
})}
function download(file:ReturnType<typeof storedFile>) {void run(async()=>{
  const blob=await api.download(`/files/${file.id}/content`), url=URL.createObjectURL(blob)
  try{const anchor=document.createElement('a');anchor.href=url;anchor.download=file.originalName;document.body.append(anchor);anchor.click();anchor.remove();await new Promise(resolve=>setTimeout(resolve,0));notice.value='다운로드 요청 완료'}finally{URL.revokeObjectURL(url)}
})}
function remove() {void run(async()=>{if(!pendingDelete.value)return;await api.send(`/files/${pendingDelete.value.id}`,'DELETE',undefined,nothing);pendingDelete.value=null;await load();notice.value='목록에서 삭제했습니다. 저장소의 파일 삭제가 지연되면 자동으로 다시 처리합니다.'})}
</script>
<template>
  <h1>파일</h1><p>조회(READ): 전체 파일 조회·다운로드 / 관리(MANAGE): 업로드·삭제. 누가 올렸는지와 관계없이 권한에 따라 사용할 수 있습니다.</p><p id="files-error" role="alert">{{ error }}</p><p role="status">{{ notice }}</p>

  <p v-if="busy" role="status">처리 중…</p><button :disabled="busy" @click="run(()=>load())">파일 목록 새로고침</button>
  <QueryStatus :status="queryState.status.value" :has-result="queryState.hasResult.value" :empty="!files.length" />
  <div class="table-scroll"><table><caption>사용 가능한 파일</caption><thead><tr><th>파일 이름</th><th>형식·크기</th><th>생성 시각 (한국)</th><th>작업</th></tr></thead><tbody><tr v-for="f in files" :key="f.id"><td>{{ f.originalName }}</td><td>{{ f.mediaType }} / {{ f.byteSize.toLocaleString() }} bytes</td><td>{{ displayTime(f.createdAt) }}</td><td><button :disabled="busy" @click="run(async()=>{detail=await api.read('/files/'+f.id,storedFile)})">상세 {{ f.originalName }}</button><button :disabled="busy" @click="download(f)">다운로드 {{ f.originalName }}</button><button v-if="canManage" :disabled="busy" @click="pendingDelete=f">삭제 {{ f.originalName }}</button></td></tr></tbody></table></div>
  <button :disabled="busy||page===0" @click="run(()=>load(page-1))">이전 파일</button><span> {{ page+1 }} 페이지 </span><button :disabled="busy||files.length<50" @click="run(()=>load(page+1))">다음 파일</button>
  <section v-if="canManage"><h2>파일 업로드</h2><form @submit.prevent="upload"><label for="upload-file">파일 (10MiB 이하)</label><input id="upload-file" ref="picker" type="file" accept=".jpg,.jpeg,.png,.webp,.avif,.pdf,.txt,.csv" :disabled="busy" @change="select" aria-describedby="file-policy files-error"><p id="file-policy">이미지: JPEG·PNG·WebP·AVIF → WebP. HEIC 미지원. PDF·TXT·CSV는 원본 저장. 서버가 내용을 검사합니다.</p><button :disabled="busy||!chosen">업로드</button></form></section>
  <section v-if="detail"><h2>파일 상세</h2><dl><dt>ID</dt><dd>{{ detail.id }}</dd><dt>업로더 ID</dt><dd>{{ detail.createdBy }}</dd><dt>SHA-256</dt><dd>{{ detail.sha256 }}</dd></dl></section>
  <ConfirmDialog :open="pendingDelete!==null" title="파일 삭제" confirm-label="파일 삭제" :busy="busy" :error="error" @cancel="pendingDelete=null" @confirm="remove"><p>{{ pendingDelete?.originalName }} 파일을 목록에서 제거합니다.</p></ConfirmDialog>
</template>
