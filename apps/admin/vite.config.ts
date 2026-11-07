import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  base: '/admin/',
  plugins: [vue()],
  server: { proxy: { '/admin/api': 'http://127.0.0.1:8081' } },
  test: { include: ['src/**/*.test.ts'], environment: 'node' },
})
