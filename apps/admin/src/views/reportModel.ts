import { object, text, nullableText } from '../api/contracts'

export type ReportStatus = 'RECEIVED' | 'IN_REVIEW' | 'RESOLVED' | 'DISMISSED'
export function reportStatus(value: unknown): ReportStatus {
  if (value === 'RECEIVED' || value === 'IN_REVIEW' || value === 'RESOLVED' || value === 'DISMISSED') return value
  throw new Error('알 수 없는 신고 처리 상태입니다.')
}
export const reportLabels: Record<ReportStatus, string> = { RECEIVED: '접수', IN_REVIEW: '검토 중', RESOLVED: '검토 완료 (앱 조치 없음)', DISMISSED: '기각' }
export function nextReportStates(status: ReportStatus): ReportStatus[] {
  return status === 'RECEIVED' ? ['IN_REVIEW'] : status === 'IN_REVIEW' ? ['RESOLVED', 'DISMISSED'] : []
}
export function reportProcessing(raw: unknown) {
  const v = object(raw)
  return { status: reportStatus(v.status), note: nullableText(v.note), handledBy: nullableText(v.handledBy), handledAt: nullableText(v.handledAt) }
}
export type ReportProcessing = ReturnType<typeof reportProcessing>
export function reportCommand(expectedStatus: ReportStatus, nextStatus: ReportStatus, rawNote: string) {
  if (!nextReportStates(expectedStatus).includes(nextStatus)) throw new Error('허용되지 않은 상태 전이입니다.')
  const note = rawNote.trim()
  if (Array.from(note).length > 1000) throw new Error('처리 메모는 1,000자 이하여야 합니다.')
  if (nextStatus !== 'IN_REVIEW' && !note) throw new Error('종결 사유를 입력하세요.')
  return { expectedStatus, nextStatus, note: note || null }
}
export function historyResources(raw: unknown): string[] {
  if (!Array.isArray(raw)) throw new Error('이력 열람 범위를 확인할 수 없습니다.')
  return raw.map(text)
}
