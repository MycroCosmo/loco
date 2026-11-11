import { describe, expect, it } from 'vitest'
import { useQueryState } from './queryState'
import { ApiError, message } from '../api/client'

describe('조회 결과 상태', () => {
  it('최초 실패는 빈 결과가 아니며 재조회 실패는 이전 성공 결과와 구분된다', async () => {
    const query = useQueryState()
    const fail = async () => { throw new Error('offline') }
    await expect(query.load(fail)).rejects.toThrow('offline')
    expect(query.status.value).toBe('failed'); expect(query.hasResult.value).toBe(false)
    await query.load(async () => {})
    expect(query.status.value).toBe('ready'); expect(query.hasResult.value).toBe(true)
    await expect(query.load(fail)).rejects.toThrow('offline')
    expect(query.status.value).toBe('failed'); expect(query.hasResult.value).toBe(true)
  })
  it('화면 전환 전의 지연 응답으로 새 화면의 상태를 덮지 않는다', async () => {
    const query = useQueryState()
    let finish: () => void = () => { throw new Error('not started') }
    const old = query.load(() => new Promise<void>(resolve => { finish = resolve }))
    query.reset()
    await expect(query.load(async () => { throw new Error('new failure') })).rejects.toThrow()
    finish(); await old
    expect(query.status.value).toBe('failed'); expect(query.hasResult.value).toBe(false)
  })
  it('CSRF 재발급을 실제 완료한 경우에만 새로 받았다고 안내한다', () => {
    const error = new ApiError(403, 'CSRF_INVALID', 'raw')
    expect(message(error)).not.toContain('새로 받았습니다')
    error.csrfRefreshed = true
    expect(message(error)).toContain('새로 받았습니다')
    expect(message(new TypeError('Failed to fetch'))).toContain('연결 상태')
  })
})
