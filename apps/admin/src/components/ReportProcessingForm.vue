<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ApiError, locoApi, message } from '../api/client'
import { useAction } from '../composables/action'
import { nextReportStates, reportCommand, reportLabels, reportProcessing, type ReportProcessing, type ReportStatus } from '../views/reportModel'
const props = defineProps<{ id: string; value: ReportProcessing }>()
const emit = defineEmits<{ refresh: [notice: string] }>()
const note = ref(''), next = ref<ReportStatus>('IN_REVIEW')
const choices = computed(() => nextReportStates(props.value.status))
const { busy, error, run } = useAction()
watch(() => props.value, value => { note.value = ''; next.value = nextReportStates(value.status)[0] ?? value.status }, { immediate: true })
function submit() { void run(async () => {
  try {
    await locoApi.send(`/reports/${props.id}/processing`, 'PUT', reportCommand(props.value.status, next.value, note.value), reportProcessing)
    note.value = ''; emit('refresh', '처리 상태를 저장했습니다.')
  } catch (cause) {
    if (cause instanceof ApiError && (cause.status === 401 || cause.status === 409 || cause.code === 'PERMISSION_DENIED')) {
      note.value = ''
      if (cause.status !== 401) emit('refresh', message(cause))
    }
    throw cause
  }
}) }
</script>
<template>
  <form v-if="choices.length" aria-label="신고 처리" @submit.prevent="submit">
    <h3>신고 처리 기록</h3><p>회원 정지·강제 비공개·앱 알림은 실행하지 않습니다. </p>
    <label for="report-next">다음 상태</label><select id="report-next" v-model="next" :disabled="busy"><option v-for="state in choices" :key="state" :value="state">{{ reportLabels[state] }}</option></select>
    <label for="report-note">처리 메모 (종결 시 필수)</label><textarea id="report-note" v-model="note" :disabled="busy" :required="next !== 'IN_REVIEW'" aria-describedby="report-note-help report-error" />
    <p id="report-note-help">최대 1,000자. 개인정보·비밀번호·토큰을 입력하지 마세요. 원문은 최신 처리 기록에만 저장되고 이력에는 포함되지 않습니다. {{ Array.from(note).length }}/1,000</p>
    <p id="report-error" role="alert">{{ error }}</p><p id="report-final-help">종결하면 메모 수정이나 재개가 불가능합니다. 선택한 결과와 메모를 확인하세요.</p><button :disabled="busy" aria-describedby="report-final-help">{{ next === 'IN_REVIEW' ? '검토 시작' : next === 'RESOLVED' ? '검토 완료로 종결' : '기각으로 종결' }}</button>
  </form>
  <p v-else>종결된 신고입니다. 상태·메모를 변경할 수 없습니다.</p>
</template>
