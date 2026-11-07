import { object, text, nullableText, list } from '../api/contracts'
import { displayTime, historyFilters } from './historyModel'

export type LocoResource = 'users'
type Filter = { key: string; label: string; options?: readonly string[] }
export const locoScreens: Record<LocoResource, { title: string; note: string; filters: readonly Filter[] }> = {
  users: { title: '회원 조회', note: '이메일은 서버에서 마스킹합니다. 토큰·외부계정 ID·이미지 URL은 제공하지 않습니다.',
    filters: [{ key: 'id', label: '회원 ID' }, { key: 'search', label: '닉네임 포함' },
      { key: 'provider', label: '가입 방식', options: ['LOCAL', 'GOOGLE', 'KAKAO', 'NAVER'] },
      { key: 'status', label: '회원 상태', options: ['ACTIVE', 'DELETED', 'ALL'] }] },
}
export function locoRow(resource: LocoResource, detail = false) {
  return (raw: unknown) => {
    const v = object(raw)
    const fields: { label: string; value: string }[] = []
    const add = (label: string, value: unknown) => fields.push({ label, value: nullableText(value) ?? '없음' })
    if (resource === 'users') {
      add('닉네임', v.nickname); add('이메일 (마스킹)', v.emailMasked); add('가입 방식', v.provider)
      add('탈퇴 시각', v.deletedAt === null ? null : displayTime(text(v.deletedAt)))
    }
    add('생성 시각 (한국)', displayTime(text(v.createdAt)))
    if (v.updatedAt !== undefined) add('수정 시각 (한국)', displayTime(text(v.updatedAt)))
    return { id: text(v.id), fields }
  }
}
export function locoPage(resource: LocoResource) {
  return (raw: unknown) => { const v = object(raw); return { items: list(locoRow(resource))(v.items), nextCursor: nullableText(v.nextCursor) } }
}
export function locoQuery(resource: LocoResource, filters: Record<string, string>, from: string, until: string, size: number) {
  if (!Number.isInteger(size) || size < 1 || size > 100) throw new Error('페이지 크기는 1~100이어야 합니다.')
  const params = new URLSearchParams(historyFilters({ resourceType: '', resourceId: '', actorId: '', from, until }))
  params.set('size', String(size))
  for (const filter of locoScreens[resource].filters) {
    const value = filters[filter.key]?.trim()
    if (value) params.set(filter.key, value)
  }
  return params.toString()
}
