import { describe, it, expect } from 'vitest'
import { locoPage, locoQuery, locoRow } from './locoModel'

describe('LOCO read contracts', () => {
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
