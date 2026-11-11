import { createServer } from 'node:http'
import { readFile, stat, mkdir } from 'node:fs/promises'
import { resolve, extname, sep } from 'node:path'
import { chromium } from '@playwright/test'

// Separate fixture-only browser: never uses the running preview or external services.
const [directory, phase] = process.argv.slice(2)
if (!directory || !['before', 'after'].includes(phase)) throw new Error('Usage: node scripts/capture-flutter.mjs BUILD_DIRECTORY before|after')
const root = resolve(directory)
const output = resolve('../../docs/ux-copy', phase)
await mkdir(output, { recursive: true })
const types = { '.html': 'text/html', '.js': 'text/javascript', '.json': 'application/json', '.wasm': 'application/wasm', '.png': 'image/png', '.ttf': 'font/ttf', '.otf': 'font/otf' }
const server = createServer(async (request, response) => {
  try {
    let file = resolve(root, '.' + decodeURIComponent(new URL(request.url, 'http://localhost').pathname))
    if (file !== root && !file.startsWith(root + sep)) { response.writeHead(403).end(); return }
    if (!(await stat(file).catch(() => null))?.isFile()) file = resolve(root, 'index.html')
    response.writeHead(200, { 'Content-Type': types[extname(file)] ?? 'application/octet-stream' })
    response.end(await readFile(file))
  } catch { response.writeHead(500).end() }
})
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
const origin = `http://127.0.0.1:${server.address().port}`
const font = await readFile(process.env.UX_CAPTURE_FONT ?? 'C:/Windows/Fonts/malgun.ttf')
const browser = await chromium.launch()
try {
  for (const width of [390, 1280]) {
    const page = await browser.newPage({ viewport: { width, height: width === 390 ? 844 : 720 } })
    page.setDefaultTimeout(15000)
    await page.route('**/*', route => {
      const url = new URL(route.request().url())
      if (url.pathname === '/api/auth/forgot-password') return route.fulfill({ json: { message: '이메일로 재설정 코드를 전송했습니다.' } })
      if (url.pathname === '/api/users/me') return route.fulfill({ json: { id: '00000000-0000-4000-8000-000000000001', nickname: '샘플 회원', email: 'sample@example.test', provider: 'LOCAL' } })
      if (['/api/maps/me','/api/places/me','/api/places/reviews/me','/api/favorites'].includes(url.pathname)) return route.fulfill({ json: [] })
      if (url.pathname === '/api/maps') return route.fulfill({ json: { items: [], nextCursor: null, hasNext: false } })
      if (url.hostname === 'fonts.gstatic.com') return route.fulfill({ body: font, contentType: 'font/ttf' })
      if (url.origin === origin) return route.continue()
      return route.abort('blockedbyclient')
    })
    page.on('pageerror', error => console.error(error.message))
    console.log('login', width)
    await page.goto(origin)
    await page.locator('flt-semantics-placeholder').dispatchEvent('click')
    await page.getByRole('textbox', { name: '이메일' }).waitFor()
    await page.waitForLoadState('networkidle')
    await page.waitForTimeout(1500)
    await page.screenshot({ path: resolve(output, `flutter-login-${width}.png`) })
    await page.getByRole('button', { name: '비밀번호를 잊으셨나요?' }).click()
    await page.getByRole('textbox', { name: '이메일' }).pressSequentially('sample@example.test', { delay: 30 })
    await page.getByRole('textbox', { name: '이메일' }).press('Tab')
    await page.getByRole('button', { name: '인증 코드 받기' }).click()
    await page.getByRole('textbox', { name: '인증 코드 (6자리)' }).waitFor()
    await page.waitForLoadState('networkidle')
    await page.waitForTimeout(1500)
    await page.screenshot({ path: resolve(output, `flutter-reset-${width}.png`) })
    await page.evaluate(() => localStorage.setItem('loco_access_token', 'fixture-only-no-valid-token'))
    console.log('create', width)
    await page.goto(origin + '/maps/create')
    await page.locator('flt-semantics-placeholder').dispatchEvent('click')
    await page.getByRole('textbox', { name: /지도(방)? 이름/ }).waitFor()
    await page.waitForLoadState('networkidle')
    await page.waitForTimeout(1500)
    await page.screenshot({ path: resolve(output, `flutter-map-create-${width}.png`) })
    await page.getByRole('button', { name: '비공개', exact: true }).scrollIntoViewIfNeeded()
    await page.getByRole('button', { name: '비공개', exact: true }).click()
    await page.waitForLoadState('networkidle')
    await page.waitForTimeout(1500)
    await page.screenshot({ path: resolve(output, `flutter-map-private-${width}.png`) })
    console.log('profile', width)
    await page.goto(origin + '/profile')
    await page.locator('flt-semantics-placeholder').dispatchEvent('click')
    await page.getByRole('button', { name: '회원 탈퇴', exact: true }).click()
    await page.getByRole('button', { name: '탈퇴', exact: true }).waitFor()
    await page.waitForLoadState('networkidle')
    await page.waitForTimeout(1500)
    await page.screenshot({ path: resolve(output, `flutter-withdraw-${width}.png`) })
    if (width === 390) {
      for (const [name, path] of [
        ['map-list', '/maps'], ['profile', '/profile'], ['profile-edit', '/profile/edit'],
        ['owned-maps', '/profile/maps/owned'], ['joined-maps', '/profile/maps/joined'],
        ['my-places', '/profile/places'], ['my-reviews', '/profile/reviews'], ['favorites', '/favorites'],
        ['place-add', '/maps/fixture/places/add'],
      ]) {
        await page.goto(origin + path)
        await page.locator('flt-semantics-placeholder').dispatchEvent('click')
        await page.waitForLoadState('networkidle')
        await page.waitForTimeout(1500)
        await page.screenshot({ path: resolve(output, `flutter-${name}-${width}.png`) })
      }
      await page.evaluate(() => localStorage.clear())
      await page.goto(origin + '/register')
      await page.locator('flt-semantics-placeholder').dispatchEvent('click')
      await page.waitForLoadState('networkidle')
      await page.waitForTimeout(1500)
      await page.screenshot({ path: resolve(output, `flutter-register-${width}.png`) })
    }
    await page.close()
  }
} finally {
  await browser.close()
  await new Promise(resolve => server.close(resolve))
}
