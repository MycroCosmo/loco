import { displayCode } from './displayLabels'
import { object, text, nullableText, list, number, boolean } from '../api/contracts'
import { displayTime, historyFilters } from './historyModel'
import { reportLabels, reportStatus, reportProcessing, type ReportProcessing } from './reportModel'

export type LocoResource = 'users' | 'maps' | 'places' | 'reports'
type Relation = { resource: LocoResource; id: string; label: string }
type Filter = { key: string; label: string; options?: readonly string[] }
export const locoScreens: Record<LocoResource, { title: string; note: string; filters: readonly Filter[] }> = {
  reports: { title: '신고 조회·처리', note: '비공개 대상 포함. 조회(READ) 권한은 조회·처리 이력, 관리(MANAGE) 권한은 상태 처리를 허용합니다. 대상 업무 조회 권한이 있어야 대상 존재 여부와 이동 링크를 제공합니다.',
    filters: [{ key: 'id', label: '신고 ID' }, { key: 'status', label: '처리 상태', options: ['RECEIVED', 'IN_REVIEW', 'RESOLVED', 'DISMISSED'] }, { key: 'reporterId', label: '신고자 ID' },
      { key: 'targetId', label: '대상 ID' }, { key: 'targetType', label: '대상 유형', options: ['MAP', 'PLACE', 'USER'] }] },
  places: { title: '장소 조회', note: '비공개 포함: 장소 조회 권한은 모든 지도방의 장소를 조회합니다. 이미지 URL·전화번호는 제외합니다.',
    filters: [{ key: 'id', label: '장소 ID' }, { key: 'mapId', label: '지도방 ID' }, { key: 'createdById', label: '작성자 ID' },
      { key: 'search', label: '이름·주소 포함' }, { key: 'tag', label: '태그 정확 일치' }] },
  maps: { title: '지도방 조회', note: '비공개 포함: 지도방 조회 권한은 모든 지도방을 조회합니다. 초대 코드·이미지 URL은 제외합니다.',
    filters: [{ key: 'id', label: '지도방 ID' }, { key: 'ownerId', label: '방장 ID' }, { key: 'search', label: '제목 포함' },
      { key: 'visibility', label: '공개 여부', options: ['PUBLIC', 'PRIVATE'] }, { key: 'tag', label: '태그 정확 일치' }] },
  users: { title: '회원 조회', note: '이메일은 서버에서 마스킹합니다. 토큰·외부계정 ID·이미지 URL은 제공하지 않습니다.',
    filters: [{ key: 'id', label: '회원 ID' }, { key: 'search', label: '닉네임 포함' },
      { key: 'provider', label: '가입 방식', options: ['LOCAL', 'GOOGLE', 'KAKAO', 'NAVER'] },
      { key: 'status', label: '회원 상태', options: ['ACTIVE', 'DELETED', 'ALL'] }] },
}
export function locoRow(resource: LocoResource, detail = false) {
  return (raw: unknown) => {
    const v = object(raw)
    const fields: { label: string; value: string }[] = []
    const relations: Relation[] = []
    let processing: ReportProcessing | null = null
    const add = (label: string, value: unknown) => fields.push({ label, value: nullableText(value) ?? '없음' })
    if (resource === 'users') {
      add('닉네임', v.nickname); add('이메일 (마스킹)', v.emailMasked); add('가입 방식', displayCode(text(v.provider)))
      add('탈퇴 시각', v.deletedAt === null ? null : displayTime(text(v.deletedAt)))
    }
    if (resource === 'maps') {
      add('제목', v.title); add('방장 ID', v.ownerId); add('공개 여부', displayCode(text(v.visibility)))
      add('태그', v.tags === null ? null : list(text)(v.tags).join(', '))
      if (detail) add('설명', v.description)
      relations.push({ resource: 'users', id: text(v.ownerId), label: '방장 조회' })
    }
    if (resource === 'places') {
      add('이름', v.name); add('지도방 ID', v.mapId); add('작성자 ID', v.createdById)
      relations.push({ resource: 'maps', id: text(v.mapId), label: '지도방 조회' }, { resource: 'users', id: text(v.createdById), label: '작성자 조회' })
      if (detail) {
        add('주소', v.address); add('도로명 주소', v.roadAddress); add('분류', v.category); add('메모', v.memo)
        add('위도', String(number(v.latitude))); add('경도', String(number(v.longitude)))
        add('태그', v.tags === null ? null : list(text)(v.tags).join(', '))
        add('리뷰 수', String(number(v.reviewCount))); add('평균 평점', String(number(v.averageRating)))
      }
    }
    if (resource === 'reports') {
      add('처리 상태', reportLabels[reportStatus(v.status)])
      add('마지막 처리자 ID', v.handledBy)
      add('마지막 처리 시각', v.handledAt === null ? null : displayTime(text(v.handledAt)))
      add('신고자 ID', v.reporterId); add('대상 유형', displayCode(text(v.targetType))); add('대상 ID', v.targetId); add('사유', v.reason)
      relations.push({ resource: 'users', id: text(v.reporterId), label: '신고자 조회' })
      if (detail) {
        processing = reportProcessing(v)
        add('처리 메모', processing.note)
        add('내용', v.detail)
        add('대상 상태', v.targetExists === null ? '대상 업무 조회 권한 필요' : boolean(v.targetExists) ? '존재' : '대상 없음')
        const target = text(v.targetType)
        if (!['USER', 'MAP', 'PLACE'].includes(target)) throw new Error('알 수 없는 신고 대상입니다.')
        if (v.targetExists === true) relations.push({ resource: target === 'USER' ? 'users' : target === 'MAP' ? 'maps' : 'places', id: text(v.targetId), label: '신고 대상 조회' })
      }
    }
    add('생성 시각 (한국)', displayTime(text(v.createdAt)))
    if (v.updatedAt !== undefined) add('수정 시각 (한국)', displayTime(text(v.updatedAt)))
    return { id: text(v.id), fields, relations, processing }
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
