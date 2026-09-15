import { defineConfig } from 'vite';

// 목업용 최소 설정. 번들러는 HMR 때문에 쓴다 — 화면을 고칠 때마다
// 서버를 재시작하지 않으려는 것이 목적이다.
export default defineConfig({
  server: {
    port: 5173,
    // /api 는 Spring Boot 로 넘긴다. CORS 설정이 없어도 개발이 된다.
    proxy: { '/api': 'http://localhost:8080' },
  },
});
