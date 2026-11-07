import { describe, it, expect } from 'vitest'
import { locoPage, locoQuery, locoRow } from './locoModel'
import { templatePermissions } from './roleModel'
import { permission } from '../api/contracts'

describe('LOCO read contracts', () => {
  it('does not offer read-only business permissions in the manager template', () => {
    const permissions = [{key:'loco.maps',label:'비공개 포함',allowedActions:['READ'],systemOnly:false}, {key:'cms.menus',label:'메뉴',allowedActions:['READ','MANAGE'],systemOnly:false}].map(permission)
    expect(templatePermissions(permissions,'MANAGE').map(p=>p.key)).toEqual(['cms.menus'])
    expect(templatePermissions(permissions,'READ')).toHaveLength(2)
  })
  it('keeps report body as text and distinguishes absent and unauthorized targets', () => {
    const report = { id: 'r1', reporterId: 'u1', targetType: 'MAP', targetId: 'm1', reason: 'reason', detail: '<script>text</script>', targetExists: null, createdAt: '2026-01-01T00:00:00Z' }
    expect(locoRow('reports', true)(report).relations).toHaveLength(1)
    expect(locoRow('reports', true)({ ...report, targetExists: false }).fields.find(f => f.label === '대상 상태')?.value).toBe('대상 없음')
    expect(locoRow('reports', true)({ ...report, targetExists: true }).relations).toHaveLength(2)
    expect(locoRow('reports', true)(report).fields.find(f => f.label === '내용')?.value).toBe('<script>text</script>')
  })
  it('validates place aggregates and excludes image and phone fields', () => {
    const place = { id: 'p1', name: 'P', mapId: 'm1', createdById: 'u1', address: 'A', roadAddress: null, category: null, memo: null, latitude: 37.05, longitude: -0.00001, tags: [], reviewCount: 2, averageRating: 3.5, phone: 'hidden', imageUrls: ['hidden'], createdAt: '2026-01-01T00:00:00Z' }
    expect(locoRow('places', true)(place).fields.find(f => f.label === '평균 평점')?.value).toBe('3.5')
    expect(JSON.stringify(locoRow('places', true)(place))).not.toContain('hidden')
    expect(() => locoRow('places', true)({ ...place, averageRating: '3.5' })).toThrow()
  })
  it('shows private maps and descriptions only in detail, without invitation data', () => {
    const map = { id: 'm1', title: 'Private', ownerId: 'u1', visibility: 'PRIVATE', tags: ['a'], createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z', description: 'text', inviteCode: 'hidden' }
    expect(locoRow('maps')(map).fields.some(f => f.label === '설명')).toBe(false)
    expect(locoRow('maps', true)(map).fields.find(f => f.label === '설명')?.value).toBe('text')
    expect(JSON.stringify(locoRow('maps', true)(map))).not.toContain('hidden')
  })
  const member = { id: 'u1', nickname: '<script>text</script>', emailMasked: '김***@***', provider: 'LOCAL', deletedAt: null, createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z' }
  it('renders only approved fields, never unknown raw properties', () => {
    const row = locoRow('users')({ ...member, email: 'never@example.test', fcmToken: 'never-token' })
    expect(JSON.stringify(row)).not.toContain('never')
    expect(row.fields.find(f => f.label === '닉네임')?.value).toBe('<script>text</script>')
  })
  it('requires the server masked value and valid page shape', () => {
    expect(() => locoRow('users')({ ...member, emailMasked: 1 })).toThrow()
    expect(locoPage('users')({ items: [member], nextCursor: null }).items).toHaveLength(1)
  })
  it('keeps literal wildcard search and converts Korean period to UTC', () => {
    const p = new URLSearchParams(locoQuery('users', { search: ' %_ ', secret: 'hidden' }, '2026-01-01T09:00', '', 50))
    expect(p.get('from')).toBe('2026-01-01T00:00:00.000Z')
    expect(p.get('search')).toBe('%_'); expect(p.has('secret')).toBe(false)
    expect(() => locoQuery('users', {}, '', '', 101)).toThrow()
  })
})
