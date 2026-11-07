import { defineConfig } from '@playwright/test'
import { readFileSync } from 'node:fs'
const runtime = JSON.parse(readFileSync(process.env.CMS_E2E_RUNTIME ?? '', 'utf8'))

export default defineConfig({
  testDir: './e2e', fullyParallel: false, workers: 1, retries: 0, timeout: 30000,
  reporter: [['list'], ['json', {outputFile:'test-results/summary.json'}]],
  use: { browserName:'chromium', baseURL:runtime.baseUrl, trace:'off', screenshot:'off', video:'off' },
})
