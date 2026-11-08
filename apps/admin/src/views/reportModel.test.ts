import { describe, it, expect } from 'vitest'
import { reportCommand, nextReportStates, reportProcessing, historyResources } from './reportModel'
describe('report processing contracts', () => {
  it('allows only forward transitions and no terminal edits', () => {
    expect(nextReportStates('RECEIVED')).toEqual(['IN_REVIEW'])
    expect(nextReportStates('IN_REVIEW')).toEqual(['RESOLVED', 'DISMISSED'])
    expect(nextReportStates('RESOLVED')).toEqual([])
    expect(nextReportStates('DISMISSED')).toEqual([])
    expect(() => reportCommand('RECEIVED', 'RESOLVED', 'note')).toThrow()
    expect(() => reportCommand('IN_REVIEW', 'IN_REVIEW', 'edit')).toThrow()
    expect(() => reportCommand('DISMISSED', 'IN_REVIEW', 'reopen')).toThrow()
  })
  it('requires terminal reasons and counts Unicode code points', () => {
    expect(reportCommand('RECEIVED', 'IN_REVIEW', '  ').note).toBeNull()
    expect(() => reportCommand('IN_REVIEW', 'RESOLVED', '  ')).toThrow()
    expect(reportCommand('IN_REVIEW', 'RESOLVED', '😀'.repeat(1000)).note).toBe('😀'.repeat(1000))
    expect(() => reportCommand('IN_REVIEW', 'RESOLVED', '😀'.repeat(1001))).toThrow()
  })
  it('rejects malformed processing and history scope responses', () => {
    expect(reportProcessing({ status: 'RECEIVED', note: null, handledBy: null, handledAt: null }).status).toBe('RECEIVED')
    expect(() => reportProcessing({ status: 'OTHER', note: null, handledBy: null, handledAt: null })).toThrow()
    expect(historyResources(['loco.report'])).toEqual(['loco.report'])
    expect(() => historyResources([42])).toThrow()
  })
})
