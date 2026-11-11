import { test, expect } from '@playwright/test'
import { readFileSync, mkdirSync } from 'node:fs'
import { resolve } from 'node:path'
import { object, text } from '../src/api/contracts'

// Explicit capture runs use only the isolated E2E host and its synthetic accounts.
const phase = process.env.UX_CAPTURE_PHASE
test.skip(phase !== 'before' && phase !== 'after', 'Set UX_CAPTURE_PHASE for review screenshots')
const runtime = object(JSON.parse(readFileSync(process.env.CMS_E2E_RUNTIME ?? '', 'utf8')))
for (const width of [1280, 390]) {
  test(`CMS review screenshots ${width}px`, async ({ page, request }) => {
    await request.post(text(runtime.controlUrl) + '/reset', { headers: { 'X-Test-Control': text(runtime.controlSecret) } })
    const directory = resolve('../../docs/ux-copy', phase ?? 'unused')
    mkdirSync(directory, { recursive: true })
    await page.setViewportSize({ width, height: width === 390 ? 844 : 720 })
    await page.goto('/admin/login')
    await expect(page.getByRole('heading', { name: '관리자 로그인' })).toBeVisible()
    await page.screenshot({ path: resolve(directory, `cms-login-${width}.png`), fullPage: true })
    await page.getByLabel('로그인 ID', { exact: true }).fill('root')
    await page.getByLabel('비밀번호', { exact: true }).fill(text(runtime.password))
    await page.getByRole('button', { name: '로그인', exact: true }).click()
    await expect(page.getByRole('heading', { name: '내 접근 근거' })).toBeVisible()
    await page.goto('/admin/loco/users')
    await expect(page.getByRole('table')).toContainText('회원1')
    await page.screenshot({ path: resolve(directory, `cms-users-${width}.png`), fullPage: true })
    await page.goto('/admin/loco/reports?id=00000000-0000-4000-8000-000000000401')
    await page.getByRole('button', { name: /^상세 / }).click()
    await expect(page.getByRole('form', { name: '신고 처리', exact: true })).toBeVisible()
    await page.screenshot({ path: resolve(directory, `cms-report-${width}.png`), fullPage: true })
  })
}
