/// <reference types="vitest/config" />
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// One config for the dev server, the production build and the tests (Vitest reads the `test` block).
export default defineConfig({
  plugins: [react()],
  server: {
    // `npm run dev`: same-origin calls, like nginx in production. The aggregator runs on 8080
    // (`./mvnw -pl flight-aggregator spring-boot:run` or `docker compose up`), gds-mock on 8081.
    proxy: {
      '/api': 'http://localhost:8080',
      '/gds-admin': {
        target: 'http://localhost:8081',
        rewrite: (path) => path.replace(/^\/gds-admin/, '/admin'),
      },
    },
  },
  test: {
    // Unit tests only; e2e/ holds the Playwright layout tests (npm run test:layout).
    include: ['src/**/*.test.{ts,tsx}'],
    environment: 'jsdom',
    // The page URL in tests; the client resolves `/api/...` against it, as the browser does.
    environmentOptions: { jsdom: { url: 'http://localhost:3000/' } },
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    restoreMocks: true,
    coverage: {
      provider: 'v8',
      include: ['src/**/*.{ts,tsx}'],
      exclude: ['src/**/*.test.{ts,tsx}', 'src/test/**', 'src/api/schema.d.ts', 'src/main.tsx'],
      thresholds: { lines: 80, branches: 70 },
    },
  },
});
