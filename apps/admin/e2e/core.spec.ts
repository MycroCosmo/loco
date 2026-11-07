import { test, expect, type Page } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { object, text } from '../src/api/contracts'

const raw=object(JSON.parse(readFileSync(process.env.CMS_E2E_RUNTIME??'', 'utf8')))
const runtime={controlUrl:text(raw.controlUrl),controlSecret:text(raw.controlSecret),password:text(raw.password),firstPassword:text(raw.firstPassword),baseUrl:text(raw.baseUrl)}
async function login(page:Page, first=false) {
  await page.goto('/admin/login')
  await page.getByLabel('로그인 ID',{exact:true}).fill(first?'first':'root')
  await page.getByLabel('비밀번호',{exact:true}).fill(first?runtime.firstPassword:runtime.password)
  await page.getByRole('button',{name:'로그인',exact:true}).click()
  await expect(page.getByRole('heading',{name:first?'비밀번호 변경':'내 접근 근거',exact:true})).toBeVisible()
}
test.beforeEach(async ({request})=>{
  const reset=await request.post(runtime.controlUrl+'/reset',{headers:{'X-Test-Control':runtime.controlSecret}})
  expect(reset.status()).toBe(200)
})

test('로그인·첫 비밀번호 변경·새로고침·재로그인',async({page})=>{
  await login(page,true)
  await page.reload()
  await expect(page.getByRole('heading',{name:'비밀번호 변경',exact:true})).toBeVisible()
  await expect(page.getByLabel('현재 비밀번호')).toHaveAttribute('aria-describedby','password-error')
  await page.getByLabel('현재 비밀번호').fill(runtime.firstPassword)
  await page.getByLabel('새 비밀번호',{exact:true}).fill(runtime.firstPassword+'-changed')
  await page.getByLabel('새 비밀번호 확인').fill(runtime.firstPassword+'-changed')
  await page.getByRole('button',{name:'비밀번호 변경',exact:true}).click()
  await expect(page.getByRole('heading',{name:'관리자 로그인'})).toBeVisible()
  await expect(page.getByRole('status')).toContainText('비밀번호 변경 완료')
  await page.getByLabel('로그인 ID',{exact:true}).fill('first')
  await page.getByLabel('비밀번호',{exact:true}).fill(runtime.firstPassword+'-changed')
  await page.getByRole('button',{name:'로그인',exact:true}).click()
  await expect(page.getByRole('heading',{name:'내 접근 근거'})).toBeVisible()
  await expect(page.getByRole('navigation',{name:'관리 도구'}).getByRole('link',{name:'관리자 계정'})).toHaveCount(0)
})

test('CSRF 실패는 정확한 코드로 안내하고 쓰기를 자동 재전송하지 않는다',async({page})=>{
  await login(page)
  let writes=0
  page.on('request',request=>{if(request.url().endsWith('/auth/logout')&&request.method()==='POST')writes++})
  await page.route('**/admin/api/core/auth/logout',async route=>{
    const headers={...route.request().headers()}; delete headers['x-xsrf-token']
    await route.continue({headers})
  },{times:1})
  const failed=page.waitForResponse(r=>r.url().endsWith('/auth/logout'))
  await page.getByRole('button',{name:'로그아웃'}).click()
  expect((await (await failed).json()).code).toBe('CSRF_INVALID')
  await expect(page.getByRole('alert').filter({hasText:'보안 토큰'})).toBeVisible()
  expect(writes).toBe(1)
  await expect(page.getByRole('heading',{name:'내 접근 근거'})).toBeVisible()
  await page.getByRole('button',{name:'로그아웃'}).click()
  await expect(page.getByRole('heading',{name:'관리자 로그인'})).toBeVisible()
  expect(writes).toBe(2)
})

test('서버 idle 만료가 다음 요청에서 로그인으로 복귀시킨다',async({page,request})=>{
  await login(page)
  expect((await request.post(runtime.controlUrl+'/expire',{headers:{'X-Test-Control':runtime.controlSecret}})).status()).toBe(200)
  const expired=page.waitForResponse(r=>r.status()===401&&r.url().includes('/admin/api/'))
  await page.getByRole('navigation',{name:'관리 도구'}).getByRole('link',{name:'파일',exact:true}).click()
  expect((await (await expired).json()).code).toBe('SESSION_EXPIRED')
  await expect(page.getByRole('heading',{name:'관리자 로그인'})).toBeVisible()
  await expect(page.getByRole('status')).toContainText('세션이 만료')
})

test('두 관리자의 메뉴 변경은 revision 충돌 후 재미리보기가 필요하다',async({page,browser})=>{
  const other=await browser.newContext({baseURL:runtime.baseUrl})
  try {
    const peer=await other.newPage()
    await login(page);await login(peer)
    await page.getByRole('navigation',{name:'관리 도구'}).getByRole('link',{name:'메뉴 트리'}).click()
    await peer.getByRole('navigation',{name:'관리 도구'}).getByRole('link',{name:'메뉴 트리'}).click()
    await page.getByRole('button',{name:'그룹 A',exact:true}).click()
    const before=await page.locator('li').filter({has:page.getByRole('button',{name:'하위 파일',exact:true})}).locator('small').textContent()
    const b=await page.getByLabel('새 부모').locator('option').filter({hasText:'그룹 B'}).getAttribute('value')
    expect(b).not.toBeNull()
    await page.getByLabel('새 부모').selectOption(b??'')
    await page.getByRole('button',{name:'이동 미리보기',exact:true}).click()
    const dialog=page.getByRole('dialog',{name:'메뉴 이동 미리보기'})
    await expect(dialog).toContainText('권한 변화 없음')
    await expect(dialog.getByRole('button',{name:'취소'})).toBeFocused()
    await page.keyboard.press('Escape')
    await expect(page.getByRole('button',{name:'이동 미리보기',exact:true})).toBeFocused()
    await page.getByRole('button',{name:'이동 미리보기',exact:true}).press('Enter')
    await peer.getByLabel('새 메뉴 제목').fill('다른 관리자의 변경')
    await peer.getByRole('button',{name:'메뉴 생성',exact:true}).click()
    await expect(peer.getByRole('status').filter({hasText:'메뉴 생성 완료'})).toBeVisible()
    const conflict=page.waitForResponse(r=>r.url().endsWith('/position')&&r.request().method()==='PUT')
    await dialog.getByRole('button',{name:'확인'}).click()
    expect((await (await conflict).json()).code).toBe('STALE_REVISION')
    await expect(page.getByRole('alert').filter({hasText:'다른 관리자가 메뉴를 변경'})).toBeVisible()
    await expect(dialog).not.toBeVisible()
    await page.getByLabel('새 부모').selectOption(b??'')
    await page.getByRole('button',{name:'이동 미리보기',exact:true}).click()
    await dialog.getByRole('button',{name:'확인'}).click()
    await expect(page.getByRole('status').filter({hasText:'이동 완료'})).toBeVisible()
    expect(await page.locator('li').filter({has:page.getByRole('button',{name:'하위 파일',exact:true})}).locator('small').textContent()).toBe(before)
  } finally { await other.close() }
})

test('로컬 SeaweedFS 파일 업로드·다운로드 바이트가 같다',async({page})=>{
  await login(page)
  await page.getByRole('navigation',{name:'관리 도구'}).getByRole('link',{name:'파일',exact:true}).click()
  const bytes=Buffer.from('CMS local storage browser contract\n','utf8')
  await page.getByLabel('파일 (10MiB 이하)').setInputFiles({name:'browser.txt',mimeType:'text/plain',buffer:bytes})
  await page.getByRole('button',{name:'업로드',exact:true}).click()
  const button=page.getByRole('button',{name:'다운로드 browser.txt',exact:true})
  await expect(button).toBeVisible()
  const event=page.waitForEvent('download')
  await button.click()
  const download=await event
  expect(download.suggestedFilename()).toBe('browser.txt')
  const path=await download.path()
  expect(path).not.toBeNull()
  expect(readFileSync(path??'')).toEqual(bytes)
})
