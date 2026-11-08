import { readFileSync } from 'node:fs'
const expected = JSON.parse(readFileSync(new URL('../package.json', import.meta.url), 'utf8')).engines
if (process.versions.node !== expected.node || !process.env.npm_config_user_agent?.startsWith(`npm/${expected.npm} `)) {
  throw new Error(`Use Node ${expected.node} / npm ${expected.npm}. Windows: . ./prepare-node.ps1`)
}
