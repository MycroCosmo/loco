import { spawn } from 'node:child_process'
import { createWriteStream, existsSync, mkdirSync, readFileSync, rmSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const admin = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const host = resolve(admin, '../server-v3'), output = resolve(host, 'build/admin-e2e')
const runtimeFile = resolve(output, 'runtime.json')
mkdirSync(output, { recursive: true })
// This is exclusively our ephemeral fixture, never a development DB or user file.
rmSync(runtimeFile, { force: true })
const log = createWriteStream(resolve(output, 'host.log'))
const windows = process.platform === 'win32'
const command = windows ? 'cmd.exe' : './gradlew'
const args = windows ? ['/d', '/c', 'gradlew.bat', 'adminE2eHost', '--console=plain'] : ['adminE2eHost', '--console=plain']
const server = spawn(command, args, { cwd: host, windowsHide: true, env: { ...process.env, ADMIN_NODE_HOME: dirname(process.execPath) }, stdio: ['ignore', 'pipe', 'pipe'] })
server.stdout.pipe(log); server.stderr.pipe(log)
let ended = false, spawnError
server.on('exit', () => { ended = true }); server.on('error', error => { spawnError = error; ended = true })
const start = Date.now()
let runtime
let result = 1
try {
  while (!existsSync(runtimeFile)) {
    if (ended) throw new Error(`E2E host exited. Inspect ${output}/host.log`, {cause:spawnError})
    if (Date.now()-start > 240000) throw new Error('E2E host startup exceeded 240 seconds')
    await new Promise(resolve => setTimeout(resolve, 250))
  }
  runtime = JSON.parse(readFileSync(runtimeFile, 'utf8'))
  const tests = spawn(process.execPath, [resolve(admin, 'node_modules/@playwright/test/cli.js'), 'test', ...process.argv.slice(2)], {
    cwd:admin, windowsHide:true, stdio:'inherit', env:{...process.env, CMS_E2E_RUNTIME:runtimeFile},
  })
  result = await new Promise((resolve, reject) => { tests.on('exit', code=>resolve(code??1)); tests.on('error',reject) })
} finally {
  if (!runtime && existsSync(runtimeFile)) runtime=JSON.parse(readFileSync(runtimeFile,'utf8'))
  if (runtime) {
    try { await fetch(runtime.controlUrl+'/stop', {method:'POST',headers:{'X-Test-Control':runtime.controlSecret},signal:AbortSignal.timeout(5000)}) } catch {}
  }
  const deadline=Date.now()+15000
  while(!ended && Date.now()<deadline) await new Promise(resolve=>setTimeout(resolve,200))
  if(!ended) {
    if(windows) await new Promise(resolve=>spawn('taskkill',['/PID',String(server.pid),'/T','/F'],{windowsHide:true,stdio:'ignore'}).on('exit',resolve))
    else server.kill('SIGTERM')
  }
  log.end()
  rmSync(runtimeFile,{force:true})
  console.log(`E2E total including host start/stop: ${((Date.now()-start)/1000).toFixed(3)} s`)
}
process.exitCode=result
