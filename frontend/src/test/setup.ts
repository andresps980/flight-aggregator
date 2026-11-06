// Runs before every test file (vite.config.ts > test.setupFiles).
import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterAll, afterEach, beforeAll } from 'vitest';
import { server } from './server';

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
    cleanup();
    server.resetHandlers();
    server.events.removeAllListeners();
    window.history.replaceState(null, '', '/');
});
afterAll(() => server.close());
