import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// 개발 중엔 vite(5173)가 /api 를 스프링(기본 4100)으로 넘긴다. 운영은 스프링 jar 가 dist 를 직접 서빙
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': `http://127.0.0.1:${process.env.WORKLOG_API_PORT ?? '4100'}`,
    },
  },
})
