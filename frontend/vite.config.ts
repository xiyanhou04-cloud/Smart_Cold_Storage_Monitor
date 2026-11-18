/// <reference types="vitest/config" />
import { defineConfig, type Plugin } from 'vite';
import react from '@vitejs/plugin-react';

// The Spring Boot backend listens on 127.0.0.1:8080. In development the Vite server proxies API calls to it so the
// session cookie and CSRF token stay same-origin, exactly as when Spring Boot serves the built files.
const backend = process.env.BACKEND_URL ?? 'http://127.0.0.1:8080';
const useMock = process.env.MOCK_API === '1';

/** `npm run dev:mock`: answers /api from the in-memory FakeBackend instead of proxying to Spring Boot. */
function mockApi(): Plugin {
  return {
    name: 'mock-api',
    async configureServer(server) {
      const { FakeBackend } = await server.ssrLoadModule('/src/mock/fakeBackend.ts');
      const fake = new FakeBackend();
      server.middlewares.use('/api', async (req, res) => {
        const chunks: Buffer[] = [];
        for await (const chunk of req) chunks.push(chunk as Buffer);
        const body = chunks.length ? Buffer.concat(chunks).toString() : undefined;
        const headers = Object.fromEntries(Object.entries(req.headers).map(([k, v]) => [k, String(v)]));
        const response: Response = await fake.fetch('/api' + req.url, { method: req.method, headers, body });
        res.statusCode = response.status;
        response.headers.forEach((value, key) => res.setHeader(key, value));
        res.end(await response.text());
      });
    },
  };
}

export default defineConfig({
  plugins: [react(), ...(useMock ? [mockApi()] : [])],
  server: {
    port: 5173,
    proxy: useMock
      ? undefined
      : {
          '/api': backend,
          '/v3': backend,
          '/swagger-ui': backend,
        },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    css: false,
    coverage: {
      include: ['src/**/*.{ts,tsx}'],
      exclude: ['src/test/**', 'src/mock/**', 'src/main.tsx', 'src/**/*.test.*'],
    },
  },
});
